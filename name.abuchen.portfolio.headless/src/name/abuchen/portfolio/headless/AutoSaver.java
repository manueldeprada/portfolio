package name.abuchen.portfolio.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.model.ClientFactory;

/**
 * Persists what the API writes.
 * <p>
 * The desktop's rule is "only the user saves", and it can afford that because there
 * is a user watching an unsaved-changes marker. Headless there is nobody, so a write
 * that is not persisted is simply lost when the process exits - which is what phase 1
 * did. This sweeps the open files on a fixed interval and saves the dirty ones.
 * <p>
 * A sweep rather than a timer armed on each change: a quote refresh fires thousands
 * of property changes in a few seconds, and rearming per change would either save
 * constantly or (with a quiet period) never save at all while the refresh runs. The
 * interval is therefore also the bound on how much work is at risk.
 * <p>
 * Saving happens <strong>on the model thread</strong>, which blocks API requests for
 * its duration. That is deliberate and matches the desktop, which saves on the UI
 * thread: a serializer walking a model another thread is editing produces a file that
 * is subtly wrong, and there is no cheaper way to prevent it than to hold the lock
 * the handlers already contend for.
 * <p>
 * Encrypted files need nothing extra. {@code ClientFactory.load} stores the derived
 * key on the {@code Client}, so {@code ClientFactory.save} re-encrypts with it and
 * the password does not have to be kept anywhere after startup.
 */
public class AutoSaver
{
    private final HeadlessHost host;
    private final List<HeadlessHost.LoadedFile> files;
    private final ScheduledExecutorService scheduler;

    public AutoSaver(HeadlessHost host, List<HeadlessHost.LoadedFile> files)
    {
        this.host = host;
        this.files = List.copyOf(files);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "pp-headless-autosave"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start(Duration interval)
    {
        scheduler.scheduleWithFixedDelay(this::sweep, interval.toMillis(), interval.toMillis(),
                        TimeUnit.MILLISECONDS);
    }

    /**
     * Stops sweeping and saves whatever is still dirty. Without this the last interval
     * of work is lost on every shutdown, including the clean one.
     */
    public void shutdown()
    {
        scheduler.shutdown();

        try
        {
            // Wait for a sweep that is already writing rather than racing it, but do
            // not let a hung save hold the daemon open for ever.
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS))
                scheduler.shutdownNow();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        sweep();
    }

    private void sweep()
    {
        for (var file : files)
        {
            if (file.isDirty())
                save(file);
        }
    }

    private void save(HeadlessHost.LoadedFile file)
    {
        try
        {
            // Read before writing: a change that lands while the file is being written
            // is not covered by what was written, so the mark must predate the write
            // and the file stays dirty for the next sweep.
            var mark = file.saveMark();

            host.syncExec(() -> {
                ClientFactory.save(file.getClient(), file.getFile());
                return null;
            });

            file.markSaved(mark);
        }
        catch (Exception e) // NOSONAR - a read-only volume must not kill the daemon
        {
            // Left dirty on purpose, so the next sweep tries again and /health keeps
            // reporting the file as unsaved for as long as it is.
            file.markSaveFailed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            PortfolioLog.error("Could not save " + file.getPath() + ": " + e.getMessage());
        }
    }

    /**
     * One copy of each file as it was on disk before the daemon touched it, named the
     * way the desktop names its own: {@code <name>.backup-after-open.<ext>}.
     * <p>
     * The desktop offers a backup before every save; here that would rewrite a copy of
     * the whole file every interval for as long as the daemon runs. One copy taken
     * before the first write covers the case that actually matters - a daemon
     * misconfigured onto the wrong file, or a bad write - and costs one copy.
     */
    public static void backup(List<HeadlessHost.LoadedFile> files)
    {
        for (var file : files)
        {
            var source = file.getFile().toPath();

            try
            {
                Files.copy(source, source.resolveSibling(backupName(source)), StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException e)
            {
                PortfolioLog.warning("Could not back up " + source + " before serving it: " + e.getMessage());
            }
        }
    }

    /** Keeps the extension, so the backup can be opened like any other file. */
    private static String backupName(Path source)
    {
        var name = source.getFileName().toString();
        var dot = name.lastIndexOf('.');

        return dot > 0 ? name.substring(0, dot) + ".backup-after-open" + name.substring(dot) //$NON-NLS-1$
                        : name + ".backup-after-open"; //$NON-NLS-1$
    }
}
