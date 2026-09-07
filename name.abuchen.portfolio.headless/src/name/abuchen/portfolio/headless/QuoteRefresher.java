package name.abuchen.portfolio.headless;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.SecurityProperty;
import name.abuchen.portfolio.online.AuthenticationExpiredException;
import name.abuchen.portfolio.online.Factory;
import name.abuchen.portfolio.online.FeedConfigurationException;
import name.abuchen.portfolio.online.QuoteFeed;
import name.abuchen.portfolio.online.QuoteFeed.HistoricalUpdatePolicy;
import name.abuchen.portfolio.online.QuoteFeedData;
import name.abuchen.portfolio.online.RateLimitExceededException;

/**
 * Keeps the quotes current, the way the desktop's {@code UpdatePricesJob} does.
 * <p>
 * That job cannot be reused: it is an Eclipse {@code Job} in the UI bundle and
 * imports SWT to report progress. What is reused is everything that matters - the
 * feeds themselves, their grouping criterion, their update policies - so the prices
 * this writes are the prices the desktop would have written.
 * <p>
 * Two differences from the desktop, both deliberate:
 * <ul>
 * <li><strong>Results are applied on the model thread.</strong> The desktop mutates
 * securities straight from a background job; here an HTTP worker may be halfway
 * through a performance calculation on the same model, so the fetch happens off the
 * model thread and only the write is marshalled onto it.</li>
 * <li><strong>Nothing prompts for authentication.</strong> A feed that needs a
 * signed-in account simply returns nothing headless, and says so once per cycle
 * rather than once per instrument.</li>
 * </ul>
 */
public class QuoteRefresher implements ControlServer.QuoteControl
{
    /**
     * One instrument's update against one feed. Split by what it fetches, matching
     * the desktop's historical/latest split, because the two have different feeds,
     * different tickers and different merge rules.
     */
    private abstract static class Task
    {
        /**
         * The write half of a task, run on the model thread. Reports whether it
         * actually changed anything, which is what decides whether the file becomes
         * dirty: a cycle that downloads the same prices again must not provoke a save,
         * or an untouched file would be rewritten every hour for ever.
         */
        interface Apply
        {
            boolean apply();
        }

        protected final QuoteFeed feed;
        protected final Security security;

        protected Task(QuoteFeed feed, Security security)
        {
            this.feed = feed;
            this.security = security;
        }

        /** Fetches off the model thread; returns what to apply on it. */
        abstract Apply fetch() throws Exception;
    }

    private static final class HistoricalTask extends Task
    {
        /** Set only for a bounded first fill; null asks the feed for its own default. */
        private final LocalDate initialHistoryStart;

        HistoricalTask(QuoteFeed feed, Security security, LocalDate initialHistoryStart)
        {
            super(feed, security);
            this.initialHistoryStart = initialHistoryStart;
        }

        @Override
        Apply fetch() throws Exception
        {
            // A security with no prices at all gets a bounded first fill rather than
            // the feed's whole-history default, which for an empty security is thirty
            // years of daily closes per instrument.
            //
            // The mechanism is a sentinel price seeded onto a deep copy used only for
            // the request, never onto the model: it is the oldest price a security
            // carries that YahooFinanceQuoteFeed's range selection reads to pick its
            // bounded (about two-year) bucket. If that bucketing ever changes, this
            // silently becomes a whole-history fetch again, so the coupling is worth
            // re-reading whenever the feed is updated.
            var fetchSecurity = security;
            if (initialHistoryStart != null && security.getPrices().isEmpty())
            {
                fetchSecurity = security.deepCopy();
                fetchSecurity.addPrice(new SecurityPrice(initialHistoryStart, 1L));
            }

            var data = feed.getHistoricalQuotes(fetchSecurity, false);
            if (!data.getErrors().isEmpty())
            {
                // A response carrying errors and no prices at all is a failed update,
                // not an empty one - thrown rather than logged, which is what puts the
                // instrument on the cycle's failure list.
                if (data.getPrices().isEmpty())
                    throw data.getErrors().get(0);

                PortfolioLog.abbreviated(data.getErrors());
            }

            var policy = feed.getHistoricalUpdatePolicy(security);
            var identity = policy == HistoricalUpdatePolicy.REPLACE_IF_SOURCE_CHANGED
                            ? feed.getHistoricalDataIdentity(security)
                            : Optional.<String> empty();

            return () -> apply(data, policy, identity);
        }

        private boolean apply(QuoteFeedData data, HistoricalUpdatePolicy policy, Optional<String> identity)
        {
            if (policy == HistoricalUpdatePolicy.REPLACE)
                return replace(data, null);

            if (policy == HistoricalUpdatePolicy.REPLACE_IF_SOURCE_CHANGED && identity.isPresent())
            {
                var stored = security.getPropertyValue(SecurityProperty.Type.FEED, QuoteFeed.HISTORICAL_DATA_IDENTITY)
                                .orElse(null);

                if (!identity.get().equals(stored))
                    return replace(data, identity.get());
            }

            return security.addAllPrices(data.getPrices());
        }

        private boolean replace(QuoteFeedData data, String identity)
        {
            // Never wipe a history over a failed download: an empty response and a
            // security that genuinely has no prices look the same from here.
            if (!data.getErrors().isEmpty() || data.getPrices().isEmpty())
                return false;

            var hadPrices = !security.getPrices().isEmpty();
            security.removeAllPrices();

            var modified = security.addAllPrices(data.getPrices()) || hadPrices;
            if (security.setPropertyValue(SecurityProperty.Type.FEED, QuoteFeed.HISTORICAL_DATA_IDENTITY, identity))
                modified = true;

            return modified;
        }
    }

    private static final class LatestTask extends Task
    {
        private final Security fetchSecurity;

        LatestTask(QuoteFeed feed, Security security)
        {
            super(feed, security);

            var latestTicker = security.getPropertyValue(SecurityProperty.Type.FEED, QuoteFeed.TICKER_SYMBOL_LATEST);
            if (latestTicker.isPresent())
            {
                this.fetchSecurity = security.deepCopy();
                this.fetchSecurity.setTickerSymbol(latestTicker.get());
            }
            else
            {
                this.fetchSecurity = security;
            }
        }

        @Override
        Apply fetch() throws Exception
        {
            var latest = feed.getLatestQuote(fetchSecurity);
            return latest.<Apply> map(price -> () -> security.setLatest(price)).orElse(() -> false);
        }
    }

    private final HeadlessHost host;

    /** Read on each cycle: a file opened after startup needs its quotes too. */
    private final FileSet files;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService fetchers;

    private volatile Instant lastUpdate;
    private volatile String lastError;
    private volatile boolean running;
    private volatile List<Failure> lastFailures = List.of();

    /** True while a user-requested cycle waits for the scheduler thread. */
    private final AtomicInteger requested = new AtomicInteger();

    /**
     * How far back a security with an empty price series is filled the first time. A
     * range of this length falls into Yahoo's two-year bucket, which is the bound; the
     * exact number only has to sit inside that bucket rather than mean anything itself.
     */
    private static final int INITIAL_HISTORY_RANGE_DAYS = 430;

    public QuoteRefresher(HeadlessHost host, FileSet files)
    {
        this.host = host;
        this.files = files;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "pp-headless-quotes")); //$NON-NLS-1$

        // One thread per grouping criterion, capped: the criterion exists so that
        // requests to one host stay serialized while different hosts run in
        // parallel, and an unbounded pool would open a connection per instrument.
        this.fetchers = Executors.newFixedThreadPool(4, runnable -> daemon(runnable, "pp-headless-quote-fetch")); //$NON-NLS-1$
    }

    private static Thread daemon(Runnable runnable, String name)
    {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Runs a first update straight away and then every {@code interval}. Unlike the
     * exchange rates this does not block startup: stale quotes are the state the file
     * was saved in, which is what the desktop shows before its own update finishes.
     */
    public void start(Duration interval)
    {
        scheduler.scheduleWithFixedDelay(this::refreshAutomatic, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void shutdown()
    {
        scheduler.shutdownNow();
        fetchers.shutdownNow();

        // A deployment sends SIGTERM while a large first-history cycle may be in
        // flight. Wait for the interrupted scheduler task to reach refresh(file)'s
        // finally block and mark every already-applied quote dirty; AutoSaver runs
        // after this component and then flushes that durable progress.
        try
        {
            scheduler.awaitTermination(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Optional<Instant> getLastUpdate()
    {
        return Optional.ofNullable(lastUpdate);
    }

    @Override
    public Optional<String> getLastError()
    {
        return Optional.ofNullable(lastError);
    }

    @Override
    public boolean isRunning()
    {
        return requested.get() > 0 || running;
    }

    @Override
    public List<Failure> getLastFailures()
    {
        return lastFailures;
    }

    /**
     * Queues one cycle on the scheduler thread, which is what serializes it against
     * the scheduled ones. Targeted requests are retained independently because a
     * missing-history fill and a last-price update are not interchangeable.
     */
    @Override
    public void requestRefresh()
    {
        queue(this::refreshAutomatic);
    }

    @Override
    public void requestRefresh(String fileId, ControlServer.QuoteControl.Mode mode)
    {
        queue(() -> files.byId(fileId).ifPresent(file -> refresh(List.of(file), mode)));
    }

    private void queue(Runnable refresh)
    {
        requested.incrementAndGet();
        try
        {
            scheduler.execute(() -> {
                requested.decrementAndGet();
                refresh.run();
            });
        }
        catch (RejectedExecutionException e)
        {
            // Shutting down; the request has no cycle to run in.
            requested.decrementAndGet();
        }
    }

    private void refreshAutomatic()
    {
        refresh(files.listAutomaticQuoteRefresh(), ControlServer.QuoteControl.Mode.FULL);
    }

    private void refresh(List<HeadlessHost.LoadedFile> selected, ControlServer.QuoteControl.Mode mode)
    {
        running = true;
        var failures = new ConcurrentLinkedQueue<Failure>();

        try
        {
            for (var file : selected)
                refresh(file, failures, mode);

            lastUpdate = Instant.now();
        }
        catch (Exception e) // NOSONAR - the loop must survive whatever one feed does
        {
            lastError = e.getMessage();
            PortfolioLog.error(e);
        }
        finally
        {
            // One entry per instrument even when both its fetches failed: the reader
            // asks "which of my instruments are stale", not "which fetch broke".
            var byUuid = new LinkedHashMap<String, Failure>();
            for (var failure : failures)
                byUuid.putIfAbsent(failure.securityUuid(), failure);
            lastFailures = List.copyOf(byUuid.values());
            running = false;
        }
    }

    private void refresh(HeadlessHost.LoadedFile file, Collection<Failure> failures,
                    ControlServer.QuoteControl.Mode mode) throws Exception
    {
        // Snapshot the securities on the model thread; the list itself is mutable and
        // an API write could be adding to it right now.
        List<Security> securities = host.syncExec(() -> new ArrayList<>(file.getClient().getSecurities()));

        // Instruments never updated first, then oldest first, so a cycle cut short by
        // a rate limit still makes progress on a different subset each time.
        securities.sort(Comparator.comparing(s -> s.getEphemeralData().getFeedLastUpdate().orElse(null),
                        Comparator.nullsFirst(Comparator.naturalOrder())));

        var tasks = new ArrayList<Task>();
        for (var security : securities)
        {
            if (security.getEphemeralData().hasPermanentError())
                continue;

            var missingHistory = security.getPrices().isEmpty();
            var includeHistorical = mode == ControlServer.QuoteControl.Mode.FULL
                            || mode == ControlServer.QuoteControl.Mode.MISSING_HISTORY && missingHistory;

            // A historical response already carries the newest daily close, so a
            // first fill does not also ask for a latest quote: that would double the
            // requests of the one cycle that has the most of them to make. The later
            // cycles own the latest price.
            var includeLatest = mode != ControlServer.QuoteControl.Mode.MISSING_HISTORY;

            var historical = Factory.getQuoteFeedProvider(security.getFeed());
            if (includeHistorical && historical != null && !QuoteFeed.MANUAL.equals(historical.getId()))
                tasks.add(new HistoricalTask(historical, security,
                                mode == ControlServer.QuoteControl.Mode.MISSING_HISTORY
                                                ? LocalDate.now().minusDays(INITIAL_HISTORY_RANGE_DAYS)
                                                : null));

            var latestId = security.getLatestFeed() != null ? security.getLatestFeed() : security.getFeed();
            var latest = Factory.getQuoteFeedProvider(latestId);
            if (includeLatest && latest != null && !QuoteFeed.MANUAL.equals(latest.getId()))
                tasks.add(new LatestTask(latest, security));
        }

        if (tasks.isEmpty())
            return;

        var groups = tasks.stream().collect(Collectors.groupingBy(QuoteRefresher::criterionOf));

        var modified = new AtomicBoolean();
        var futures = groups.values().stream()
                        .map(group -> fetchers.submit(() -> runGroup(group, failures, modified)))
                        .toList();
        try
        {
            for (var future : futures)
                future.get();
        }
        finally
        {
            if (modified.get())
            {
                // One notification for the whole file rather than one per price.
                // The finally matters on shutdown: histories already applied before
                // interruption are marked for AutoSaver's final flush.
                host.syncExec(() -> {
                    file.getClient().markDirty();
                    return null;
                });
            }
        }
    }

    private static String criterionOf(Task task)
    {
        return task instanceof LatestTask ? task.feed.getLatestGroupingCriterion(task.security)
                        : task.feed.getGroupingCriterion(task.security);
    }

    /**
     * One grouping criterion's tasks, in order. Mirrors the desktop's
     * {@code RunTaskGroupJob}: a rate limit backs off and retries a bounded number of
     * times, a configuration error is permanent for that instrument, and an expired
     * authentication abandons the rest of the group because every one of them would
     * fail the same way.
     */
    private Boolean runGroup(List<Task> group, Collection<Failure> failures, AtomicBoolean fileModified)
    {
        var pending = new ArrayList<>(group);
        var attemptsLeft = group.get(0).feed.getMaxRateLimitAttempts();
        var modified = false;

        while (!pending.isEmpty())
        {
            var task = pending.remove(0);

            try
            {
                var apply = task.fetch();
                if (host.syncExec(apply::apply).booleanValue())
                {
                    modified = true;
                    fileModified.set(true);
                }
                task.security.getEphemeralData().touchFeedLastUpdate();
            }
            catch (AuthenticationExpiredException e)
            {
                lastError = "authentication expired for " + task.feed.getName();
                var reason = "needs a signed-in " + task.feed.getName() + " account";
                failures.add(failureOf(task, reason));
                pending.forEach(remaining -> failures.add(failureOf(remaining, reason)));
                PortfolioLog.warning("Skipping " + (pending.size() + 1) + " price update(s) from " + task.feed.getName()
                                + ": it needs a signed-in account, which a headless daemon cannot provide.");
                return Boolean.valueOf(modified);
            }
            catch (FeedConfigurationException e)
            {
                task.security.getEphemeralData().setHasPermanentError();
                failures.add(failureOf(task,
                                e.getMessage() != null ? e.getMessage() : "feed configuration issue"));
                PortfolioLog.warning("Instrument \"" + task.security.getName() + "\" has a feed configuration issue: "
                                + e.getMessage());
            }
            catch (RateLimitExceededException e)
            {
                attemptsLeft--;
                if (attemptsLeft < 0 || !e.getRetryAfter().isPositive())
                {
                    lastError = "rate limit exceeded for " + task.feed.getName();
                    var reason = "rate limit exceeded for " + task.feed.getName() + ", skipped this cycle";
                    failures.add(failureOf(task, reason));
                    pending.forEach(remaining -> failures.add(failureOf(remaining, reason)));
                    PortfolioLog.warning("Rate limit exceeded for " + task.feed.getName() + "; skipping "
                                    + (pending.size() + 1) + " price update(s) this cycle.");
                    return Boolean.valueOf(modified);
                }

                pending.add(0, task);
                try
                {
                    Thread.sleep(e.getRetryAfter().toMillis());
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    return Boolean.valueOf(modified);
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return Boolean.valueOf(modified);
            }
            catch (Exception e) // NOSONAR - one bad instrument must not stop the group
            {
                lastError = e.getMessage();
                failures.add(failureOf(task,
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                PortfolioLog.abbreviated(e);
            }
        }

        return Boolean.valueOf(modified);
    }

    private static Failure failureOf(Task task, String reason)
    {
        return new Failure(task.security.getUUID(), task.security.getName(), task.feed.getId(), reason);
    }
}
