package name.abuchen.portfolio.rest.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

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
 * injection here, this class reads the identical bundled resource directly for
 * the built-in list.
 * <p>
 * A configuration built over the API (an inline {@code mapping} on
 * {@code POST .../imports}, saved with {@code save=true}) is kept in a second,
 * in-memory store instead: there is no {@code IEclipsePreferences} to write it
 * into headless, and no other durable location this bundle can reach without
 * knowing about a particular deployment's file layout (the headless daemon's
 * workspace, for instance, is a concept this bundle does not have). It is
 * therefore process-lifetime only - it does not survive a restart, and it is
 * not shared between REST server instances - documented here rather than
 * silently discovered the first time a saved mapping disappears. A label
 * saved this way shadows a built-in one of the same name for {@link #byLabel}
 * and {@link #all}, on the reasoning that a caller who named their own
 * mapping "SECURITIES" meant their own.
 */
final class CSVConfigs
{
    private static List<CSVConfig> builtIn;

    private static final Map<String, CSVConfig> CUSTOM = new ConcurrentHashMap<>();

    private CSVConfigs()
    {
    }

    static synchronized List<CSVConfig> builtIn()
    {
        if (builtIn == null)
            builtIn = Collections.unmodifiableList(load());
        return builtIn;
    }

    /** Built-in configurations first, then the custom ones saved over the API. */
    static List<CSVConfig> all()
    {
        var answer = new ArrayList<CSVConfig>();
        for (var config : builtIn())
            if (!CUSTOM.containsKey(config.getLabel()))
                answer.add(config);
        answer.addAll(CUSTOM.values());
        return answer;
    }

    static Optional<CSVConfig> byLabel(String label)
    {
        var custom = CUSTOM.get(label);
        if (custom != null)
            return Optional.of(custom);
        return builtIn().stream().filter(c -> c.getLabel().equals(label)).findAny();
    }

    /** Keeps a mapping built from an inline {@code mapping} for later reuse by label. */
    static void save(CSVConfig config)
    {
        CUSTOM.put(config.getLabel(), config);
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
