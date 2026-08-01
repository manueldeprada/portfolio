package name.abuchen.portfolio.headless;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * What the daemon serves, read from a JSON file.
 * <p>
 * The desktop learns all of this from the user: which files are open, which are
 * shared over the API, under what alias, and on which port. Headless there is
 * nobody to ask, so it is declared up front:
 *
 * <pre>
 * {
 *   "port": 5712,
 *   "healthPort": 5713,
 *   "workerThreads": 8,
 *   "files": [
 *     { "path": "/home/me/portfolio.xml", "alias": "main", "passwordEnv": "PP_PASSWORD_MAIN" }
 *   ],
 *   "clients": ["webapp"],
 *   "autosaveSeconds": 60,
 *   "backupOnStart": true,
 *   "exchangeRateTimeoutSeconds": 60,
 *   "quoteRefreshMinutes": 60
 * }
 * </pre>
 *
 * A password is named, never written here: the config file sits next to the
 * portfolio file and is meant to be readable, so an encrypted file's password is
 * taken from the named environment variable instead.
 */
public record HeadlessConfig(int port, int healthPort, int workerThreads, List<FileEntry> files, List<String> clients,
                Duration autosave, boolean backupOnStart, Duration exchangeRateTimeout, Duration quoteRefresh)
{
    public record FileEntry(Path path, String alias, String passwordEnv)
    {
    }

    /** The daemon owns the files it serves - see the one-owner rule in the README. */
    public static final int DEFAULT_PORT = 5712;

    /**
     * Sized for several clients rather than the desktop's one: unlike the desktop,
     * where {@code RestApiServer} fixes the pool at two, a daemon is what a container
     * probe, a browser tab and a script all talk to at once. The {@code calc(...)}
     * seam already runs the expensive computation off the model thread, so the extra
     * workers buy parallelism rather than just a longer queue.
     */
    public static final int DEFAULT_WORKER_THREADS = 8;

    /**
     * The desktop's rule is "only the user saves"; there is no user here, so the
     * daemon sweeps dirty files on this interval. It is also the amount of work at
     * risk if the process is killed.
     */
    public static final Duration DEFAULT_AUTOSAVE = Duration.ofSeconds(60);

    /**
     * How long startup waits for the first exchange-rate download before serving
     * anyway. Rates that fall back to the ones embedded in the file are wrong by
     * whole percent (see the README), so it is worth waiting for them - but not
     * worth refusing to serve a single-currency file on a machine that is offline.
     */
    public static final Duration DEFAULT_EXCHANGE_RATE_TIMEOUT = Duration.ofSeconds(60);

    /** Matches the desktop's own default cadence for the periodic price update. */
    public static final Duration DEFAULT_QUOTE_REFRESH = Duration.ofMinutes(60);

    public static HeadlessConfig read(Path file) throws IOException
    {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            var element = JsonParser.parseReader(reader);
            if (!element.isJsonObject())
                throw new IOException(file + ": expected a JSON object");
            return parse(element.getAsJsonObject(), file);
        }
        catch (JsonSyntaxException e)
        {
            throw new IOException(file + " is not valid JSON", e);
        }
    }

    private static HeadlessConfig parse(JsonObject json, Path source) throws IOException
    {
        var port = json.has("port") ? json.get("port").getAsInt() : DEFAULT_PORT;
        if (port < 0 || port > 65535)
            throw new IOException(source + ": port must be between 0 and 65535");

        // One past the API port, so a config that names only "port" still gets a
        // liveness probe. Zero disables it; the daemon serves the API either way.
        var healthPort = json.has("healthPort") ? json.get("healthPort").getAsInt() : port + 1;
        if (healthPort < 0 || healthPort > 65535)
            throw new IOException(source + ": healthPort must be between 0 and 65535");
        if (healthPort != 0 && healthPort == port)
            throw new IOException(source + ": healthPort must differ from port");

        var workerThreads = json.has("workerThreads") ? json.get("workerThreads").getAsInt()
                        : DEFAULT_WORKER_THREADS;
        if (workerThreads < 1)
            throw new IOException(source + ": workerThreads must be at least 1");

        var files = new ArrayList<FileEntry>();
        if (json.has("files"))
        {
            for (var element : json.getAsJsonArray("files"))
            {
                var entry = element.getAsJsonObject();
                if (!entry.has("path"))
                    throw new IOException(source + ": every entry in \"files\" needs a \"path\"");

                // Absolute, because the path is the API's identity key for a file and
                // is compared literally against the open files - see FileResolver.
                var path = Path.of(entry.get("path").getAsString()).toAbsolutePath().normalize();

                files.add(new FileEntry(path, //
                                entry.has("alias") ? entry.get("alias").getAsString() : null,
                                entry.has("passwordEnv") ? entry.get("passwordEnv").getAsString() : null));
            }
        }

        if (files.isEmpty())
            throw new IOException(source + ": \"files\" is empty, so the daemon would serve nothing");

        var clients = new ArrayList<String>();
        if (json.has("clients"))
        {
            for (var element : json.getAsJsonArray("clients"))
                clients.add(element.getAsString());
        }

        return new HeadlessConfig(port, healthPort, workerThreads, List.copyOf(files), List.copyOf(clients),
                        seconds(json, "autosaveSeconds", DEFAULT_AUTOSAVE, source),
                        json.has("backupOnStart") ? json.get("backupOnStart").getAsBoolean() : true,
                        seconds(json, "exchangeRateTimeoutSeconds", DEFAULT_EXCHANGE_RATE_TIMEOUT, source),
                        minutes(json, "quoteRefreshMinutes", DEFAULT_QUOTE_REFRESH, source));
    }

    /**
     * Durations are read as whole seconds or minutes rather than as an ISO period:
     * the unit is in the key, so a number cannot be misread, and zero consistently
     * means "off" for every one of them.
     */
    private static Duration seconds(JsonObject json, String key, Duration fallback, Path source) throws IOException
    {
        return Duration.ofSeconds(number(json, key, fallback.toSeconds(), source));
    }

    private static Duration minutes(JsonObject json, String key, Duration fallback, Path source) throws IOException
    {
        return Duration.ofMinutes(number(json, key, fallback.toMinutes(), source));
    }

    private static long number(JsonObject json, String key, long fallback, Path source) throws IOException
    {
        if (!json.has(key))
            return fallback;

        var value = json.get(key).getAsLong();
        if (value < 0)
            throw new IOException(source + ": " + key + " must not be negative (0 disables it)");

        return value;
    }
}
