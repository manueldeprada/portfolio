package name.abuchen.portfolio.headless;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.runtime.NullProgressMonitor;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.ClientFileType;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.spi.HostApplication;
import name.abuchen.portfolio.rest.spi.OpenFile;

/**
 * The set of portfolio files this process owns, and the only place one is opened,
 * created, saved or closed.
 * <p>
 * Phase 1 loaded the configured files at startup and served exactly those until
 * shutdown, because that is what the desktop does - a file is open because a user
 * opened it. As the backend of a web service it is the other way round: an account
 * uploads a file, clones one, deletes one, and the daemon's configuration is not
 * where that belongs. So the set is mutable, and the caller that manages it is the
 * BFF over {@link ControlServer}.
 * <p>
 * Everything that mutates the set is {@code synchronized}, which is not the same
 * lock as the model thread: this serializes <em>open against close against
 * create</em>, whose invariants are the file lock and the registry, while the model
 * itself is only ever touched on the model thread ({@link HeadlessHost#syncExec}).
 * Both are needed, and confusing them is how a file ends up loaded twice.
 * <p>
 * <strong>One owner per file still holds.</strong> Every file in here is claimed with
 * a {@link PortfolioFileLock} for as long as it is open, and released when it is
 * closed - so a second daemon cannot take a file this one is serving, whether it was
 * opened at startup or five minutes ago.
 */
public class FileSet
{
    /** What was done to a file, for the caller's log line. */
    public enum Outcome
    {
        OPENED, ALREADY_OPEN
    }

    public record Opened(HeadlessHost.LoadedFile file, String id, Outcome outcome)
    {
    }

    /**
     * The format a file this daemon creates is written in: {@code .portfolio},
     * protobuf, compressed - the desktop's own default for a new file, so a user who
     * later opens it in the application sees nothing unusual.
     */
    private static final ClientFileType CREATE_FORMAT = ClientFileType.BINARY;

    private final FileAccessRegistry registry;
    private final int port;
    private final boolean backupOnOpen;

    /** Insertion-ordered, so /health and the control API list files predictably. */
    private final Map<Path, HeadlessHost.LoadedFile> files = new LinkedHashMap<>();
    private final Map<Path, PortfolioFileLock> locks = new LinkedHashMap<>();

    private volatile HostApplication host;

    public FileSet(FileAccessRegistry registry, int port, boolean backupOnOpen)
    {
        this.registry = registry;
        this.port = port;
        this.backupOnOpen = backupOnOpen;
    }

    /**
     * The model thread, supplied after construction because the host is built
     * <em>from</em> this set - it answers {@code listOpenFiles} out of it. Saving and
     * cloning need that thread; opening and closing do not touch a live model.
     */
    public void attach(HostApplication modelThread)
    {
        this.host = modelThread;
    }

    public synchronized List<HeadlessHost.LoadedFile> list()
    {
        return List.copyOf(files.values());
    }

    /** The same list as {@link #list()}, typed for {@link HeadlessHost}. */
    public synchronized List<OpenFile> listOpenFiles()
    {
        return List.copyOf(files.values());
    }

    /**
     * The registry uuid, which is what {@code GET /v1/files} reports as a file's
     * {@code id} and what every API path addresses it by. Deliberately not derived
     * from the path: a client must not have to know one to address the other.
     */
    public synchronized Optional<String> idOf(HeadlessHost.LoadedFile file)
    {
        return registry.byPath(file.getPath()).map(FileAccessRegistry.FileAccess::uuid);
    }

    public synchronized Optional<HeadlessHost.LoadedFile> byId(String id)
    {
        return files.values().stream().filter(file -> idOf(file).map(id::equals).orElse(false)).findFirst();
    }

    /**
     * Loads a file and exposes it over the API.
     * <p>
     * Idempotent: a file already open is returned as it is, rather than loaded a
     * second time into a second in-memory copy. That is what lets the BFF re-assert
     * its whole list of files on every start without tracking what the daemon
     * already has.
     */
    public synchronized Opened open(Path path, char[] password) throws IOException
    {
        return open(path, password, null);
    }

    /**
     * @param alias
     *            an optional short name the API accepts in place of the id, for a file
     *            named in the configuration by an operator who has to type its URLs.
     *            Files opened by a service in front of this daemon do not need one -
     *            the id is what it stores - and an alias it invented would be a second
     *            name for one thing.
     */
    public synchronized Opened open(Path path, char[] password, String alias) throws IOException
    {
        var absolute = path.toAbsolutePath().normalize();

        var existing = files.get(absolute);
        if (existing != null)
            return new Opened(existing, idOf(existing).orElse(null), Outcome.ALREADY_OPEN);

        var file = absolute.toFile();
        if (!file.isFile())
            throw new NoSuchFileException(absolute.toString());

        // Claimed before it is read: refusing after loading would leave a second copy
        // of a file this process does not own in memory.
        var lock = claim(absolute);

        HeadlessHost.LoadedFile loaded;
        try
        {
            var client = ClientFactory.load(file, password, new NullProgressMonitor());
            loaded = new HeadlessHost.LoadedFile(absolute.toString(), labelOf(file), client);
        }
        catch (IOException | RuntimeException e)
        {
            // The claim is only justified while the file is served; a file that failed
            // to load is not, and holding it would lock out the next attempt.
            if (lock != null)
                lock.close();
            throw e;
        }

        var record = registry.ensureRecord(absolute.toString());
        registry.setEnabled(absolute.toString(), true);

        if (alias != null)
        {
            try
            {
                registry.setAlias(absolute.toString(), alias);
            }
            catch (IllegalArgumentException e)
            {
                // Malformed, or already taken by another file. Not a reason to refuse
                // the file: its id addresses it either way, and the alias is a
                // convenience the operator asked for.
                PortfolioLog.warning("Ignoring alias \"" + alias + "\" for " + absolute + ": " + e.getMessage());
            }
        }

        if (lock != null)
            locks.put(absolute, lock);
        files.put(absolute, loaded);

        // One copy as the file was handed over, before this process writes to it.
        if (backupOnOpen)
            AutoSaver.backup(List.of(loaded));

        PortfolioLog.info("Opened " + absolute + " as " + record.uuid());
        return new Opened(loaded, record.uuid(), Outcome.OPENED);
    }

    /**
     * Writes an empty portfolio file and opens it. The base currency is the one thing
     * a new file cannot be given later without touching every account in it, which is
     * why the desktop asks for it in its new-file wizard and why it is the only
     * parameter here.
     */
    public synchronized Opened create(Path path, String baseCurrency) throws IOException
    {
        var absolute = path.toAbsolutePath().normalize();

        if (Files.exists(absolute))
            throw new FileAlreadyExistsException(absolute.toString());

        if (baseCurrency != null && CurrencyUnit.getInstance(baseCurrency) == null)
            throw new IOException("Unknown currency code: " + baseCurrency);

        var client = new Client();
        if (baseCurrency != null)
        {
            client.setBaseCurrency(baseCurrency);
            client.getAccounts().forEach(account -> account.setCurrencyCode(baseCurrency));
        }

        Files.createDirectories(absolute.getParent());
        ClientFactory.saveAs(client, absolute.toFile(), null, CREATE_FORMAT.getFlags());

        // Opened from disk rather than served out of the client just written, so what
        // is served is what a round trip through the file produces - the same thing
        // every other open file is.
        return open(absolute, null);
    }

    /**
     * Copies an open file to a new path and opens that.
     * <p>
     * Written from the live model on the model thread, not copied on disk, because the
     * source's unsaved changes are part of what the caller asked to duplicate and a
     * byte copy of a file being written is not a file.
     *
     * @throws IOException
     *             if the source is encrypted. Its password is not kept after the load
     *             (see {@link HeadlessApplication}), so the copy could only be written
     *             unencrypted, and silently dropping a file's encryption is not a
     *             thing to do on a user's behalf.
     */
    public Opened copy(String sourceId, Path target) throws IOException
    {
        HeadlessHost.LoadedFile source;
        Path absolute;

        synchronized (this)
        {
            source = byId(sourceId).orElseThrow(() -> new NoSuchFileException("No open file with id " + sourceId));
            absolute = target.toAbsolutePath().normalize();

            if (Files.exists(absolute))
                throw new FileAlreadyExistsException(absolute.toString());

            // Read off the file rather than the client: the model's save flags are
            // package-private, and the file on disk is the same answer.
            if (ClientFactory.isEncrypted(source.getFile()))
                throw new IOException("Cannot copy " + source.getLabel()
                                + ": it is encrypted, and this process does not keep its password.");

            Files.createDirectories(absolute.getParent());
        }

        // exportAs, not saveAs: saveAs adopts the flags it wrote onto the *source*
        // client, so cloning a compressed XML file would quietly change the format
        // the original is saved in from then on.
        runOnModelThread(() -> {
            ClientFactory.exportAs(source.getClient(), absolute.toFile(), null, CREATE_FORMAT.getFlags());
            return null;
        });

        return open(absolute, null);
    }

    /**
     * Saves the file if it has unsaved changes and gives it up: the lock is released,
     * the API stops seeing it, and its registry record is dropped.
     * <p>
     * {@code save} is false when the caller is about to delete the file. Writing it
     * one last time first would be work whose only effect is a longer window in which
     * the file exists.
     */
    public synchronized void close(String id, boolean save) throws IOException
    {
        var file = byId(id).orElseThrow(() -> new NoSuchFileException("No open file with id " + id));
        var path = Path.of(file.getPath());

        if (save && file.isDirty())
            flush(file);

        files.remove(path);

        var lock = locks.remove(path);
        if (lock != null)
            lock.close();

        // The record is dropped rather than merely disabled: a path that comes back is
        // a different file as far as anything here can tell, and a workspace that
        // accumulates a record per file ever served is a workspace nobody can read.
        registry.remove(file.getPath());

        PortfolioLog.info("Closed " + path);
    }

    /**
     * Writes one file, on the model thread, and marks it saved.
     * <p>
     * The mark is read <em>before</em> the write, so a change that lands during it
     * leaves the file dirty and the next sweep saves it again - the desktop's own
     * rule. Failures leave the file dirty on purpose, so the next sweep retries and
     * {@code /health} keeps reporting it unsaved for as long as it is.
     */
    public void flush(HeadlessHost.LoadedFile file)
    {
        try
        {
            var mark = file.saveMark();

            runOnModelThread(() -> {
                ClientFactory.save(file.getClient(), file.getFile());
                return null;
            });

            file.markSaved(mark);
        }
        catch (Exception e) // NOSONAR - a read-only volume must not kill the daemon
        {
            file.markSaveFailed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            PortfolioLog.error("Could not save " + file.getPath() + ": " + e.getMessage());
        }
    }

    /**
     * Inserts a file the loader could not have produced - one whose path is a
     * directory, or an in-memory client with no file behind it at all. Only the
     * autosave and reporting tests need this; it takes no lock and writes no registry
     * record, so nothing that addresses a file by id can see what it added.
     */
    /* testing */ synchronized void adopt(HeadlessHost.LoadedFile file)
    {
        files.put(Path.of(file.getPath()), file);
    }

    /** Releases every lock, for the shutdown path. The files themselves go with the process. */
    public synchronized void releaseLocks()
    {
        PortfolioFileLock.releaseAll(new ArrayList<>(locks.values()));
        locks.clear();
    }

    /**
     * A file on a share that cannot lock is served anyway, with a warning: refusing
     * would be worse than the risk, but the operator has to know the guarantee is not
     * in force for it. Per file, so one unlockable share does not stop the others
     * being claimed.
     */
    private PortfolioFileLock claim(Path path) throws IOException
    {
        try
        {
            return PortfolioFileLock.acquire(path, port);
        }
        catch (PortfolioFileLock.UnsupportedLockException e)
        {
            PortfolioLog.warning(e.getMessage() + " - serving it anyway, but nothing prevents"
                            + " a second instance from owning it.");
            return null;
        }
    }

    private <T> T runOnModelThread(java.util.concurrent.Callable<T> callable) throws IOException
    {
        var modelThread = host;
        if (modelThread == null)
            throw new IllegalStateException("no model thread attached to the file set");

        try
        {
            return modelThread.syncExec(callable);
        }
        catch (IOException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The file name without its extension, matching what the desktop shows. */
    private static String labelOf(File file)
    {
        var name = file.getName();
        var dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Wipes a password after use; the load has taken what it needs from it. */
    static void wipe(char[] password)
    {
        if (password != null)
            Arrays.fill(password, '\0');
    }
}
