package name.abuchen.portfolio.headless;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An exclusive claim on one portfolio file, held for as long as the process runs.
 * <p>
 * Two instances must not own the same file: each holds its own in-memory copy, and
 * whichever saves last silently discards the other's work. Nothing in the
 * application enforces that today - the save path's lock is momentary and skipped
 * on macOS entirely, and the Eclipse workspace lock only stops two *application*
 * instances, not two instances pointed at one file.
 * <p>
 * The claim is an OS lock on a sidecar {@code <file>.lock}, not a PID file, which
 * means it cannot go stale: if the holder dies, or is killed, the kernel releases
 * the lock. The file's contents are only there so the refusal can name who holds it.
 * <p>
 * <strong>Limit worth knowing.</strong> An advisory lock only excludes processes
 * that also take it. This excludes another daemon completely; it does not yet see a
 * desktop application holding the same file, because the desktop does not take this
 * lock. Closing that gap is a small change on the desktop side (claim the same
 * sidecar when a {@code ClientInput} opens a file) and is the honest next step.
 */
public final class PortfolioFileLock implements AutoCloseable
{
    private final Path lockFile;
    private final FileChannel channel;
    private final java.nio.channels.FileLock lock;

    private PortfolioFileLock(Path lockFile, FileChannel channel, java.nio.channels.FileLock lock)
    {
        this.lockFile = lockFile;
        this.channel = channel;
        this.lock = lock;
    }

    public static Path lockFileFor(Path portfolioFile)
    {
        return portfolioFile.resolveSibling(portfolioFile.getFileName() + ".lock");
    }

    /**
     * @throws FileInUseException
     *             if another process already holds this file
     */
    public static PortfolioFileLock acquire(Path portfolioFile, int port) throws IOException
    {
        var lockFile = lockFileFor(portfolioFile);

        var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        java.nio.channels.FileLock lock = null;
        try
        {
            lock = channel.tryLock();
        }
        catch (OverlappingFileLockException e)
        {
            // Same JVM, so this is a configuration mistake: the file is listed twice.
            close(channel);
            throw new FileInUseException(portfolioFile, "it is listed more than once in the configuration");
        }
        catch (IOException e)
        {
            // Locks are unsupported on some network shares. Refusing to start there
            // would be worse than the risk, so this degrades to a warning: the
            // caller still gets a lock object, it just guarantees nothing.
            close(channel);
            throw new UnsupportedLockException(portfolioFile, e);
        }

        if (lock == null)
        {
            var holder = read(lockFile);
            close(channel);
            throw new FileInUseException(portfolioFile,
                            holder.isEmpty() ? "another process holds it" : "held by " + holder);
        }

        write(channel, port);
        return new PortfolioFileLock(lockFile, channel, lock);
    }

    private static void write(FileChannel channel, int port)
    {
        try
        {
            var text = "pid=" + ProcessHandle.current().pid() + " port=" + port + " since=" + Instant.now();
            channel.truncate(0);
            channel.write(java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            channel.force(false);
        }
        catch (IOException e)
        {
            // Only the diagnostic text; the lock itself is already held.
        }
    }

    private static String read(Path lockFile)
    {
        try
        {
            return new String(java.nio.file.Files.readAllBytes(lockFile), StandardCharsets.UTF_8).trim();
        }
        catch (IOException e)
        {
            return "";
        }
    }

    private static void close(FileChannel channel)
    {
        try
        {
            channel.close();
        }
        catch (IOException e)
        {
            // nothing useful to do while already failing
        }
    }

    public Path lockFile()
    {
        return lockFile;
    }

    @Override
    public void close()
    {
        try
        {
            if (lock.isValid())
                lock.release();
        }
        catch (IOException e)
        {
            // the process is going away; the kernel releases it regardless
        }
        close(channel);
    }

    /** Releases every lock in the list, in reverse order. */
    public static void releaseAll(List<PortfolioFileLock> locks)
    {
        var reversed = new ArrayList<>(locks);
        java.util.Collections.reverse(reversed);
        reversed.forEach(PortfolioFileLock::close);
    }

    /** Another process owns this portfolio file. */
    public static class FileInUseException extends IOException
    {
        private static final long serialVersionUID = 1L;

        public FileInUseException(Path portfolioFile, String detail)
        {
            super(portfolioFile + " is already in use: " + detail
                            + ". Two instances must not own one portfolio file - the last one to save wins.");
        }
    }

    /** The filesystem does not support locking; the caller decides what to do. */
    public static class UnsupportedLockException extends IOException
    {
        private static final long serialVersionUID = 1L;

        public UnsupportedLockException(Path portfolioFile, IOException cause)
        {
            super("Could not lock " + portfolioFile + ": " + cause.getMessage(), cause);
        }
    }
}
