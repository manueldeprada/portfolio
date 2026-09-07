package name.abuchen.portfolio.headless;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.ClientFileType;
import name.abuchen.portfolio.model.Security;
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
    /**
     * The file is encrypted and no password came with the request. Typed, because the
     * caller has to be able to answer it by asking a human - a message from the model's
     * loader is localized and cannot be switched on.
     */
    public static class PasswordRequiredException extends IOException
    {
        private static final long serialVersionUID = 1L;

        public PasswordRequiredException(Path path)
        {
            super(path + " is encrypted and needs a password");
        }
    }

    /** The password did not decrypt the file. Also typed, and for the same reason. */
    public static class PasswordRejectedException extends IOException
    {
        private static final long serialVersionUID = 1L;

        public PasswordRejectedException(Path path, String detail)
        {
            super(path + " could not be read with that password: " + detail);
        }
    }

    /**
     * A security the caller asked for cannot be built. Deliberately not an
     * {@link IOException}: everything the model thread can fail with arrives as one, and
     * a caller told its request was invalid would retry a body that is perfectly fine.
     */
    public static class InvalidSecurityException extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;

        public InvalidSecurityException(String message)
        {
            super(message);
        }
    }

    /** What was done to a file, for the caller's log line. */
    public enum Outcome
    {
        OPENED, ALREADY_OPEN
    }

    public record Opened(HeadlessHost.LoadedFile file, String id, Outcome outcome)
    {
    }

    public record SecuritySpec(String name, String ticker, String currency, String feed)
    {
    }

    public record SecuritiesAdded(int added, int total)
    {
    }

    /**
     * The format a file this daemon creates is written in: {@code .portfolio},
     * protobuf, compressed - the desktop's own default for a new file, so a user who
     * later opens it in the application sees nothing unusual.
     */
    private static final ClientFileType CREATE_FORMAT = ClientFileType.BINARY;

    /**
     * And the format a copy of an encrypted file is written in - the strongest of the
     * two the desktop offers, since the alternative is only there for files that have
     * to be read by an older build.
     */
    private static final ClientFileType ENCRYPTED_FORMAT = ClientFileType.BINARY_AES256;

    private final FileAccessRegistry registry;
    private final int port;
    private final boolean backupOnOpen;

    /** Insertion-ordered, so /health and the control API list files predictably. */
    private final Map<Path, HeadlessHost.LoadedFile> files = new LinkedHashMap<>();
    private final Map<Path, PortfolioFileLock> locks = new LinkedHashMap<>();
    private final Set<Path> manualQuoteRefresh = new LinkedHashSet<>();

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

    /** Files participating in the daemon's ordinary scheduled and user-requested cycles. */
    public synchronized List<HeadlessHost.LoadedFile> listAutomaticQuoteRefresh()
    {
        return files.entrySet().stream().filter(entry -> !manualQuoteRefresh.contains(entry.getKey()))
                        .map(Map.Entry::getValue).toList();
    }

    public synchronized void setAutomaticQuoteRefresh(String id, boolean automatic) throws IOException
    {
        var file = byId(id).orElseThrow(() -> new NoSuchFileException("No open file with id " + id));
        var path = Path.of(file.getPath());
        if (automatic)
            manualQuoteRefresh.remove(path);
        else
            manualQuoteRefresh.add(path);
    }

    /**
     * Adds the securities whose ticker symbol the file does not have yet.
     * <p>
     * An additive merge, whatever the verb on the wire suggests: an entry naming a
     * ticker already on record is skipped rather than rewritten, and a security the
     * caller no longer lists stays where it is. Nothing here removes or replaces
     * anything, which is what lets a caller re-send its whole list without losing the
     * quote history the file has accumulated.
     * <p>
     * <strong>Every entry is validated before any of them is added</strong>, so a
     * refused request leaves the file exactly as it was - the all-or-nothing the REST
     * API's own writes promise. Validating inside the loop would half-apply a list
     * whose last entry is wrong, and mark the file dirty on top of it.
     */
    public SecuritiesAdded addMissingSecurities(String id, List<SecuritySpec> requested) throws IOException
    {
        var file = byId(id).orElseThrow(() -> new NoSuchFileException("No open file with id " + id));

        for (var entry : requested)
            validate(entry);

        return runOnModelThread(() -> {
            var client = file.getClient();
            var present = new LinkedHashSet<String>();
            for (var security : client.getSecurities())
                if (security.getTickerSymbol() != null)
                    present.add(security.getTickerSymbol());

            var added = 0;
            for (var entry : requested)
            {
                if (!present.add(entry.ticker()))
                    continue;

                var security = new Security(entry.name(), entry.currency());
                security.setTickerSymbol(entry.ticker());
                security.setFeed(entry.feed());

                // The protobuf writer reads updatedAt unconditionally, so a security
                // with none makes the next save of the file it is in fail.
                security.setUpdatedAt(Instant.now());

                client.addSecurity(security);
                added++;
            }
            return new SecuritiesAdded(added, client.getSecurities().size());
        });
    }

    private static void validate(SecuritySpec entry)
    {
        if (entry.name() == null || entry.name().isBlank() || entry.ticker() == null || entry.ticker().isBlank()
                        || entry.feed() == null || entry.feed().isBlank() || entry.currency() == null
                        || CurrencyUnit.getInstance(entry.currency()) == null)
            throw new InvalidSecurityException("every security needs a name, ticker, feed and known currency");
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

        // Asked before the file is claimed, because "this needs a password" is not a
        // failure the caller should have to distinguish from a corrupt file: the loader
        // reports both as an IOException with a localized message.
        var encrypted = ClientFactory.isEncrypted(file);
        if (encrypted && password == null)
            throw new PasswordRequiredException(absolute);

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

            // For an encrypted file a wrong password is the overwhelmingly likely
            // cause, and the one the caller can do something about.
            if (encrypted)
                throw new PasswordRejectedException(absolute, e.getMessage());
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
     * @param password
     *            for an encrypted source, the password to write the copy with - which is
     *            also the password it is then opened with. Required, because the
     *            source's own is not kept after the load (see
     *            {@link HeadlessApplication}) and writing the copy unencrypted would
     *            drop a file's protection on the user's behalf. Ignored for a source
     *            that is not encrypted: a copy is a copy, not a chance to add
     *            encryption where the original has none.
     */
    public Opened copy(String sourceId, Path target, char[] password) throws IOException
    {
        HeadlessHost.LoadedFile source;
        Path absolute;
        boolean encrypted;

        synchronized (this)
        {
            source = byId(sourceId).orElseThrow(() -> new NoSuchFileException("No open file with id " + sourceId));
            absolute = target.toAbsolutePath().normalize();

            if (Files.exists(absolute))
                throw new FileAlreadyExistsException(absolute.toString());

            // Read off the file rather than the client: the model's save flags are
            // package-private, and the file on disk is the same answer.
            encrypted = ClientFactory.isEncrypted(source.getFile());
            if (encrypted && password == null)
                throw new PasswordRequiredException(Path.of(source.getPath()));

            Files.createDirectories(absolute.getParent());
        }

        var flags = encrypted ? ENCRYPTED_FORMAT.getFlags() : CREATE_FORMAT.getFlags();
        var secret = encrypted ? password : null;

        // exportAs, not saveAs: saveAs adopts the flags it wrote onto the *source*
        // client, so copying a compressed XML file would quietly change the format the
        // original is saved in from then on.
        runOnModelThread(() -> {
            ClientFactory.exportAs(source.getClient(), absolute.toFile(), secret, flags);
            return null;
        });

        try
        {
            return open(absolute, encrypted ? password : null);
        }
        catch (IOException | RuntimeException e)
        {
            // A copy nothing can open is worse than no copy: it would sit in the
            // caller's directory as a file it has to explain.
            Files.deleteIfExists(absolute);
            throw e;
        }
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
        manualQuoteRefresh.remove(path);

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
