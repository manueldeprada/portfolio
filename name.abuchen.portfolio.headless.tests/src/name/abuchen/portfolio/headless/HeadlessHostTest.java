package name.abuchen.portfolio.headless;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.rest.spi.ApiAccessRequest;
import name.abuchen.portfolio.rest.spi.OpenFile;

@SuppressWarnings("nls")
public class HeadlessHostTest
{
    private static HeadlessHost host()
    {
        return new HeadlessHost(List.<OpenFile> of(new HeadlessHost.LoadedFile("/tmp/a.xml", "a", new Client())));
    }

    @Test
    public void testTheConfiguredFilesAreReported() throws Exception
    {
        var host = host();
        try
        {
            assertThat(host.listOpenFiles().size(), is(1));
            assertThat(host.listOpenFiles().get(0).getPath(), is("/tmp/a.xml"));
            assertThat(host.listOpenFiles().get(0).getLabel(), is("a"));
        }
        finally
        {
            host.shutdown();
        }
    }

    @Test
    public void testSyncExecReturnsTheCallablesValue() throws Exception
    {
        var host = host();
        try
        {
            assertThat(host.syncExec(() -> 42), is(42));
        }
        finally
        {
            host.shutdown();
        }
    }

    /**
     * The whole point of the single model thread: the handlers assume the
     * serialization the desktop's UI thread gives them, and the HTTP server calls
     * them from a pool. Without it two requests interleave on a model that is not
     * thread-safe.
     */
    @Test
    public void testConcurrentCallsAreSerialized() throws Exception
    {
        var host = host();
        var workers = Executors.newFixedThreadPool(8);
        try
        {
            var inside = new AtomicInteger();
            var maxObserved = new AtomicInteger();
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(50);

            for (var i = 0; i < 50; i++)
            {
                workers.submit(() -> {
                    start.await();
                    host.syncExec(() -> {
                        var now = inside.incrementAndGet();
                        maxObserved.accumulateAndGet(now, Math::max);
                        Thread.sleep(1);
                        inside.decrementAndGet();
                        return null;
                    });
                    done.countDown();
                    return null;
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS), is(true));
            assertThat(maxObserved.get(), is(lessThanOrEqualTo(1)));
        }
        finally
        {
            workers.shutdownNow();
            host.shutdown();
        }
    }

    @Test
    public void testEveryCallRunsOnTheSameThread() throws Exception
    {
        var host = host();
        var workers = Executors.newFixedThreadPool(4);
        try
        {
            var threads = Collections.synchronizedList(new ArrayList<String>());
            var futures = new ArrayList<java.util.concurrent.Future<?>>();

            for (var i = 0; i < 20; i++)
                futures.add(workers.submit(() -> host.syncExec(() -> threads.add(Thread.currentThread().getName()))));

            for (var future : futures)
                future.get(30, TimeUnit.SECONDS);

            assertThat(Set.copyOf(threads).size(), is(1));
        }
        finally
        {
            workers.shutdownNow();
            host.shutdown();
        }
    }

    /**
     * A nested call must run inline. Queueing it behind the call already occupying
     * the single thread would deadlock the request instead of throwing, which is the
     * kind of failure that only shows up under load.
     */
    @Test(timeout = 10000)
    public void testANestedCallDoesNotDeadlock() throws Exception
    {
        var host = host();
        try
        {
            assertThat(host.syncExec(() -> host.syncExec(() -> "inner")), is("inner"));
        }
        finally
        {
            host.shutdown();
        }
    }

    /**
     * Unwrapped, or the ApiException a handler throws to mean "404" would reach the
     * server wrapped in an ExecutionException and be answered 500.
     */
    @Test
    public void testTheCallablesExceptionIsRethrownAsItself() throws Exception
    {
        var host = host();
        try
        {
            host.syncExec(() -> {
                throw new IllegalStateException("boom");
            });
            Assert.fail("expected the original exception");
        }
        catch (IllegalStateException e)
        {
            assertThat(e.getMessage(), is("boom"));
        }
        finally
        {
            host.shutdown();
        }
    }

    /** There is no user, so no request is ever answered 423. */
    @Test
    public void testTheUserIsNeverEditing()
    {
        var host = host();
        try
        {
            assertThat(host.isUserEditing(), is(false));
        }
        finally
        {
            host.shutdown();
        }
    }

    /**
     * Declined rather than ignored: a client attempting interactive pairing is told
     * immediately instead of polling a request that can never be approved.
     */
    @Test
    public void testPairingIsDeclinedRatherThanLeftPending()
    {
        var host = host();
        try
        {
            var decisions = new ArrayList<String>();
            host.requestApiAccessApproval(new ApiAccessRequest()
            {
                @Override
                public String getClientName()
                {
                    return "someone";
                }

                @Override
                public Instant getExpiresAt()
                {
                    return Instant.now();
                }

                @Override
                public void allowForSession()
                {
                    decisions.add("session");
                }

                @Override
                public void allowAlways()
                {
                    decisions.add("always");
                }

                @Override
                public void decline()
                {
                    decisions.add("decline");
                }
            });

            assertThat(decisions, is(List.of("decline")));
        }
        finally
        {
            host.shutdown();
        }
    }

    /**
     * The OpenFile contract warns the factory must be owned per file, never built
     * per request - it registers a listener on the client.
     */
    @Test
    public void testTheExchangeRateFactoryIsTheSameInstanceEveryTime()
    {
        var file = new HeadlessHost.LoadedFile("/tmp/a.xml", "a", new Client());

        assertThat(file.getExchangeRateProviderFactory(), is(file.getExchangeRateProviderFactory()));
    }
}
