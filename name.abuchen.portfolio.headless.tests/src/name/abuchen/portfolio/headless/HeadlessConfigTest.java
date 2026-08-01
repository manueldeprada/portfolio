package name.abuchen.portfolio.headless;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@SuppressWarnings("nls")
public class HeadlessConfigTest
{
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private int counter;

    private HeadlessConfig read(String json) throws IOException
    {
        // A fresh name per call: a test that asserts two rejections would otherwise
        // fail on the second newFile, and its IOException would look like the
        // rejection under test.
        var file = folder.newFile("headless-" + counter++ + ".json").toPath();
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return HeadlessConfig.read(file);
    }

    @Test
    public void testAMinimalConfigTakesTheDefaultPort() throws IOException
    {
        var config = read("{\"files\":[{\"path\":\"/tmp/a.xml\"}]}");

        assertThat(config.port(), is(HeadlessConfig.DEFAULT_PORT));
        assertThat(config.files().size(), is(1));
        assertThat(config.files().get(0).alias(), is(nullValue()));
        assertThat(config.clients().isEmpty(), is(true));
    }

    @Test
    public void testEveryFieldIsRead() throws IOException
    {
        var config = read("{\"port\":6000,\"files\":[{\"path\":\"/tmp/a.xml\",\"alias\":\"main\","
                        + "\"passwordEnv\":\"PP_PW\"}],\"clients\":[\"webapp\"]}");

        assertThat(config.port(), is(6000));
        assertThat(config.files().get(0).alias(), is("main"));
        assertThat(config.files().get(0).passwordEnv(), is("PP_PW"));
        assertThat(config.clients(), is(java.util.List.of("webapp")));
    }

    /**
     * The path is the API's identity key and is compared literally against the open
     * files, so it is absolute and normalized before anything sees it.
     */
    @Test
    public void testThePathIsMadeAbsoluteAndNormalized() throws IOException
    {
        var config = read("{\"files\":[{\"path\":\"/tmp/./sub/../a.xml\"}]}");

        assertThat(config.files().get(0).path(), is(Path.of("/tmp/a.xml")));
        assertThat(config.files().get(0).path().isAbsolute(), is(true));
    }

    /** A daemon with nothing to serve is a configuration mistake, not a valid state. */
    @Test
    public void testAConfigWithoutFilesIsRejected()
    {
        assertRejected("{\"files\":[]}", "serve nothing");
        assertRejected("{}", "serve nothing");
    }

    @Test
    public void testAFileEntryWithoutAPathIsRejected()
    {
        assertRejected("{\"files\":[{\"alias\":\"main\"}]}", "needs a \"path\"");
    }

    @Test
    public void testAnOutOfRangePortIsRejected()
    {
        assertRejected("{\"port\":70000,\"files\":[{\"path\":\"/tmp/a.xml\"}]}", "between 0 and 65535");
    }

    @Test
    public void testMalformedJsonIsRejectedWithTheFileName()
    {
        assertRejected("{ not json", "is not valid JSON");
    }

    @Test
    public void testANonObjectDocumentIsRejected()
    {
        assertRejected("[]", "expected a JSON object");
    }

    private void assertRejected(String json, String expectedMessage)
    {
        try
        {
            read(json);
            Assert.fail("expected an IOException for: " + json);
        }
        catch (IOException e)
        {
            assertThat(e.getMessage(), containsString(expectedMessage));
        }
    }
}
