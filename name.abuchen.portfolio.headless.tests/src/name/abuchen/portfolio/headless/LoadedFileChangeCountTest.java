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
 * <p>
 * Dirtiness is derived from the same counter rather than being a flag of its own,
 * which is what makes "changed while the save was running" resolve to still-dirty.
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

    /** But not a change that needs saving - it is what is on disk. */
    @Test
    public void testAFreshlyLoadedFileIsNotDirty()
    {
        assertThat(file().isDirty(), is(false));
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

    @Test
    public void testAChangeMarksTheFileDirty()
    {
        var file = file();
        assertThat(file.isDirty(), is(false));

        file.getClient().addSecurity(new Security());

        assertThat(file.isDirty(), is(true));
    }

    /**
     * A touch is a change that needs no recalculation, not a change that needs no
     * saving - the desktop marks the file dirty for it too.
     */
    @Test
    public void testATouchAlsoMarksTheFileDirty()
    {
        var file = file();

        file.getClient().touch();

        assertThat(file.isDirty(), is(true));
    }

    @Test
    public void testASuccessfulSaveClearsTheDirtyFlag()
    {
        var file = file();
        file.getClient().addSecurity(new Security());

        file.markSaved(file.saveMark());

        assertThat(file.isDirty(), is(false));
        assertThat(file.getSavedAt().isPresent(), is(true));
    }

    /**
     * The mark is read before the file is written, so an edit that lands during the
     * write is not covered by what was written and the file has to stay dirty. Getting
     * this wrong loses exactly one edit, silently.
     */
    @Test
    public void testAChangeDuringTheSaveLeavesTheFileDirty()
    {
        var file = file();
        file.getClient().addSecurity(new Security());

        var mark = file.saveMark();
        file.getClient().addSecurity(new Security());
        file.markSaved(mark);

        assertThat(file.isDirty(), is(true));
    }

    /** A failed save must not look like a successful one on the next sweep. */
    @Test
    public void testAFailedSaveLeavesTheFileDirtyAndRecordsWhy()
    {
        var file = file();
        file.getClient().addSecurity(new Security());

        file.markSaveFailed("read-only file system");

        assertThat(file.isDirty(), is(true));
        assertThat(file.getSaveError().orElse(null), is("read-only file system"));
    }

    /**
     * New rates change every converted figure in every cached response, so the ETag
     * has to change - but nothing in the file did, so saving it would be pointless
     * churn on a file the daemon may not have been asked to rewrite at all.
     */
    @Test
    public void testAnExchangeRateUpdateMovesTheCounterWithoutMakingTheFileDirty()
    {
        var file = file();
        var before = file.getChangeCount();

        file.onExchangeRatesUpdated();

        assertThat(file.getChangeCount(), is(greaterThan(before)));
        assertThat(file.isDirty(), is(false));
    }
}
