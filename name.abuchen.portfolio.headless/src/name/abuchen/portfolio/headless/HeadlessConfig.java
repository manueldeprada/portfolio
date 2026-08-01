package name.abuchen.portfolio.headless;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *   "files": [
 *     { "path": "/home/me/portfolio.xml", "alias": "main", "passwordEnv": "PP_PASSWORD_MAIN" }
 *   ],
 *   "clients": ["webapp"]
 * }
 * </pre>
 *
 * A password is named, never written here: the config file sits next to the
 * portfolio file and is meant to be readable, so an encrypted file's password is
 * taken from the named environment variable instead.
 */
public record HeadlessConfig(int port, List<FileEntry> files, List<String> clients)
{
    public record FileEntry(Path path, String alias, String passwordEnv)
    {
    }

    /** The daemon owns the files it serves - see the one-owner rule in the README. */
    public static final int DEFAULT_PORT = 5712;

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

        return new HeadlessConfig(port, List.copyOf(files), List.copyOf(clients));
    }
}
