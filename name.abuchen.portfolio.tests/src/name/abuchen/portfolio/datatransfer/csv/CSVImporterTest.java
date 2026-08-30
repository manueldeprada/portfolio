package name.abuchen.portfolio.datatransfer.csv;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.model.Client;

/**
 * Reading a ragged file, which is the everyday shape rather than a corner case:
 * commons-csv gives every record its own arity, so a data row that simply omits
 * its trailing empty columns is shorter than the header row that named them.
 */
@SuppressWarnings("nls")
public class CSVImporterTest
{
    private Path file;

    @Before
    public void setUp() throws IOException
    {
        file = Files.createTempFile("pp-csv-importer-test-", ".csv");
    }

    @After
    public void tearDown() throws IOException
    {
        Files.deleteIfExists(file);
    }

    private CSVImporter importerFor(String content) throws IOException
    {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        var importer = new CSVImporter(new Client(), file.toFile());
        importer.setDelimiter(';');
        importer.setEncoding(StandardCharsets.UTF_8);
        return importer;
    }

    @Test
    public void testARowShorterThanTheHeaderIsRead() throws IOException
    {
        // the second row stops before the value column, which auto-detection
        // samples for its format guess
        var importer = importerFor(Messages.CSVColumn_Date + ";" + Messages.CSVColumn_Value + "\n" //
                        + "2026-08-24\n" //
                        + "2026-08-25;100.00\n");

        importer.processFile(true);

        var columns = importer.getColumns();
        assertThat(columns.length, is(2));
        assertThat(importer.getFirstNonEmptyValue(columns[1]), is("100.00"));
    }

    @Test
    public void testAColumnNoRowEverReachesHasNoValue() throws IOException
    {
        var importer = importerFor(Messages.CSVColumn_Date + ";" + Messages.CSVColumn_Value + "\n" //
                        + "2026-08-24\n");

        importer.processFile(true);

        assertThat(importer.getFirstNonEmptyValue(importer.getColumns()[1]), is(nullValue()));
    }
}
