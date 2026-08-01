package name.abuchen.portfolio.headless;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import name.abuchen.portfolio.PortfolioLog;

/**
 * {@code GET /health} on a port of its own.
 * <p>
 * Separate from the REST API rather than a route on it, for two reasons. A liveness
 * probe cannot present a bearer token - a container's {@code HEALTHCHECK}, a systemd
 * watchdog and a reverse proxy all have none - and the REST API's contract is
 * {@code openapi.yaml}, which describes the desktop's API too. A route here changes
 * neither.
 * <p>
 * <strong>It answers 200 while the process is serving, even when something is
 * wrong.</strong> Being offline, or failing to save, is reported in the body as
 * {@code "status": "degraded"}; it is not a reason for a supervisor to restart a
 * daemon that is answering every request correctly from the data it has. Reserve a
 * non-200 for "this process cannot serve", which here means not answering at all.
 */
public class HealthServer
{
    /**
     * What a background refresh loop reports about itself. An interface rather than
     * the two concrete classes so that the report can be exercised without starting
     * their schedulers - and without a test having to reach the network to make one of
     * them succeed.
     */
    public interface Refresh
    {
        Optional<Instant> getLastUpdate();

        Optional<String> getLastError();
    }

    private final int port;
    private final int apiPort;
    private final List<HeadlessHost.LoadedFile> files;
    private final Refresh exchangeRates;
    private final Refresh quotes;
    private final Instant startedAt = Instant.now();

    private HttpServer server;

    public HealthServer(int port, int apiPort, List<HeadlessHost.LoadedFile> files, Refresh exchangeRates,
                    Refresh quotes)
    {
        this.port = port;
        this.apiPort = apiPort;
        this.files = List.copyOf(files);
        this.exchangeRates = exchangeRates;
        this.quotes = quotes;
    }

    public void start() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::dispatch); //$NON-NLS-1$

        // One thread: the response is a handful of counters read from volatile fields,
        // and a probe that has to queue behind another probe for a millisecond is not
        // a problem worth a pool.
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "pp-headless-health"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    /** The port actually bound, which differs from the configured one only for port 0. */
    public int boundPort()
    {
        return server != null ? server.getAddress().getPort() : port;
    }

    public void stop()
    {
        if (server != null)
        {
            server.stop(0);
            server = null;
        }
    }

    private void dispatch(HttpExchange exchange)
    {
        try
        {
            if (!"/health".equals(exchange.getRequestURI().getPath())) //$NON-NLS-1$
            {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            if (!"GET".equals(exchange.getRequestMethod())) //$NON-NLS-1$
            {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            var body = report().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$

            // Nothing here is cacheable; a probe reading a proxy's copy of the answer
            // would report the daemon healthy after it stopped being so.
            exchange.getResponseHeaders().set("Cache-Control", "no-store"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
        catch (IOException e)
        {
            PortfolioLog.abbreviated(e);
        }
        finally
        {
            exchange.close();
        }
    }

    /* testing */ JsonObject report()
    {
        var degraded = exchangeRates.getLastUpdate().isEmpty() //
                        || files.stream().anyMatch(f -> f.getSaveError().isPresent());

        var json = new JsonObject();
        json.addProperty("status", degraded ? "degraded" : "ok"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        json.addProperty("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds()); //$NON-NLS-1$
        json.addProperty("apiPort", apiPort); //$NON-NLS-1$

        // The exchange rates get their own block because "no rate has ever been
        // downloaded" is the one failure that makes converted figures wrong rather
        // than merely stale - see ExchangeRateRefresher.
        json.add("exchangeRates", refresher(exchangeRates.getLastUpdate(), exchangeRates.getLastError())); //$NON-NLS-1$
        json.add("quotes", refresher(quotes.getLastUpdate(), quotes.getLastError())); //$NON-NLS-1$

        var array = new JsonArray();
        for (var file : files)
        {
            var entry = new JsonObject();
            entry.addProperty("label", file.getLabel()); //$NON-NLS-1$
            entry.addProperty("dirty", file.isDirty()); //$NON-NLS-1$
            entry.addProperty("changeCount", file.getChangeCount()); //$NON-NLS-1$
            entry.addProperty("lastSave", file.getSavedAt().map(Instant::toString).orElse(null)); //$NON-NLS-1$
            entry.addProperty("saveError", file.getSaveError().orElse(null)); //$NON-NLS-1$
            array.add(entry);
        }
        json.add("files", array); //$NON-NLS-1$

        return json;
    }

    private static JsonObject refresher(Optional<Instant> lastUpdate, Optional<String> error)
    {
        var json = new JsonObject();
        json.addProperty("lastUpdate", lastUpdate.map(Instant::toString).orElse(null)); //$NON-NLS-1$
        json.addProperty("error", error.orElse(null)); //$NON-NLS-1$
        return json;
    }
}
