package name.abuchen.portfolio.datatransfer.csv;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.junit.Test;

import name.abuchen.portfolio.model.Client;

@SuppressWarnings("nls")
public class CSVConfigTest
{
    private static JSONObject json(String encoding) throws ParseException
    {
        return (JSONObject) JSONValue.parseWithException("""
                        {
                          "label": "Test",
                          "target": "portfolio",
                          "delimiter": ";",
                          "encoding": "%s",
                          "skipLines": 0,
                          "isFirstLineHeader": true,
                          "columns": [{"label": "Name", "field": "name"}]
                        }
                        """.formatted(encoding));
    }

    @Test
    public void testAKnownEncodingIsRead() throws ParseException
    {
        var config = new CSVConfig();
        config.fromJSON(json("ISO-8859-1"));

        var importer = new CSVImporter(new Client(), new File("unused.csv"));
        config.writeTo(importer);

        assertThat(importer.getEncoding(), is(StandardCharsets.ISO_8859_1));
    }

    /**
     * An unknown charset used to fall back to the platform default without a
     * word. Reading a file with the wrong charset does not fail - it produces
     * mojibake - so the substitution was invisible all the way through.
     */
    @Test(expected = UnsupportedCharsetException.class)
    public void testAnEncodingThisJvmDoesNotHaveIsRejected() throws ParseException
    {
        new CSVConfig().fromJSON(json("NO-SUCH-CHARSET-42"));
    }
}
