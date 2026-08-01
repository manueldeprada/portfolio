package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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
 * The caching contract of the file-scoped reads: a validator that is stable
 * while nothing moves, changes when the model or the question changes, and that
 * the server accepts back as {@code If-None-Match}.
 */
@SuppressWarnings("nls")
public class ConditionalGetTest
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

    private Response get(String path, Map<String, String> query, Map<String, String> headers) throws Exception
    {
        var match = router.match("GET", path);
        return match.handler().handle(new Request("GET", path, match.pathParams(), query, headers, new byte[0]));
    }

    private Response get(String path) throws Exception
    {
        return get(path, Map.of(), Map.of());
    }

    private String instruments()
    {
        return "/v1/files/" + fileId + "/instruments";
    }

    private String holdings()
    {
        return "/v1/files/" + fileId + "/holdings";
    }

    private static String etagOf(Response response)
    {
        return response.headers().get(ConditionalGet.ETAG);
    }

    @Test
    public void testReadAnswersWithAStrongETag() throws Exception
    {
        var etag = etagOf(get(instruments()));

        assertThat(etag, is(notNullValue()));
        assertThat(etag.startsWith("\"") && etag.endsWith("\""), is(true));
    }

    @Test
    public void testTheSameRequestYieldsTheSameETag() throws Exception
    {
        assertThat(etagOf(get(instruments())), is(etagOf(get(instruments()))));
    }

    @Test
    public void testAChangedModelYieldsADifferentETag() throws Exception
    {
        var before = etagOf(get(instruments()));

        file.bumpChangeCount();

        assertThat(etagOf(get(instruments())), is(not(before)));
    }

    @Test
    public void testDifferentQueryParametersYieldDifferentETags() throws Exception
    {
        var one = etagOf(get(holdings(), Map.of("date", "2024-06-30"), Map.of()));
        var other = etagOf(get(holdings(), Map.of("date", "2024-12-31"), Map.of()));

        assertThat(one, is(not(other)));
    }

    /** the same question asked with its parameters in the other order is the same question */
    @Test
    public void testQueryParameterOrderDoesNotChangeTheETag() throws Exception
    {
        var one = etagOf(get(holdings(), Map.of("date", "2024-06-30", "currency", "EUR"), Map.of()));
        var other = etagOf(get(holdings(), Map.of("currency", "EUR", "date", "2024-06-30"), Map.of()));

        assertThat(one, is(other));
    }

    @Test
    public void testTwoRoutesOfTheSameFileYieldDifferentETags() throws Exception
    {
        assertThat(etagOf(get(instruments())), is(not(etagOf(get(holdings())))));
    }

    @Test
    public void testMatchingIfNoneMatchIsAnEmpty304() throws Exception
    {
        var etag = etagOf(get(instruments()));

        var response = get(instruments(), Map.of(), Map.of("If-None-Match", etag));

        assertThat(response.status(), is(304));
        assertThat(response.body().length, is(0));
        assertThat(etagOf(response), is(etag));
    }

    @Test
    public void testMatchingIfNoneMatchIsAnEmpty304OnACalculationRoute() throws Exception
    {
        var etag = etagOf(get(holdings()));

        var response = get(holdings(), Map.of(), Map.of("If-None-Match", etag));

        assertThat(response.status(), is(304));
        assertThat(response.body().length, is(0));
    }

    @Test
    public void testHeaderLookupIsCaseInsensitive() throws Exception
    {
        var etag = etagOf(get(instruments()));

        assertThat(get(instruments(), Map.of(), Map.of("if-none-match", etag)).status(), is(304));
    }

    @Test
    public void testANonMatchingIfNoneMatchIsServedInFull() throws Exception
    {
        var response = get(instruments(), Map.of(), Map.of("If-None-Match", "\"0123456789abcdef\""));

        assertThat(response.status(), is(200));
        assertThat(response.body().length > 0, is(true));
    }

    /** a stale tag stays stale: the model moved, so the client must be told */
    @Test
    public void testAnETagStopsMatchingAfterTheModelMoved() throws Exception
    {
        var etag = etagOf(get(instruments()));

        file.bumpChangeCount();

        assertThat(get(instruments(), Map.of(), Map.of("If-None-Match", etag)).status(), is(200));
    }

    @Test
    public void testAListOfTagsMatchesIfAnyDoes() throws Exception
    {
        var etag = etagOf(get(instruments()));

        var header = "\"deadbeef\", W/\"cafe\", " + etag;

        assertThat(get(instruments(), Map.of(), Map.of("If-None-Match", header)).status(), is(304));
    }

    @Test
    public void testTheWildcardMatchesAnything() throws Exception
    {
        assertThat(get(instruments(), Map.of(), Map.of("If-None-Match", "*")).status(), is(304));
    }

    /**
     * The validator is derived from a file's change counter, so a route that has
     * no file to derive it from must not pretend to have one.
     */
    @Test
    public void testRoutesWithoutAFileScopeCarryNoETag() throws Exception
    {
        var match = router.match("GET", "/v1/openapi.yaml");
        var response = match.handler().handle(new Request("GET", "/v1/openapi.yaml", Map.of(), new byte[0]));

        assertThat(etagOf(response), is(nullValue()));
    }
}
