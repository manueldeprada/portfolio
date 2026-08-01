package name.abuchen.portfolio.headless;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.NullProgressMonitor;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.money.ExchangeRateProvider;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;

/**
 * Keeps the exchange rates current.
 * <p>
 * <strong>This is a correctness component, not a nicety.</strong> A fresh daemon
 * workspace has no stored ECB data, so
 * {@code ExchangeRateProviderFactory} falls back to the rates embedded in the
 * portfolio file - which are as old as the file. Measured on a real file during
 * phase 1, the daemon reported USD to CHF as 0.9937 where the desktop, with current
 * rates, reported 0.8101. Every converted figure the API returns is wrong until this
 * has run.
 * <p>
 * The rates themselves live in the Eclipse instance area (the provider writes
 * {@code ecb_exchange_rates.pb} into its bundle data directory), not in the
 * portfolio file, which is why a refresh never makes a file dirty. It does bump each
 * file's change counter, because every converted figure in every cached response has
 * just changed.
 */
public class ExchangeRateRefresher
{
    /**
     * The desktop's cadence, and for its reason: the ECB reference rates are
     * "usually updated at around 16:00 CET every working day", so the next attempt is
     * 17:00 CET, or six hours from now if that is sooner.
     */
    private static final Duration MAX_INTERVAL = Duration.ofHours(6);

    private final List<HeadlessHost.LoadedFile> files;
    private final ScheduledExecutorService scheduler;

    private volatile boolean loaded;
    private volatile Instant lastUpdate;
    private volatile String lastError;

    public ExchangeRateRefresher(List<HeadlessHost.LoadedFile> files)
    {
        this.files = List.copyOf(files);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "pp-headless-exchange-rates"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Loads the stored rates and downloads current ones, blocking up to
     * {@code timeout}. Startup waits for this on purpose: serving a converted figure
     * computed from rates embedded in the file is worse than starting a minute later,
     * and a client has no way to tell the two apart.
     * <p>
     * A timeout or a failure is logged and startup continues. An offline machine, or
     * one behind a proxy that blocks the ECB, must still be able to serve a
     * single-currency file, where none of this matters at all.
     */
    public void refreshBlocking(Duration timeout)
    {
        var task = scheduler.submit(this::refresh);

        try
        {
            task.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        }
        catch (java.util.concurrent.TimeoutException e)
        {
            PortfolioLog.warning("Exchange rates were still updating after " + timeout.toSeconds()
                            + "s; serving anyway. Figures that cross currencies may be stale until it finishes.");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (Exception e)
        {
            PortfolioLog.error(e);
        }
    }

    /** Schedules the recurring update. Call after {@link #refreshBlocking}. */
    public void start()
    {
        scheduler.schedule(this::runAndReschedule, millisUntilNextAttempt(), TimeUnit.MILLISECONDS);
    }

    public void shutdown()
    {
        scheduler.shutdownNow();

        // The provider holds the downloaded rates in memory and only writes them on
        // save; without this the next start re-downloads everything, and downloads
        // nothing at all if the network is gone by then.
        for (var provider : ExchangeRateProviderFactory.getProviders())
        {
            try
            {
                provider.save(new NullProgressMonitor());
            }
            catch (Exception e) // NOSONAR - a failed save must not block shutdown
            {
                PortfolioLog.error(e);
            }
        }
    }

    public Optional<Instant> getLastUpdate()
    {
        return Optional.ofNullable(lastUpdate);
    }

    public Optional<String> getLastError()
    {
        return Optional.ofNullable(lastError);
    }

    private void runAndReschedule()
    {
        try
        {
            refresh();
        }
        finally
        {
            scheduler.schedule(this::runAndReschedule, millisUntilNextAttempt(), TimeUnit.MILLISECONDS);
        }
    }

    private void refresh()
    {
        var failures = 0;
        var providers = ExchangeRateProviderFactory.getProviders();

        for (ExchangeRateProvider provider : providers)
        {
            // Reading the stored rates is a first start's only defence against being
            // offline, so it happens even if the download then fails - and only once,
            // because after that the in-memory data is newer than the file.
            if (!loaded)
            {
                try
                {
                    provider.load(new NullProgressMonitor());
                }
                catch (Exception e) // NOSONAR - a corrupt cache must not stop the download
                {
                    PortfolioLog.error(e);
                }
            }

            try
            {
                provider.update(new NullProgressMonitor());
                provider.save(new NullProgressMonitor());
            }
            catch (Exception e) // NOSONAR - offline is a normal state for a daemon
            {
                failures++;
                lastError = provider.getName() + ": " + e.getMessage();
                PortfolioLog.warning("Could not update exchange rates from " + provider.getName() + ": "
                                + e.getMessage());
            }
        }

        loaded = true;

        for (var file : files)
            file.onExchangeRatesUpdated();

        if (failures == 0)
        {
            lastError = null;
            lastUpdate = Instant.now();
        }
    }

    private static long millisUntilNextAttempt()
    {
        var cet = ZoneId.of("CET"); //$NON-NLS-1$
        var now = ZonedDateTime.now(cet);
        var next = now.toLocalDate().atTime(17, 0).atZone(cet);
        if (!next.isAfter(now))
            next = next.plusDays(1);

        var untilSeventeen = ChronoUnit.MILLIS.between(now, next);
        return Math.min(untilSeventeen, MAX_INTERVAL.toMillis());
    }
}
