package name.abuchen.portfolio.rest.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.rest.spi.ApiAccessRequest;
import name.abuchen.portfolio.rest.spi.HostApplication;
import name.abuchen.portfolio.rest.spi.OpenFile;

public class FakeHost implements HostApplication
{
    public static final class FakeOpenFile implements OpenFile
    {
        private final String path;
        private final String label;
        private final Client client;
        private final ExchangeRateProviderFactory factory;

        private final AtomicLong changeCount = new AtomicLong();
        private final AtomicInteger changeCountReads = new AtomicInteger();
        private final AtomicInteger pendingBumps = new AtomicInteger();
        private boolean dirty = false;

        public FakeOpenFile(String path, String label, Client client, ExchangeRateProviderFactory factory)
        {
            this.path = path;
            this.label = label;
            this.client = client;
            this.factory = factory;
        }

        public FakeOpenFile(String path, String label, Client client)
        {
            this(path, label, client, new ExchangeRateProviderFactory(client));
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
            changeCountReads.incrementAndGet();

            var value = changeCount.get();
            if (pendingBumps.get() > 0)
            {
                pendingBumps.decrementAndGet();
                changeCount.incrementAndGet();
            }
            return value;
        }

        @Override
        public boolean isDirty()
        {
            return dirty;
        }

        public void setDirty(boolean dirty)
        {
            this.dirty = dirty;
        }

        public long bumpChangeCount()
        {
            return changeCount.incrementAndGet();
        }

        /**
         * Makes each of the next {@code times} reads of the change count leave
         * behind a higher value: the user editing the file between two samples
         * of the counter, which no test can otherwise time reliably.
         */
        public void bumpChangeCountAfterNextReads(int times)
        {
            pendingBumps.set(times);
        }

        /** how often the change count was read, i.e. how often a route sampled it */
        public int changeCountReads()
        {
            return changeCountReads.get();
        }
    }

    private final List<OpenFile> openFiles;
    private boolean userEditing = false;
    private ApiAccessRequest lastAccessRequest;

    private int syncExecDepth = 0;
    private boolean accessedOutsideUIThread = false;
    private final List<Object> syncExecResults = new ArrayList<>();

    public FakeHost(List<OpenFile> openFiles)
    {
        this.openFiles = openFiles;
    }

    public void setUserEditing(boolean userEditing)
    {
        this.userEditing = userEditing;
    }

    /**
     * Whether the open files were read without going through
     * {@link #syncExec(Callable)}, i.e. off the UI thread in the real
     * application.
     */
    public boolean hasAccessedOutsideUIThread()
    {
        return accessedOutsideUIThread;
    }

    @Override
    public List<OpenFile> listOpenFiles()
    {
        if (syncExecDepth == 0)
            accessedOutsideUIThread = true;

        return openFiles;
    }

    /**
     * The values the syncExec callables returned - lets a test verify what was
     * (and was not) computed on the UI thread.
     */
    public List<Object> syncExecResults()
    {
        return syncExecResults;
    }

    @Override
    public <T> T syncExec(Callable<T> callable) throws Exception
    {
        syncExecDepth++;

        try
        {
            T result = callable.call();
            syncExecResults.add(result);
            return result;
        }
        finally
        {
            syncExecDepth--;
        }
    }

    @Override
    public boolean isUserEditing()
    {
        return userEditing;
    }

    @Override
    public void requestApiAccessApproval(ApiAccessRequest request)
    {
        this.lastAccessRequest = request;
    }

    /** the most recent access request the service asked the user about */
    public ApiAccessRequest lastAccessRequest()
    {
        return lastAccessRequest;
    }
}
