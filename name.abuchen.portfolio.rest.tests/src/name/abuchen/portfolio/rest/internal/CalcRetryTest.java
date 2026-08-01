package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.ClientStore;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.PairingService;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

/**
 * A calculation runs off the UI thread, so the user can edit the file while it
 * is running. The route samples the change counter around the computation and
 * redoes it when the file moved; the number of samples is what makes that
 * visible from outside.
 */
@SuppressWarnings("nls")
public class CalcRetryTest
{
    private static final String PATH = "/tmp/x.portfolio";

    private IEclipsePreferences node;
    private FakeHost.FakeOpenFile file;
    private Router router;
    private String fileId;

    @Before
    public void setUp()
    {
        var client = new Client();
        new SecurityBuilder().addTo(client);

        node = InstanceScope.INSTANCE.getNode("rest-test-" + UUID.randomUUID());
        var registry = new FileAccessRegistry(node);
        registry.setEnabled(PATH, true);
        fileId = registry.byPath(PATH).orElseThrow().uuid();

        file = new FakeHost.FakeOpenFile(PATH, "x", client);
        var host = new FakeHost(List.of(file));
        router = ApiRoutes.create(registry, host,
                        new PairingService(new ClientStore(Path.of("target", "unused-client-store")), host));
    }

    @After
    public void tearDown() throws Exception
    {
        node.removeNode();
    }

    private Response holdings() throws Exception
    {
        var path = "/v1/files/" + fileId + "/holdings";
        var match = router.match("GET", path);
        return match.handler().handle(new Request("GET", path, match.pathParams(), Map.of(), Map.of(), new byte[0]));
    }

    /** the baseline: one sample before, one after, and no repeat */
    @Test
    public void testAQuietFileIsComputedOnce() throws Exception
    {
        var response = holdings();

        assertThat(file.changeCountReads(), is(2));
        assertThat(response.status(), is(200));
        assertThat(response.headers().get(ConditionalGet.ETAG), is(notNullValue()));
    }

    @Test
    public void testACalculationIsRedoneWhenTheFileMovedWhileItRan() throws Exception
    {
        file.bumpChangeCountAfterNextReads(1);

        var response = holdings();

        // before, after (moved), after the repeat (settled)
        assertThat(file.changeCountReads(), is(3));
        assertThat(response.status(), is(200));
        assertThat(response.headers().get(ConditionalGet.ETAG), is(notNullValue()));
    }

    /**
     * A file that never settles still gets an answer - but one that may straddle
     * an edit, so it carries no validator and must not be cached.
     */
    @Test
    public void testAFileThatKeepsMovingIsServedWithoutAnETag() throws Exception
    {
        file.bumpChangeCountAfterNextReads(100);

        var response = holdings();

        // one sample before plus one after each of the three attempts
        assertThat(file.changeCountReads(), is(4));
        assertThat(response.status(), is(200));
        assertThat(response.headers().get(ConditionalGet.ETAG), is(nullValue()));
    }

    /** the retry is a property of the calculation routes, not of every read */
    @Test
    public void testAUIThreadReadSamplesTheCounterOnlyOnce() throws Exception
    {
        var path = "/v1/files/" + fileId + "/instruments";
        var match = router.match("GET", path);
        match.handler().handle(new Request("GET", path, match.pathParams(), Map.of(), Map.of(), new byte[0]));

        assertThat(file.changeCountReads(), is(1));
    }
}
