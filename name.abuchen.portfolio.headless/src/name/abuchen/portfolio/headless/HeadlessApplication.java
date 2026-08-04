package name.abuchen.portfolio.headless;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.ClientStore;
import name.abuchen.portfolio.rest.PairingService;
import name.abuchen.portfolio.rest.RestApiServer;
import name.abuchen.portfolio.rest.RestApiWorkspace;

/**
 * Serves the REST API without the desktop application.
 * <p>
 * The composition is deliberately the same five objects the desktop's
 * {@code RestApiAddon} wires together - registry, client store, host, routes,
 * server - with only the host swapped. Nothing in the REST bundle changes, which is
 * what keeps this module purely additive against upstream.
 * <p>
 * Around that sit the four things the desktop gets from a user sitting in front of
 * it, and a daemon has to do for itself: {@link ExchangeRateRefresher} (without
 * which every converted figure is wrong), {@link QuoteRefresher},
 * {@link AutoSaver} (without which every write is lost on exit), and
 * {@link ControlServer}.
 * <p>
 * Which files are open, and which of them the API exposes, is what the desktop learns
 * from a user and this process does not. Both come from {@link FileSet}: seeded from
 * the configuration at startup, and changed at runtime by whatever manages this daemon
 * over the control API. <strong>A daemon serving no files at all is a normal
 * state</strong> - it is what a fresh install of a web service in front of it looks
 * like, before any account has uploaded anything.
 * <p>
 * <strong>One owner per file.</strong> This daemon and the desktop application must
 * not hold the same {@code .portfolio} file open at once: each keeps its own
 * in-memory copy and the last writer wins. Since the daemon now saves by itself,
 * that is no longer a theoretical risk.
 */
public class HeadlessApplication implements IApplication
{
    /**
     * {@code -ppConfig <path>}, the {@code pp.headless.config} system property, or
     * this under the user's home. Deliberately not {@code -config}: Equinox owns
     * {@code -configuration} and echoes near-misses as framework arguments, which
     * makes an app-level {@code -config} needlessly ambiguous to read in a log.
     */
    private static final String CONFIG_ARG = "-ppConfig";
    private static final String CONFIG_PROPERTY = "pp.headless.config";
    private static final String DEFAULT_CONFIG = ".portfolio-performance/headless.json";

    private final CountDownLatch stopped = new CountDownLatch(1);

    private RestApiServer server;
    private ControlServer controlServer;
    private HeadlessHost host;
    private FileSet files;
    private AutoSaver autoSaver;
    private ExchangeRateRefresher exchangeRates;
    private QuoteRefresher quotes;

    @Override
    public Object start(IApplicationContext context) throws Exception
    {
        var args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        var configPath = configPath(args);

        if (!Files.isReadable(configPath))
        {
            PortfolioLog.error("No readable configuration at " + configPath + ". Pass " + CONFIG_ARG
                            + " <path>, set -D" + CONFIG_PROPERTY + ", or create that file.");
            return Integer.valueOf(IApplication.EXIT_OK);
        }

        var config = HeadlessConfig.read(configPath);

        // Before loading anything: a server already on this port is almost always a
        // desktop instance with the API enabled, or another daemon. Saying so here
        // is far clearer than the bind failure further down.
        if (isPortInUse(config.port()))
        {
            PortfolioLog.error("Port " + config.port() + " is already in use - a Portfolio Performance instance"
                            + " (desktop or headless) is very likely already serving the API. Not starting.");
            return Integer.valueOf(IApplication.EXIT_OK);
        }

        var registry = RestApiWorkspace.createFileAccessRegistry();
        var clientStore = RestApiWorkspace.getClientStore();

        files = new FileSet(registry, config.port(), config.backupOnStart());

        for (var entry : config.files())
        {
            try
            {
                char[] password = password(entry);
                try
                {
                    files.open(entry.path(), password, entry.alias());
                }
                finally
                {
                    FileSet.wipe(password);
                }
            }
            catch (PortfolioFileLock.FileInUseException e)
            {
                // Another instance owns this file. Refusing to start is the point: the
                // operator asked for it, and serving the rest silently would leave them
                // reading a file list that is missing one.
                PortfolioLog.error(e.getMessage());
                files.releaseLocks();
                return Integer.valueOf(IApplication.EXIT_OK);
            }
            catch (IOException | RuntimeException e)
            {
                // Not fatal any more: a daemon with no files is a normal state now that
                // the control API can open them, so one unreadable file in the config
                // is worth a line in the log rather than a refusal to start.
                PortfolioLog.error("Could not open " + entry.path() + ": " + e.getMessage());
            }
        }

        provisionClients(config, clientStore);

        host = new HeadlessHost(files::listOpenFiles);
        files.attach(host);

        exchangeRates = new ExchangeRateRefresher(files);

        // Blocking, and before the server binds: a fresh workspace has no ECB data,
        // so until this returns the factory falls back to the rates stored in the
        // file and every converted figure is wrong by whole percent. A client cannot
        // tell that from a correct answer, so it must not be served one.
        PortfolioLog.info("Updating exchange rates before serving...");
        exchangeRates.refreshBlocking(config.exchangeRateTimeout());
        exchangeRates.start();

        server = new RestApiServer(config.port(), config.workerThreads(),
                        token -> clientStore.authenticate(token).isPresent(),
                        ApiRoutes.create(registry, host, new PairingService(clientStore, host)));

        try
        {
            server.start();
        }
        catch (IOException e)
        {
            // Same choice as the desktop: no port hopping. A daemon that quietly
            // moved would leave every configured client pointing at nothing.
            PortfolioLog.error(e);
            server = null;
            shutdownComponents();
            return Integer.valueOf(IApplication.EXIT_OK);
        }

        if (config.autosave().isZero())
        {
            PortfolioLog.warning("Autosave is disabled, so anything written through the API is lost on exit.");
        }
        else
        {
            autoSaver = new AutoSaver(files);
            autoSaver.start(config.autosave());
        }

        quotes = new QuoteRefresher(host, files);
        if (!config.quoteRefresh().isZero())
            quotes.start(config.quoteRefresh());

        startControlServer(config, clientStore);

        // A daemon is stopped with a signal, not by closing a window. Equinox does
        // call stop() when the framework shuts down cleanly, but the unsaved work
        // this now has to flush is worth not depending on that: the hook makes the
        // flush happen on any path out, and stop() is idempotent.
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "pp-headless-shutdown"));

        PortfolioLog.info("Portfolio Performance headless API listening on 127.0.0.1:" + server.getPort()
                        + " serving " + files.list().size() + " file(s)");

        context.applicationRunning();
        stopped.await();
        return Integer.valueOf(IApplication.EXIT_OK);
    }

    /**
     * Synchronized because two paths reach it - Equinox's own shutdown and the
     * shutdown hook - and they can overlap.
     */
    @Override
    public synchronized void stop()
    {
        // Requests first: a handler running while the model is being serialized would
        // read a half-consistent model, and one arriving afterwards would be answered
        // out of a model nothing is persisting any more.
        if (server != null)
        {
            server.stop();
            server = null;
        }
        if (controlServer != null)
        {
            controlServer.stop();
            controlServer = null;
        }

        shutdownComponents();
        stopped.countDown();
    }

    /**
     * Order matters: the refreshers stop mutating the model before the autosaver
     * flushes it, or the flush would miss what a refresh landed after it read the
     * dirty flag. The model thread goes last, because the flush runs on it.
     */
    private void shutdownComponents()
    {
        if (quotes != null)
        {
            quotes.shutdown();
            quotes = null;
        }
        if (exchangeRates != null)
        {
            exchangeRates.shutdown();
            exchangeRates = null;
        }
        if (autoSaver != null)
        {
            autoSaver.shutdown();
            autoSaver = null;
        }
        if (host != null)
        {
            host.shutdown();
            host = null;
        }

        if (files != null)
            files.releaseLocks();
    }

    /**
     * A liveness probe is not correctness, so a port already taken is a warning and
     * the daemon serves the API without one - unlike the API's own port, where
     * something already listening means another instance owns these files.
     * <p>
     * The file administration on the same port goes with it: a caller that cannot
     * reach the control API can still read and write through the REST API, which is
     * exactly the daemon this module started out as.
     */
    private void startControlServer(HeadlessConfig config, ClientStore clientStore)
    {
        if (config.healthPort() == 0)
            return;

        controlServer = new ControlServer(config.healthPort(), config.port(), files, exchangeRates, quotes,
                        token -> clientStore.authenticate(token).isPresent());
        try
        {
            controlServer.start();
            PortfolioLog.info("Health endpoint on http://127.0.0.1:" + config.healthPort() + "/health"
                            + ", file administration on the same port");
        }
        catch (IOException e)
        {
            controlServer = null;
            PortfolioLog.warning("Could not bind the control endpoint on port " + config.healthPort() + " ("
                            + e.getMessage() + "); serving the API without it.");
        }
    }

    /**
     * The password is wiped after the load and never kept: an encrypted file is
     * re-encrypted from the key {@code ClientFactory.load} left on the
     * {@code Client}, so autosave needs nothing more.
     */
    private static char[] password(HeadlessConfig.FileEntry entry) throws IOException
    {
        if (entry.passwordEnv() == null)
            return null;

        var value = System.getenv(entry.passwordEnv());
        if (value == null || value.isEmpty())
            throw new IOException(entry.path() + " needs the password in $" + entry.passwordEnv() + ", which is unset");

        return value.toCharArray();
    }

    /**
     * Mints a token for each configured client that does not have one yet, and logs
     * it once. The store only ever returns a token's plaintext at creation, so this
     * is the single moment it can be handed over; on later starts the existing
     * client is left alone and nothing is logged.
     */
    private static void provisionClients(HeadlessConfig config, ClientStore clientStore)
    {
        List<String> existing = clientStore.listClients().stream().map(ClientStore.ApiClient::name).toList();

        for (var name : config.clients())
        {
            if (existing.contains(name))
                continue;

            var token = clientStore.addPersistentClient(name);
            PortfolioLog.info("Provisioned API client \"" + name + "\". Token (shown only now): " + token);
        }
    }

    /** Loopback only, matching where the server binds. */
    private static boolean isPortInUse(int port)
    {
        if (port == 0)
            return false;

        try (var socket = new ServerSocket())
        {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return false;
        }
        catch (IOException e)
        {
            return true;
        }
    }

    private static Path configPath(String[] args)
    {
        if (args != null)
        {
            for (var i = 0; i < args.length - 1; i++)
            {
                if (CONFIG_ARG.equals(args[i]))
                    return Path.of(args[i + 1]).toAbsolutePath().normalize();
            }
        }

        var property = System.getProperty(CONFIG_PROPERTY);
        if (property != null && !property.isBlank())
            return Path.of(property).toAbsolutePath().normalize();

        return Path.of(System.getProperty("user.home"), DEFAULT_CONFIG);
    }
}
