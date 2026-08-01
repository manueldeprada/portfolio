package name.abuchen.portfolio.headless;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.ClientStore;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.PairingService;
import name.abuchen.portfolio.rest.RestApiServer;
import name.abuchen.portfolio.rest.RestApiWorkspace;
import name.abuchen.portfolio.rest.spi.OpenFile;

/**
 * Serves the REST API without the desktop application.
 * <p>
 * The composition is deliberately the same five objects the desktop's
 * {@code RestApiAddon} wires together - registry, client store, host, routes,
 * server - with only the host swapped. Nothing in the REST bundle changes, which is
 * what keeps this module purely additive against upstream.
 * <p>
 * Two things the desktop provides that are configuration here: which files are open
 * (loaded from the config at startup, and owned by this process for its lifetime),
 * and which files are exposed over the API (enabled in the registry from the same
 * config, instead of a checkbox in the preferences).
 * <p>
 * <strong>One owner per file.</strong> This daemon and the desktop application must
 * not hold the same {@code .portfolio} file open at once: each keeps its own
 * in-memory copy and the last writer wins.
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
    private HeadlessHost host;
    private final List<PortfolioFileLock> locks = new ArrayList<>();

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

        var files = new ArrayList<OpenFile>();
        for (var entry : config.files())
        {
            try
            {
                // Claimed before it is read: refusing after loading would leave a
                // second copy of a file this process does not own in memory.
                locks.add(PortfolioFileLock.acquire(entry.path(), config.port()));
            }
            catch (PortfolioFileLock.FileInUseException e)
            {
                PortfolioLog.error(e.getMessage());
                PortfolioFileLock.releaseAll(locks);
                return Integer.valueOf(IApplication.EXIT_OK);
            }
            catch (PortfolioFileLock.UnsupportedLockException e)
            {
                // A network share that cannot lock. Refusing to serve there would be
                // worse than the risk, but the operator has to know the guarantee is
                // not in force for this file. Per file, so one unlockable share does
                // not stop the others being claimed.
                PortfolioLog.warning(e.getMessage() + " - serving it anyway, but nothing prevents"
                                + " a second instance from owning it.");
            }

            files.add(open(entry, registry));
        }

        if (files.isEmpty())
        {
            PortfolioLog.error("No configured file could be opened; not starting the server.");
            PortfolioFileLock.releaseAll(locks);
            return Integer.valueOf(IApplication.EXIT_OK);
        }

        provisionClients(config, clientStore);

        host = new HeadlessHost(files);
        server = new RestApiServer(config.port(), token -> clientStore.authenticate(token).isPresent(),
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
            host.shutdown();
            PortfolioFileLock.releaseAll(locks);
            return Integer.valueOf(IApplication.EXIT_OK);
        }

        PortfolioLog.info("Portfolio Performance headless API listening on 127.0.0.1:" + server.getPort()
                        + " serving " + files.size() + " file(s)");

        context.applicationRunning();
        stopped.await();
        return Integer.valueOf(IApplication.EXIT_OK);
    }

    @Override
    public void stop()
    {
        if (server != null)
        {
            server.stop();
            server = null;
        }
        if (host != null)
        {
            host.shutdown();
            host = null;
        }
        PortfolioFileLock.releaseAll(locks);
        locks.clear();
        stopped.countDown();
    }

    /**
     * Loads one file and registers it as API-accessible. The registry is the same
     * store the desktop's preference page writes, so a record already there (uuid,
     * alias) is reused rather than replaced - the uuid is what clients address the
     * file by, and regenerating it would break every stored URL.
     */
    private static OpenFile open(HeadlessConfig.FileEntry entry, FileAccessRegistry registry)
                    throws IOException
    {
        var file = entry.path().toFile();
        if (!file.isFile())
            throw new IOException("Not a file: " + entry.path());

        var password = password(entry);
        var client = ClientFactory.load(file, password, new NullProgressMonitor());
        if (password != null)
            Arrays.fill(password, '\0');

        var path = file.getAbsolutePath();
        registry.ensureRecord(path);
        registry.setEnabled(path, true);
        if (entry.alias() != null)
            registry.setAlias(path, entry.alias());

        return new HeadlessHost.LoadedFile(path, labelOf(file), client);
    }

    private static char[] password(HeadlessConfig.FileEntry entry) throws IOException
    {
        if (entry.passwordEnv() == null)
            return null;

        var value = System.getenv(entry.passwordEnv());
        if (value == null || value.isEmpty())
            throw new IOException(entry.path() + " needs the password in $" + entry.passwordEnv() + ", which is unset");

        return value.toCharArray();
    }

    /** The file name without its extension, matching what the desktop shows. */
    private static String labelOf(File file)
    {
        var name = file.getName();
        var dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
