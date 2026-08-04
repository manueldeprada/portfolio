package name.abuchen.portfolio.headless;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import name.abuchen.portfolio.PortfolioLog;

/**
 * The daemon's own control plane, on a port of its own: {@code GET /health}, and the
 * file administration a web service in front of this needs.
 * <p>
 * Separate from the REST API rather than routes on it, for two reasons that pull the
 * same way. A liveness probe cannot present a bearer token - a container's
 * {@code HEALTHCHECK}, a systemd watchdog and a reverse proxy all have none - and the
 * REST API's contract is {@code openapi.yaml}, which describes the desktop's API too.
 * Neither {@code /health} nor "open this path as a file" belongs there: the desktop
 * has no equivalent of the second one, since a user opens files by hand, and adding it
 * to the shared contract would be describing a route only one of the two hosts can
 * answer.
 * <p>
 * <strong>{@code /health} answers 200 while the process is serving, even when
 * something is wrong.</strong> Being offline, or failing to save, is reported in the
 * body as {@code "status": "degraded"}; it is not a reason for a supervisor to restart
 * a daemon that is answering every request correctly from the data it has. Reserve a
 * non-200 for "this process cannot serve", which here means not answering at all.
 * <p>
 * <strong>The file routes require the same bearer token the REST API takes</strong>,
 * and {@code /health} deliberately does not. Opening and deleting files is not
 * something anything that can reach the port may do, and that token is what an
 * operator has to hand out to have an API client at all - so there is one set of
 * credentials, not two.
 *
 * <pre>
 * GET    /health                    no token; liveness, plus what is degraded
 * GET    /files                     token; what is open, with each file's id
 * POST   /files                     token; open, create or copy one. An encrypted file
 *                                   takes a password; without one the answer is 401
 *                                   password-required, with a wrong one 403
 *                                   password-rejected
 * DELETE /files/{id}[?save=false]   token; save, release and forget one
 * GET    /quotes                    token; the last refresh cycle: when, what failed
 * POST   /quotes/refresh            token; queue a refresh cycle now, answered 202
 * </pre>
 *
 * The bodies are small on purpose - a path, a base currency, the id of a file to copy
 * - because what is on the other side is one process on loopback, not a public API.
 */
public class ControlServer
{
    /**
     * What a background refresh loop reports about itself. An interface rather than the
     * two concrete classes so that the report can be exercised without starting their
     * schedulers - and without a test having to reach the network to make one of them
     * succeed.
     */
    public interface Refresh
    {
        Optional<Instant> getLastUpdate();

        Optional<String> getLastError();
    }

    /**
     * What the quote refresher exposes beyond {@link Refresh}: a way to ask for a
     * cycle now, and what happened to each instrument in the last one. The exchange
     * rates need neither - their cadence is the ECB's publishing schedule, not a
     * user's button.
     */
    public interface QuoteControl extends Refresh
    {
        /**
         * One instrument that could not be updated in the last completed cycle.
         * {@code feed} is the failing feed's stable id ({@code PP}, {@code YAHOO},
         * ...), which is what lets a client offer "switch this instrument to a
         * feed that works here" rather than parsing the reason text.
         */
        record Failure(String securityUuid, String securityName, String feed, String reason)
        {
        }

        boolean isRunning();

        /** Queues a refresh cycle unless one is already queued; returns immediately. */
        void requestRefresh();

        List<Failure> getLastFailures();
    }

    private final int port;
    private final int apiPort;
    private final FileSet files;
    private final Refresh exchangeRates;
    private final QuoteControl quotes;

    /** The REST API's own authenticator, so there is one set of tokens rather than two. */
    private final Predicate<String> authenticator;

    private final Instant startedAt = Instant.now();

    private HttpServer server;

    public ControlServer(int port, int apiPort, FileSet files, Refresh exchangeRates, QuoteControl quotes,
                    Predicate<String> authenticator)
    {
        this.port = port;
        this.apiPort = apiPort;
        this.files = files;
        this.exchangeRates = exchangeRates;
        this.quotes = quotes;
        this.authenticator = authenticator;
    }

    public void start() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::dispatch);

        // One thread: a health report is a handful of counters read from volatile
        // fields, and the file routes serialize on FileSet's own lock anyway, so a pool
        // would buy nothing but the appearance of concurrency.
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "pp-headless-control");
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
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();

            if ("/health".equals(path))
            {
                if ("GET".equals(method))
                    send(exchange, 200, report());
                else
                    exchange.sendResponseHeaders(405, -1);
                return;
            }

            var isFiles = "/files".equals(path) || path.startsWith("/files/");
            var isQuotes = "/quotes".equals(path) || "/quotes/refresh".equals(path);

            if (!isFiles && !isQuotes)
            {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            if (!isAuthorized(exchange))
            {
                // The scheme is named, so a client that sent nothing is told what to
                // send rather than left to guess at the shape of the failure.
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, error("unauthorized", "a bearer token is required"));
                return;
            }

            if ("/quotes".equals(path))
            {
                if ("GET".equals(method))
                    send(exchange, 200, quoteStatus());
                else
                    exchange.sendResponseHeaders(405, -1);
                return;
            }

            if ("/quotes/refresh".equals(path))
            {
                if ("POST".equals(method))
                {
                    // Queued rather than run inline: this server has one thread, and a
                    // cycle can take a minute - waiting here would block /health with
                    // it. The caller polls GET /quotes until running turns false.
                    quotes.requestRefresh();
                    send(exchange, 202, quoteStatus());
                }
                else
                    exchange.sendResponseHeaders(405, -1);
                return;
            }

            if ("/files".equals(path))
            {
                switch (method)
                {
                    case "GET" -> send(exchange, 200, listFiles());
                    case "POST" -> postFile(exchange);
                    default -> exchange.sendResponseHeaders(405, -1);
                }
                return;
            }

            if ("DELETE".equals(method))
                deleteFile(exchange, path.substring("/files/".length()));
            else
                exchange.sendResponseHeaders(405, -1);
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

    private boolean isAuthorized(HttpExchange exchange)
    {
        var header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7))
            return false;

        return authenticator.test(header.substring(7).trim());
    }

    /**
     * Opens a file, creates an empty one, or copies an open one - one route, because
     * all three end in the same place (a file this process owns and serves) and the
     * caller's next request is the same whichever it asked for.
     */
    private void postFile(HttpExchange exchange) throws IOException
    {
        JsonObject body;
        try
        {
            var text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var element = JsonParser.parseString(text);
            if (!element.isJsonObject())
                throw new JsonSyntaxException("not an object");
            body = element.getAsJsonObject();
        }
        catch (JsonSyntaxException | IllegalStateException e)
        {
            send(exchange, 400, error("bad-request", "the body must be a JSON object"));
            return;
        }

        if (!body.has("path"))
        {
            send(exchange, 400, error("bad-request", "\"path\" is required"));
            return;
        }

        var path = Path.of(body.get("path").getAsString());
        if (!path.isAbsolute())
        {
            // The path is the identity key the registry and the open files are compared
            // by, and this process's working directory is not something the caller can
            // see - a relative path would silently resolve against it.
            send(exchange, 400, error("bad-request", "\"path\" must be absolute"));
            return;
        }

        var copyOf = body.has("copyOf") ? body.get("copyOf").getAsString() : null;
        var create = body.has("create") && body.get("create").getAsBoolean();

        if (copyOf != null && create)
        {
            send(exchange, 400, error("bad-request", "\"create\" and \"copyOf\" are exclusive"));
            return;
        }

        var password = password(body);

        try
        {
            FileSet.Opened opened;
            if (copyOf != null)
                opened = files.copy(copyOf, path, password);
            else if (create)
                opened = files.create(path, body.has("baseCurrency") ? body.get("baseCurrency").getAsString() : null);
            else
                opened = files.open(path, password);

            // 200 rather than 201 for a file that was already open: nothing was
            // created, and a caller re-asserting its whole list of files on startup
            // should be able to tell the difference without diffing what it sent.
            send(exchange, opened.outcome() == FileSet.Outcome.OPENED ? 201 : 200, describe(opened));
        }
        catch (FileAlreadyExistsException e)
        {
            send(exchange, 409, error("already-exists", e.getMessage()));
        }
        catch (NoSuchFileException e)
        {
            send(exchange, 404, error("not-found", e.getMessage()));
        }
        catch (PortfolioFileLock.FileInUseException e)
        {
            send(exchange, 409, error("in-use", e.getMessage()));
        }
        catch (FileSet.PasswordRequiredException e)
        {
            // Its own code, so the caller can ask a human for the password instead of
            // reporting the file as broken. 401 rather than 422: what is missing is a
            // credential, and the request is otherwise perfectly formed.
            send(exchange, 401, error("password-required", e.getMessage()));
        }
        catch (FileSet.PasswordRejectedException e)
        {
            send(exchange, 403, error("password-rejected", e.getMessage()));
        }
        catch (IOException | RuntimeException e)
        {
            // A file that is not a portfolio file, or a corrupt one: the request's fault
            // rather than this process's, and the message is the only thing that tells
            // them apart. Logged as the path and the reason - never the request body,
            // which may hold a password.
            PortfolioLog.warning("Could not open " + path + ": " + e.getMessage());
            send(exchange, 422,
                            error("cannot-open",
                                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        finally
        {
            // Wiped as soon as the load is done with it, so a heap dump taken later does
            // not still hold it.
            FileSet.wipe(password);
        }
    }

    /**
     * {@code ?save=false} for a caller that is about to delete the file: writing it one
     * last time first is work whose only effect is a longer window in which the file
     * exists.
     */
    private void deleteFile(HttpExchange exchange, String id) throws IOException
    {
        var save = !"false".equals(queryValue(exchange, "save"));

        try
        {
            files.close(id, save);
            exchange.sendResponseHeaders(204, -1);
        }
        catch (NoSuchFileException e)
        {
            send(exchange, 404, error("not-found", e.getMessage()));
        }
    }

    /**
     * The password for an encrypted file, from one of two places.
     * <p>
     * {@code passwordEnv} names an environment variable, which is what a file listed in
     * the configuration uses: that file sits next to the portfolio file and is meant to
     * be readable, so a password must not be in it.
     * <p>
     * {@code password} carries the value itself, which is what a service in front of
     * this daemon has - it asked a human a moment ago and holds it for the length of one
     * request. There is no environment variable it could put it in, and the alternative
     * is not supporting encrypted files at all. It is never logged, and nothing here
     * keeps it: {@code ClientFactory.load} leaves the derived key on the client, which
     * is what lets autosave re-encrypt without the password.
     */
    private static char[] password(JsonObject body)
    {
        if (body.has("password"))
        {
            var value = body.get("password").getAsString();
            return value.isEmpty() ? null : value.toCharArray();
        }

        if (!body.has("passwordEnv"))
            return null;

        var value = System.getenv(body.get("passwordEnv").getAsString());
        return value == null || value.isEmpty() ? null : value.toCharArray();
    }

    private JsonObject listFiles()
    {
        var json = new JsonObject();
        var array = new JsonArray();
        for (var file : files.list())
            array.add(describe(file));
        json.add("files", array);
        return json;
    }

    private JsonObject describe(FileSet.Opened opened)
    {
        var json = describe(opened.file());
        json.addProperty("created", opened.outcome() == FileSet.Outcome.OPENED);
        return json;
    }

    private JsonObject describe(HeadlessHost.LoadedFile file)
    {
        var json = new JsonObject();
        json.addProperty("id", files.idOf(file).orElse(null));
        json.addProperty("label", file.getLabel());
        json.addProperty("path", file.getPath());
        json.addProperty("dirty", file.isDirty());
        json.addProperty("changeCount", file.getChangeCount());
        return json;
    }

    /* testing */ JsonObject report()
    {
        var open = files.list();

        var degraded = exchangeRates.getLastUpdate().isEmpty()
                        || open.stream().anyMatch(f -> f.getSaveError().isPresent());

        var json = new JsonObject();
        json.addProperty("status", degraded ? "degraded" : "ok");
        json.addProperty("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        json.addProperty("apiPort", apiPort);

        // The exchange rates get their own block because "no rate has ever been
        // downloaded" is the one failure that makes converted figures wrong rather than
        // merely stale - see ExchangeRateRefresher.
        json.add("exchangeRates", refresher(exchangeRates.getLastUpdate(), exchangeRates.getLastError()));
        json.add("quotes", refresher(quotes.getLastUpdate(), quotes.getLastError()));

        var array = new JsonArray();
        for (var file : open)
        {
            var entry = new JsonObject();
            entry.addProperty("label", file.getLabel());
            entry.addProperty("dirty", file.isDirty());
            entry.addProperty("changeCount", file.getChangeCount());
            entry.addProperty("lastSave", file.getSavedAt().map(Instant::toString).orElse(null));
            entry.addProperty("saveError", file.getSaveError().orElse(null));
            array.add(entry);
        }
        json.add("files", array);

        return json;
    }

    /* testing */ JsonObject quoteStatus()
    {
        var json = refresher(quotes.getLastUpdate(), quotes.getLastError());
        json.addProperty("running", quotes.isRunning());

        var failures = new JsonArray();
        for (var failure : quotes.getLastFailures())
        {
            var entry = new JsonObject();
            entry.addProperty("uuid", failure.securityUuid());
            entry.addProperty("name", failure.securityName());
            entry.addProperty("feed", failure.feed());
            entry.addProperty("reason", failure.reason());
            failures.add(entry);
        }
        json.add("failures", failures);

        return json;
    }

    private static JsonObject refresher(Optional<Instant> lastUpdate, Optional<String> error)
    {
        var json = new JsonObject();
        json.addProperty("lastUpdate", lastUpdate.map(Instant::toString).orElse(null));
        json.addProperty("error", error.orElse(null));
        return json;
    }

    private static JsonObject error(String code, String message)
    {
        var json = new JsonObject();
        json.addProperty("error", code);
        json.addProperty("message", message);
        return json;
    }

    private static String queryValue(HttpExchange exchange, String key)
    {
        var query = exchange.getRequestURI().getQuery();
        if (query == null)
            return null;

        for (var pair : query.split("&"))
        {
            var index = pair.indexOf('=');
            if (index > 0 && key.equals(pair.substring(0, index)))
                return pair.substring(index + 1);
        }
        return null;
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException
    {
        var bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");

        // Nothing here is cacheable; a probe reading a proxy's copy of the answer would
        // report the daemon healthy after it stopped being so.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
