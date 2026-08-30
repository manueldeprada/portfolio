package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.ClientStore;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.PairingService;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

/**
 * Which routes of the real routing table take an upload-sized body. The 1 MiB
 * default is about JSON documents a client composes; the two import routes have
 * a bank's own export as their body, and a year of statements legitimately runs
 * past that.
 */
@SuppressWarnings("nls")
public class RequestBodyLimitTest
{
    private static final String PATH = "/tmp/x.portfolio";

    private IEclipsePreferences node;
    private Router router;
    private String fileId;

    @Before
    public void setUp()
    {
        node = InstanceScope.INSTANCE.getNode("rest-test-" + UUID.randomUUID());
        var registry = new FileAccessRegistry(node);
        registry.setEnabled(PATH, true);
        fileId = registry.byPath(PATH).orElseThrow().uuid();

        var host = new FakeHost(List.of(new FakeHost.FakeOpenFile(PATH, "x", new Client())));
        router = ApiRoutes.create(registry, host,
                        new PairingService(new ClientStore(Path.of("target", "unused-client-store")), host));
    }

    @After
    public void tearDown() throws Exception
    {
        node.removeNode();
    }

    private int limitOf(String method, String path)
    {
        return router.match(method, "/v1/files/" + fileId + path).maxRequestBody();
    }

    @Test
    public void testTheImportUploadRoutesTakeAnUploadSizedBody()
    {
        assertThat(limitOf("POST", "/imports"), is(Router.MAX_UPLOAD_BODY));
        assertThat(limitOf("POST", "/imports/csv-preview"), is(Router.MAX_UPLOAD_BODY));
    }

    @Test
    public void testEveryOtherRouteKeepsTheJsonDefault()
    {
        // including the third import route, whose body is the JSON review of a
        // session rather than a file
        assertThat(limitOf("POST", "/imports/some-session-id"), is(Router.DEFAULT_MAX_REQUEST_BODY));
        assertThat(limitOf("GET", "/imports/configs"), is(Router.DEFAULT_MAX_REQUEST_BODY));
        assertThat(limitOf("POST", "/transactions"), is(Router.DEFAULT_MAX_REQUEST_BODY));
        assertThat(limitOf("POST", "/accounts"), is(Router.DEFAULT_MAX_REQUEST_BODY));
        assertThat(limitOf("GET", "/holdings"), is(Router.DEFAULT_MAX_REQUEST_BODY));
    }
}
