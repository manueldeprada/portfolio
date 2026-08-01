package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.ClientStore;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.PairingService;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

/**
 * The file listing is the only place a client can learn that the model moved
 * without asking for the data itself, so it must report the counter and the
 * unsaved-changes flag of every file it lists.
 */
@SuppressWarnings("nls")
public class ChangeCounterTest
{
    private static final String PATH = "/tmp/x.portfolio";

    private IEclipsePreferences node;
    private FakeHost.FakeOpenFile file;
    private Router router;

    @Before
    public void setUp()
    {
        node = InstanceScope.INSTANCE.getNode("rest-test-" + UUID.randomUUID());
        var registry = new FileAccessRegistry(node);
        registry.setEnabled(PATH, true);

        file = new FakeHost.FakeOpenFile(PATH, "x", new Client());
        var host = new FakeHost(List.of(file));
        router = ApiRoutes.create(registry, host,
                        new PairingService(new ClientStore(Path.of("target", "unused-client-store")), host));
    }

    @After
    public void tearDown() throws Exception
    {
        node.removeNode();
    }

    private JsonObject listFiles() throws Exception
    {
        var match = router.match("GET", "/v1/files");
        var response = match.handler()
                        .handle(new Request("GET", "/v1/files", match.pathParams(), Map.of(), new byte[0]));
        return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject() //
                        .get("items").getAsJsonArray().get(0).getAsJsonObject();
    }

    @Test
    public void testFileListingReportsChangeIdAndDirty() throws Exception
    {
        var item = listFiles();
        assertThat(item.get("changeId").getAsLong(), is(0L));
        assertThat(item.get("dirty").getAsBoolean(), is(false));
    }

    @Test
    public void testChangeIdAndDirtyFollowTheFile() throws Exception
    {
        var before = listFiles().get("changeId").getAsLong();

        file.bumpChangeCount();
        file.setDirty(true);

        var item = listFiles();
        assertThat(item.get("changeId").getAsLong(), is(before + 1));
        assertThat(item.get("dirty").getAsBoolean(), is(true));
    }
}
