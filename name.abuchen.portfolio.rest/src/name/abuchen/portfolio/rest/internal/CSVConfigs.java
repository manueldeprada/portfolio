package name.abuchen.portfolio.rest.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import name.abuchen.portfolio.datatransfer.csv.CSVConfig;
import name.abuchen.portfolio.datatransfer.csv.CSVConfigManager;

/**
 * The CSV import configurations a caller can name on {@code POST .../imports}.
 * <p>
 * The desktop's {@link CSVConfigManager} loads the same built-in list but is an
 * e4-injected singleton (its user-specific configurations live in
 * {@code IEclipsePreferences} handed to it by dependency injection), which a
 * headless REST call has no context to obtain. Rather than reimplement that
 * injection here, this class reads the identical bundled resource directly -
 * only the built-in configurations are exposed over the API. A user-specific
 * configuration created in the desktop wizard is not visible here; that is the
 * one thing "saved configs" does not yet mean over the API, and is worth
 * revisiting only if a caller actually needs a custom mapping saved from a
 * script rather than from the desktop.
 */
final class CSVConfigs
{
    private static List<CSVConfig> builtIn;

    private CSVConfigs()
    {
    }

    static synchronized List<CSVConfig> builtIn()
    {
        if (builtIn == null)
            builtIn = Collections.unmodifiableList(load());
        return builtIn;
    }

    static Optional<CSVConfig> byLabel(String label)
    {
        return builtIn().stream().filter(c -> c.getLabel().equals(label)).findAny();
    }

    private static List<CSVConfig> load()
    {
        try (var stream = CSVConfigManager.class.getResourceAsStream("csv-config.json"); //$NON-NLS-1$
                        var scanner = new Scanner(stream, StandardCharsets.UTF_8.name()))
        {
            var json = scanner.useDelimiter("\\A").next(); //$NON-NLS-1$
            var array = (JSONArray) JSONValue.parseWithException(json);

            var configs = new ArrayList<CSVConfig>();
            for (var ii = 0; ii < array.size(); ii++)
            {
                var config = new CSVConfig();
                config.fromJSON((JSONObject) array.get(ii));
                configs.add(config);
            }
            return configs;
        }
        catch (IOException | ParseException e)
        {
            throw new IllegalStateException("the bundled CSV import configurations could not be read", e); //$NON-NLS-1$
        }
    }
}
