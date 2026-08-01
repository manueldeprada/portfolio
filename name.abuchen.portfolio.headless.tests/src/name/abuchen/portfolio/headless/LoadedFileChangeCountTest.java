package name.abuchen.portfolio.headless;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

/**
 * The counter feeds the ETag on every file-scoped read, so it has to mean the same
 * thing here as it does in the desktop's ClientInput: it moves on every model
 * change, and only equality is meaningful.
 */
@SuppressWarnings("nls")
public class LoadedFileChangeCountTest
{
    private static HeadlessHost.LoadedFile file()
    {
        return new HeadlessHost.LoadedFile("/tmp/a.xml", "a", new Client());
    }

    /**
     * A freshly loaded client is itself a change: a consumer holding a counter from
     * before the load must not conclude that nothing happened.
     */
    @Test
    public void testLoadingCountsAsAChange()
    {
        assertThat(file().getChangeCount(), is(greaterThan(0L)));
    }

    @Test
    public void testAModelChangeMovesTheCounter()
    {
        var file = file();
        var before = file.getChangeCount();

        file.getClient().addSecurity(new Security());

        assertThat(file.getChangeCount(), is(greaterThan(before)));
    }

    @Test
    public void testReadingTheCounterDoesNotMoveIt()
    {
        var file = file();

        assertThat(file.getChangeCount(), is(file.getChangeCount()));
    }

    /** Nothing persists yet, so a change leaves the file unsaved. */
    @Test
    public void testAChangeMarksTheFileDirty()
    {
        var file = file();
        assertThat(file.isDirty(), is(false));

        file.getClient().addSecurity(new Security());

        assertThat(file.isDirty(), is(true));
    }
}
