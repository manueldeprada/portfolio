package name.abuchen.portfolio.headless;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Security;

/**
 * Autosave is what makes a write through the API mean anything: phase 1 mutated the
 * in-memory client and lost it on exit.
 * <p>
 * The sweep is driven directly rather than through {@code start()} - a test that
 * waits for a scheduled interval is a test that is either slow or flaky, and
 * {@code shutdown()} sweeps by design so that the last interval of work is not lost.
 */
@SuppressWarnings("nls")
public class AutoSaverTest
{
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private HeadlessHost.LoadedFile fileAt(File target)
    {
        var client = new Client();
        client.setBaseCurrency("EUR");
        return new HeadlessHost.LoadedFile(target.getAbsolutePath(), "a", client);
    }

    /** Sweeps once, synchronously, and cleans up. */
    private void sweep(HeadlessHost host, HeadlessHost.LoadedFile file)
    {
        new AutoSaver(host, List.of(file)).shutdown();
    }

    @Test
    public void testADirtyFileIsWrittenAndReadsBack() throws Exception
    {
        var target = new File(folder.getRoot(), "portfolio.xml");
        var file = fileAt(target);
        var host = new HeadlessHost(List.of(file));

        try
        {
            var security = new Security();
            security.setName("Test Instrument");
            file.getClient().addSecurity(security);
            assertThat(file.isDirty(), is(true));

            sweep(host, file);

            assertThat(file.isDirty(), is(false));
            assertThat(file.getSavedAt().isPresent(), is(true));

            var reloaded = ClientFactory.load(target, null, new NullProgressMonitor());
            assertThat(reloaded.getSecurities().size(), is(1));
            assertThat(reloaded.getSecurities().get(0).getName(), is("Test Instrument"));
        }
        finally
        {
            host.shutdown();
        }
    }

    /**
     * A file nothing has written to must not be rewritten: the daemon shares the file
     * with whoever else edits it, and rewriting it would change its timestamp - and
     * on an XML file, its formatting - for no reason.
     */
    @Test
    public void testACleanFileIsNotWritten() throws Exception
    {
        var target = new File(folder.getRoot(), "untouched.xml");
        var file = fileAt(target);
        var host = new HeadlessHost(List.of(file));

        try
        {
            sweep(host, file);

            assertThat(target.exists(), is(false));
        }
        finally
        {
            host.shutdown();
        }
    }

    /**
     * A save that cannot happen - a read-only volume, a path that is no longer a file -
     * must leave the file dirty so the next sweep retries, and must say why, because
     * /health is the only place an operator would ever see it.
     */
    @Test
    public void testAFailedSaveLeavesTheFileDirtyAndIsReported() throws Exception
    {
        // A directory where the file should be: FileOutputStream cannot open it.
        var target = folder.newFolder("not-a-file.xml");
        var file = fileAt(target);
        var host = new HeadlessHost(List.of(file));

        try
        {
            file.getClient().addSecurity(new Security());

            sweep(host, file);

            assertThat(file.isDirty(), is(true));
            assertThat(file.getSaveError().isPresent(), is(true));
            assertThat(file.getSavedAt().isPresent(), is(false));
        }
        finally
        {
            host.shutdown();
        }
    }

    /**
     * The backup is taken before the daemon writes anything, and keeps the extension so
     * it opens like any other portfolio file.
     */
    @Test
    public void testTheStartupBackupKeepsTheExtension() throws IOException
    {
        var target = folder.newFile("portfolio.xml");
        Files.write(target.toPath(), "original".getBytes(StandardCharsets.UTF_8));

        AutoSaver.backup(List.of(fileAt(target)));

        var backup = new File(folder.getRoot(), "portfolio.backup-after-open.xml");
        assertThat(backup.exists(), is(true));
        assertThat(Files.readString(backup.toPath()), is("original"));
    }

    /** A missing file is a misconfiguration to report, not a reason to fail startup. */
    @Test
    public void testABackupOfAMissingFileIsSurvived()
    {
        AutoSaver.backup(List.of(fileAt(new File(folder.getRoot(), "absent.xml"))));

        assertThat(folder.getRoot().list(), is(notNullValue()));
    }
}
