package name.abuchen.portfolio.headless;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

/**
 * The health endpoint is what a container probe or a supervisor sees, so the two
 * things worth pinning down are that it answers 200 while the process serves - being
 * offline is not a reason to restart a daemon that answers every request correctly -
 * and that "no exchange rate has ever been downloaded" shows up, since that is the one
 * state in which the API's converted figures are wrong rather than merely stale.
 */
@SuppressWarnings("nls")
public class HealthServerTest
{
    private static final class FixedRefresh implements HealthServer.Refresh
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

    private static HeadlessHost.LoadedFile file()
    {
        return new HeadlessHost.LoadedFile("/tmp/a.xml", "a", new Client());
    }

    private static HealthServer server(HeadlessHost.LoadedFile file, HealthServer.Refresh rates)
    {
        return new HealthServer(0, 5712, List.of(file), rates, FixedRefresh.never());
    }

    @Test
    public void testRatesThatHaveNeverBeenDownloadedAreDegraded()
    {
        var report = server(file(), FixedRefresh.never()).report();

        assertThat(report.get("status").getAsString(), is("degraded"));
        assertThat(report.get("exchangeRates").getAsJsonObject().get("lastUpdate").isJsonNull(), is(true));
    }

    @Test
    public void testAFreshDaemonWithCurrentRatesIsOk()
    {
        var report = server(file(), FixedRefresh.succeeded()).report();

        assertThat(report.get("status").getAsString(), is("ok"));
        assertThat(report.get("apiPort").getAsInt(), is(5712));
        assertThat(report.get("exchangeRates").getAsJsonObject().get("lastUpdate").getAsString(),
                        is("2026-08-01T12:00:00Z"));
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

        var report = server(file, FixedRefresh.succeeded()).report();

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

        var report = server(file, FixedRefresh.succeeded()).report();

        // Dirty is the normal state between two sweeps, so it is reported but does not
        // make the daemon degraded - only a save that actually failed does.
        assertThat(report.get("status").getAsString(), is("ok"));
        assertThat(report.get("files").getAsJsonArray().get(0).getAsJsonObject().get("dirty").getAsBoolean(), is(true));
    }

    @Test
    public void testItAnswersOverHttpAndOnlyOnItsOwnPath() throws IOException
    {
        var server = new HealthServer(0, 5712, List.of(file()), FixedRefresh.never(), FixedRefresh.never());
        server.start();

        try
        {
            var port = server.boundPort();

            // 200 even though the rates are missing: the process is serving, which is
            // what a liveness probe asks about.
            var health = get(port, "/health");
            assertThat(health.status(), is(200));
            assertThat(health.body(), containsString("\"status\":\"degraded\""));

            assertThat(get(port, "/").status(), is(404));
            assertThat(get(port, "/v1/files").status(), is(404));
        }
        finally
        {
            server.stop();
        }
    }

    private record HttpResult(int status, String body)
    {
    }

    private static HttpResult get(int port, String path) throws IOException
    {
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        try
        {
            var status = connection.getResponseCode();
            var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            var body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new HttpResult(status, body);
        }
        finally
        {
            connection.disconnect();
        }
    }
}
