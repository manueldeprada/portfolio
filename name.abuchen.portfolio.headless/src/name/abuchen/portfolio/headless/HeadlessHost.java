package name.abuchen.portfolio.headless;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.rest.spi.ApiAccessRequest;
import name.abuchen.portfolio.rest.spi.HostApplication;
import name.abuchen.portfolio.rest.spi.OpenFile;

/**
 * The daemon standing in for the desktop application.
 * <p>
 * The handlers are written against a host whose model access is serialized by a UI
 * thread. There is no UI thread here, but the guarantee still has to hold - the
 * HTTP server runs a pool of worker threads and the model is not thread-safe - so
 * {@link #syncExec} marshals onto a single dedicated thread instead. That keeps the
 * invariant {@code UIThreadMarshallingTest} enforces on the handlers true for this
 * host too, with no change to any handler.
 */
public class HeadlessHost implements HostApplication
{
    /**
     * One open portfolio file, loaded once at startup and served until shutdown.
     * <p>
     * A class rather than a record because it carries the change counter, and that
     * has to be mutable: every model change bumps it, which is what lets a response
     * carry an ETag without hashing the model.
     */
    public static final class LoadedFile implements OpenFile
    {
        private final String path;
        private final String label;
        private final Client client;
        private final ExchangeRateProviderFactory factory;
        private final AtomicLong changeCount = new AtomicLong();
        private volatile boolean dirty;

        public LoadedFile(String path, String label, Client client)
        {
            this.path = path;
            this.label = label;
            this.client = client;
            // The factory registers a listener on the client, so it is built once
            // per file and kept - never per request, as the OpenFile contract warns.
            this.factory = new ExchangeRateProviderFactory(client);

            // Loading the client is itself a change: a consumer holding a counter
            // from before must not conclude that nothing happened. Same reasoning as
            // ClientInput's, and the counters have to mean the same thing for an
            // ETag to be trustworthy whichever host served it.
            changeCount.incrementAndGet();

            client.addPropertyChangeListener(event -> {
                changeCount.incrementAndGet();
                // Nothing persists yet (autosave is phase 2), so any change is an
                // unsaved one. When autosave lands this clears after a successful
                // save, and only then.
                if (!"touch".equals(event.getPropertyName()))
                    dirty = true;
            });
        }

        @Override
        public String getPath()
        {
            return path;
        }

        @Override
        public String getLabel()
        {
            return label;
        }

        @Override
        public Client getClient()
        {
            return client;
        }

        @Override
        public ExchangeRateProviderFactory getExchangeRateProviderFactory()
        {
            return factory;
        }

        @Override
        public long getChangeCount()
        {
            return changeCount.get();
        }

        @Override
        public boolean isDirty()
        {
            return dirty;
        }
    }

    private final List<OpenFile> openFiles;
    private final ExecutorService modelThread;
    private final Thread owner;

    public HeadlessHost(List<OpenFile> openFiles)
    {
        this.openFiles = List.copyOf(openFiles);

        // Captured so syncExec can tell "already on the model thread" from "called
        // from a worker" - see the reentrancy note there.
        var ownerHolder = new Thread[1];
        this.modelThread = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "pp-headless-model");
            thread.setDaemon(true);
            ownerHolder[0] = thread;
            return thread;
        });

        try
        {
            modelThread.submit(() -> null).get();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting the model thread", e);
        }
        catch (ExecutionException e)
        {
            throw new IllegalStateException("could not start the model thread", e);
        }

        this.owner = ownerHolder[0];
    }

    @Override
    public List<OpenFile> listOpenFiles()
    {
        return openFiles;
    }

    /**
     * Runs on the single model thread, so two requests never touch the model at
     * once.
     * <p>
     * Reentrant on purpose: a nested call from the model thread runs inline rather
     * than queueing behind itself, which would deadlock. No handler nests today, but
     * a single-threaded executor turns that mistake into a hung request rather than
     * an exception, so it is worth not being able to make.
     */
    @Override
    public <T> T syncExec(Callable<T> callable) throws Exception
    {
        if (Thread.currentThread() == owner)
            return callable.call();

        try
        {
            return modelThread.submit(callable).get();
        }
        catch (ExecutionException e)
        {
            // Unwrap, or every ApiException would reach the server as a 500.
            if (e.getCause() instanceof Exception cause)
                throw cause;
            if (e.getCause() instanceof Error error)
                throw error;
            throw e;
        }
    }

    /**
     * Never. There is no user and no dialog, so no request is ever answered 423 -
     * the write path's retry behaviour simply does not arise here.
     */
    @Override
    public boolean isUserEditing()
    {
        return false;
    }

    /**
     * Declined, always. Interactive pairing needs someone to ask; headless clients
     * are provisioned a token from the configuration instead. Declining rather than
     * ignoring means a client that tries the pairing flow is told so immediately,
     * instead of polling until the request expires.
     */
    @Override
    public void requestApiAccessApproval(ApiAccessRequest request)
    {
        request.decline();
    }

    public void shutdown()
    {
        modelThread.shutdown();
    }
}
