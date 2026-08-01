package name.abuchen.portfolio.headless;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

        /**
         * Everything a client can observe: model edits and exchange-rate refreshes
         * alike, because both change what a response says.
         */
        private final AtomicLong changeCount = new AtomicLong();

        /**
         * The value {@link #changeCount} had at the last <em>model</em> change, and
         * at the last successful save. Dirtiness is the difference between the two
         * rather than a flag, which is what makes "a change arrived while the save
         * was running" resolve to still-dirty instead of to a lost edit.
         * <p>
         * An exchange-rate refresh moves the counter but not these, so it invalidates
         * every ETag without provoking a save of a file whose contents did not
         * change.
         */
        private final AtomicLong lastModelChange = new AtomicLong();
        private final AtomicLong lastSaved = new AtomicLong();

        private volatile Instant savedAt;
        private volatile String saveError;

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
            // ETag to be trustworthy whichever host served it. It is not a *model*
            // change, though - what was just read off disk is what is on disk.
            changeCount.incrementAndGet();

            client.addPropertyChangeListener(event -> lastModelChange.set(changeCount.incrementAndGet()));
        }

        @Override
        public String getPath()
        {
            return path;
        }

        public File getFile()
        {
            return new File(path);
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
            return lastModelChange.get() != lastSaved.get();
        }

        /**
         * The mark to pass to {@link #markSaved} afterwards. Read <em>before</em> the
         * file is written, so a change that lands during the write leaves the file
         * dirty and the next sweep saves it again - the desktop's own rule.
         */
        long saveMark()
        {
            return lastModelChange.get();
        }

        void markSaved(long mark)
        {
            lastSaved.set(mark);
            savedAt = Instant.now();
            saveError = null;
        }

        void markSaveFailed(String message)
        {
            saveError = message;
        }

        /**
         * New exchange rates change every converted figure in every cached response,
         * so the counter has to move even though nothing in the file did - otherwise
         * a client holding an ETag from before the refresh keeps being told 304 and
         * goes on showing the rates the daemon started with.
         * <p>
         * Also drops the factory's resolved-series cache: an update replaces the
         * provider's series objects wholesale, and a cached path still points at the
         * old ones. This is what {@code ClientInput#onExchangeRatesLoaded} does on
         * the desktop, minus the counter bump, which the desktop arguably owes too.
         */
        void onExchangeRatesUpdated()
        {
            factory.clearCache();
            changeCount.incrementAndGet();
        }

        Optional<Instant> getSavedAt()
        {
            return Optional.ofNullable(savedAt);
        }

        Optional<String> getSaveError()
        {
            return Optional.ofNullable(saveError);
        }
    }

    private final List<OpenFile> openFiles;
    private final ExecutorService modelThread;
    private final Thread owner;

    public HeadlessHost(List<? extends OpenFile> openFiles)
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
