package name.abuchen.portfolio.headless;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.ClientFileType;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.rest.FileAccessRegistry;

/**
 * Two surfaces on one port, and the split between them is what is worth pinning down.
 * {@code /health} is what a container probe or a supervisor sees, so it answers 200
 * while the process serves - being offline is not a reason to restart a daemon that
 * answers every request correctly - and it takes no token, because a probe has none to
 * give. The file routes take one, because opening and deleting files is not something
 * anything that can reach the port may do.
 */
@SuppressWarnings("nls")
public class ControlServerTest
{
    private static final String TOKEN = "test-token";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final class FixedRefresh implements ControlServer.Refresh
    {
        private final Instant lastUpdate;

        private FixedRefresh(Instant lastUpdate)
        {
            this.lastUpdate = lastUpdate;
        }

        static FixedRefresh never()
        {
            return new FixedRefresh(null);
        }

        static FixedRefresh succeeded()
        {
            return new FixedRefresh(Instant.parse("2026-08-01T12:00:00Z"));
        }

        @Override
        public Optional<Instant> getLastUpdate()
        {
            return Optional.ofNullable(lastUpdate);
        }

        @Override
        public Optional<String> getLastError()
        {
            return Optional.empty();
        }
    }

    /**
     * A quote refresher that never runs: the routes' contract - what a trigger
     * answers, how a failure is reported - is what these tests pin down, not the
     * refresher itself, which needs the network.
     */
    private static final class FixedQuotes implements ControlServer.QuoteControl
    {
        private final Instant lastUpdate;
        private final List<Failure> failures;
        private boolean requested;

        FixedQuotes(Instant lastUpdate, List<Failure> failures)
        {
            this.lastUpdate = lastUpdate;
            this.failures = failures;
        }

        static FixedQuotes never()
        {
            return new FixedQuotes(null, List.of());
        }

        @Override
        public Optional<Instant> getLastUpdate()
        {
            return Optional.ofNullable(lastUpdate);
        }

        @Override
        public Optional<String> getLastError()
        {
            return Optional.empty();
        }

        @Override
        public boolean isRunning()
        {
            return requested;
        }

        @Override
        public void requestRefresh()
        {
            requested = true;
        }

        @Override
        public List<Failure> getLastFailures()
        {
            return failures;
        }
    }

    private FileSet emptySet()
    {
        var node = InstanceScope.INSTANCE.getNode("headless-control-" + UUID.randomUUID());
        return new FileSet(new FileAccessRegistry(node), 5712, false);
    }

    private FileSet setWith(HeadlessHost.LoadedFile file)
    {
        var set = emptySet();
        set.adopt(file);
        return set;
    }

    private static HeadlessHost.LoadedFile file()
    {
        return new HeadlessHost.LoadedFile("/tmp/a.xml", "a", new Client());
    }

    private ControlServer server(FileSet files, ControlServer.Refresh rates)
    {
        return server(files, rates, FixedQuotes.never());
    }

    private ControlServer server(FileSet files, ControlServer.Refresh rates, ControlServer.QuoteControl quotes)
    {
        return new ControlServer(0, 5712, files, rates, quotes, TOKEN::equals);
    }

    @Test
    public void testRatesThatHaveNeverBeenDownloadedAreDegraded()
    {
        var report = server(setWith(file()), FixedRefresh.never()).report();

        assertThat(report.get("status").getAsString(), is("degraded"));
        assertThat(report.get("exchangeRates").getAsJsonObject().get("lastUpdate").isJsonNull(), is(true));
    }

    @Test
    public void testAFreshDaemonWithCurrentRatesIsOk()
    {
        var report = server(setWith(file()), FixedRefresh.succeeded()).report();

        assertThat(report.get("status").getAsString(), is("ok"));
        assertThat(report.get("apiPort").getAsInt(), is(5712));
        assertThat(report.get("exchangeRates").getAsJsonObject().get("lastUpdate").getAsString(),
                        is("2026-08-01T12:00:00Z"));
    }

    /** A daemon with no files at all is a normal state, not a degraded one. */
    @Test
    public void testNoOpenFilesIsNotDegraded()
    {
        var report = server(emptySet(), FixedRefresh.succeeded()).report();

        assertThat(report.get("status").getAsString(), is("ok"));
        assertThat(report.get("files").getAsJsonArray().size(), is(0));
    }

    /**
     * A file the daemon cannot write is the failure an operator has no other way of
     * seeing: nothing in the API's own responses changes when a save fails.
     */
    @Test
    public void testAFileThatCannotBeSavedIsDegradedAndNamed()
    {
        var file = file();
        file.getClient().addSecurity(new Security());
        file.markSaveFailed("read-only file system");

        var report = server(setWith(file), FixedRefresh.succeeded()).report();

        assertThat(report.get("status").getAsString(), is("degraded"));

        var entry = report.get("files").getAsJsonArray().get(0).getAsJsonObject();
        assertThat(entry.get("label").getAsString(), is("a"));
        assertThat(entry.get("dirty").getAsBoolean(), is(true));
        assertThat(entry.get("saveError").getAsString(), is("read-only file system"));
    }

    @Test
    public void testUnsavedWorkIsReportedWithoutBeingAFailure()
    {
        var file = file();
        file.getClient().addSecurity(new Security());

        var report = server(setWith(file), FixedRefresh.succeeded()).report();

        // Dirty is the normal state between two sweeps, so it is reported but does not
        // make the daemon degraded - only a save that actually failed does.
        assertThat(report.get("status").getAsString(), is("ok"));
        assertThat(report.get("files").getAsJsonArray().get(0).getAsJsonObject().get("dirty").getAsBoolean(), is(true));
    }

    @Test
    public void testHealthAnswersWithoutATokenAndOnlyOnKnownPaths() throws IOException
    {
        var server = server(setWith(file()), FixedRefresh.never());
        server.start();

        try
        {
            var port = server.boundPort();

            // 200 even though the rates are missing: the process is serving, which is
            // what a liveness probe asks about.
            var health = request(port, "GET", "/health", null, null);
            assertThat(health.status(), is(200));
            assertThat(health.body(), containsString("\"status\":\"degraded\""));

            assertThat(request(port, "GET", "/", null, null).status(), is(404));
            assertThat(request(port, "GET", "/v1/files", null, null).status(), is(404));
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * The file routes are the reason this port is no longer read-only, so the token is
     * what stands between anything that can reach loopback and someone else's portfolio
     * file.
     */
    @Test
    public void testTheFileRoutesRefuseWithoutTheToken() throws IOException
    {
        var server = server(emptySet(), FixedRefresh.succeeded());
        server.start();

        try
        {
            var port = server.boundPort();

            assertThat(request(port, "GET", "/files", null, null).status(), is(401));
            assertThat(request(port, "GET", "/files", null, "wrong").status(), is(401));
            assertThat(request(port, "POST", "/files", "{}", "wrong").status(), is(401));
            assertThat(request(port, "GET", "/files", null, TOKEN).status(), is(200));
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * The round trip the web service in front of this daemon actually makes: create a
     * file, see it in the list, and give it up again.
     */
    @Test
    public void testAFileCanBeCreatedListedAndClosedOverHttp() throws Exception
    {
        var files = emptySet();
        var host = new HeadlessHost(files::listOpenFiles);
        files.attach(host);

        var server = server(files, FixedRefresh.succeeded());
        server.start();

        try
        {
            var port = server.boundPort();
            var target = folder.getRoot().toPath().resolve("new.portfolio");

            var created = request(port, "POST", "/files",
                            "{\"path\":\"" + target + "\",\"create\":true,\"baseCurrency\":\"CHF\"}", TOKEN);
            assertThat(created.status(), is(201));

            var id = json(created.body()).get("id").getAsString();
            assertThat(id, is(not("")));
            assertThat(target.toFile().isFile(), is(true));

            var listed = json(request(port, "GET", "/files", null, TOKEN).body()).getAsJsonArray("files").get(0)
                            .getAsJsonObject();
            assertThat(listed.get("id").getAsString(), is(id));
            assertThat(listed.get("label").getAsString(), is("new"));

            assertThat(request(port, "DELETE", "/files/" + id, null, TOKEN).status(), is(204));
            assertThat(files.list().size(), is(0));

            // Closing gives the file up; it does not delete it. That is the caller's
            // decision, and the caller is what owns the directory.
            assertThat(target.toFile().isFile(), is(true));
        }
        finally
        {
            server.stop();
            host.shutdown();
            files.releaseLocks();
        }
    }

    @Test
    public void testTheWayAFileFailsToOpenIsDistinguishable() throws Exception
    {
        var files = emptySet();
        var host = new HeadlessHost(files::listOpenFiles);
        files.attach(host);

        var server = server(files, FixedRefresh.succeeded());
        server.start();

        try
        {
            var port = server.boundPort();

            assertThat(request(port, "DELETE", "/files/" + UUID.randomUUID(), null, TOKEN).status(), is(404));
            assertThat(request(port, "POST", "/files", "not json", TOKEN).status(), is(400));
            assertThat(request(port, "POST", "/files", "{}", TOKEN).status(), is(400));
            assertThat(request(port, "POST", "/files", "{\"path\":\"relative.portfolio\"}", TOKEN).status(), is(400));

            // Nothing at that path: 404, as opposed to 422 for something that is a file
            // but not a portfolio file.
            var absent = folder.getRoot().toPath().resolve("absent.portfolio");
            assertThat(request(port, "POST", "/files", "{\"path\":\"" + absent + "\"}", TOKEN).status(), is(404));

            var notAPortfolio = folder.newFile("notes.txt").toPath();
            java.nio.file.Files.writeString(notAPortfolio, "hello");
            assertThat(request(port, "POST", "/files", "{\"path\":\"" + notAPortfolio + "\"}", TOKEN).status(),
                            is(422));
        }
        finally
        {
            server.stop();
            host.shutdown();
            files.releaseLocks();
        }
    }

    /**
     * Re-opening a file the daemon already has is the caller's startup path - it
     * re-asserts its whole list - and it is not a create.
     */
    @Test
    public void testOpeningAnAlreadyOpenFileAnswers200RatherThan201() throws Exception
    {
        var files = emptySet();
        var host = new HeadlessHost(files::listOpenFiles);
        files.attach(host);

        var server = server(files, FixedRefresh.succeeded());
        server.start();

        try
        {
            var port = server.boundPort();
            var target = folder.getRoot().toPath().resolve("existing.portfolio");
            ClientFactory.saveAs(new Client(), target.toFile(), null, ClientFileType.BINARY.getFlags());

            var body = "{\"path\":\"" + target + "\"}";
            assertThat(request(port, "POST", "/files", body, TOKEN).status(), is(201));

            var again = request(port, "POST", "/files", body, TOKEN);
            assertThat(again.status(), is(200));
            assertThat(json(again.body()).get("created").getAsBoolean(), is(false));
            assertThat(files.list().size(), is(1));
        }
        finally
        {
            server.stop();
            host.shutdown();
            files.releaseLocks();
        }
    }

    /**
     * The quote routes are user data (instrument names) and an ability to make the
     * daemon reach the network, so they take the token like the file routes do.
     */
    @Test
    public void testTheQuoteRoutesRefuseWithoutTheToken() throws IOException
    {
        var server = server(emptySet(), FixedRefresh.succeeded());
        server.start();

        try
        {
            var port = server.boundPort();

            assertThat(request(port, "GET", "/quotes", null, null).status(), is(401));
            assertThat(request(port, "POST", "/quotes/refresh", null, "wrong").status(), is(401));
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * The refresh trigger answers 202 and returns immediately - the control server
     * has one thread, and a cycle can take a minute, so the caller polls GET /quotes
     * rather than holding a request open (which would block /health behind it).
     */
    @Test
    public void testARefreshCanBeQueuedAndItsOutcomeRead() throws IOException
    {
        var quotes = new FixedQuotes(Instant.parse("2026-08-04T09:00:00Z"), List.of(
                        new ControlServer.QuoteControl.Failure("uuid-1", "ACME Corp", "no quotes returned")));

        var server = server(emptySet(), FixedRefresh.succeeded(), quotes);
        server.start();

        try
        {
            var port = server.boundPort();

            var status = request(port, "GET", "/quotes", null, TOKEN);
            assertThat(status.status(), is(200));

            var body = json(status.body());
            assertThat(body.get("lastUpdate").getAsString(), is("2026-08-04T09:00:00Z"));
            assertThat(body.get("running").getAsBoolean(), is(false));

            var failure = body.getAsJsonArray("failures").get(0).getAsJsonObject();
            assertThat(failure.get("uuid").getAsString(), is("uuid-1"));
            assertThat(failure.get("name").getAsString(), is("ACME Corp"));
            assertThat(failure.get("reason").getAsString(), is("no quotes returned"));

            var triggered = request(port, "POST", "/quotes/refresh", null, TOKEN);
            assertThat(triggered.status(), is(202));
            assertThat(quotes.requested, is(true));
            assertThat(json(triggered.body()).get("running").getAsBoolean(), is(true));

            assertThat(request(port, "GET", "/quotes/refresh", null, TOKEN).status(), is(405));
            assertThat(request(port, "POST", "/quotes", null, TOKEN).status(), is(405));
        }
        finally
        {
            server.stop();
        }
    }

    private static JsonObject json(String body)
    {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private record HttpResult(int status, String body)
    {
    }

    private static HttpResult request(int port, String method, String path, String body, String token)
                    throws IOException
    {
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        try
        {
            connection.setRequestMethod(method);
            if (token != null)
                connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null)
            {
                connection.setDoOutput(true);
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }

            var status = connection.getResponseCode();
            var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            var text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new HttpResult(status, text);
        }
        finally
        {
            connection.disconnect();
        }
    }
    /**
     * An encrypted file is what a human has to be asked about, so the two answers a
     * caller acts on differently - "it needs a password" and "that password was wrong" -
     * are distinct statuses rather than one 422 with a localized message in it.
     */
    @Test
    public void testAnEncryptedFileIsAnsweredWithItsOwnStatuses() throws Exception
    {
        var files = emptySet();
        var host = new HeadlessHost(files::listOpenFiles);
        files.attach(host);

        var server = server(files, FixedRefresh.succeeded());
        server.start();

        try
        {
            var port = server.boundPort();
            var target = folder.getRoot().toPath().resolve("locked.portfolio");

            var client = new Client();
            client.setBaseCurrency("EUR");
            ClientFactory.saveAs(client, target.toFile(), "hunter2".toCharArray(),
                            ClientFileType.BINARY_AES256.getFlags());

            var withoutPassword = request(port, "POST", "/files", "{\"path\":\"" + target + "\"}", TOKEN);
            assertThat(withoutPassword.status(), is(401));
            assertThat(json(withoutPassword.body()).get("error").getAsString(), is("password-required"));

            var wrong = request(port, "POST", "/files",
                            "{\"path\":\"" + target + "\",\"password\":\"nope\"}", TOKEN);
            assertThat(wrong.status(), is(403));
            assertThat(json(wrong.body()).get("error").getAsString(), is("password-rejected"));

            var unlocked = request(port, "POST", "/files",
                            "{\"path\":\"" + target + "\",\"password\":\"hunter2\"}", TOKEN);
            assertThat(unlocked.status(), is(201));
            assertThat(files.list().size(), is(1));
        }
        finally
        {
            server.stop();
            host.shutdown();
            files.releaseLocks();
        }
    }
}
