package name.abuchen.portfolio.headless;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@SuppressWarnings("nls")
public class PortfolioFileLockTest
{
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path portfolio() throws IOException
    {
        return folder.newFile("portfolio.xml").toPath();
    }

    @Test
    public void testTheLockSitsBesideTheFile() throws IOException
    {
        var file = portfolio();

        assertThat(PortfolioFileLock.lockFileFor(file), is(file.resolveSibling("portfolio.xml.lock")));
    }

    @Test
    public void testAcquiringCreatesTheLockFileAndReleasingLeavesIt() throws IOException
    {
        var file = portfolio();

        try (var lock = PortfolioFileLock.acquire(file, 5712))
        {
            assertThat(Files.exists(lock.lockFile()), is(true));
            // Named so a refusal can say who holds it.
            var contents = Files.readString(lock.lockFile());
            assertThat(contents, containsString("pid="));
            assertThat(contents, containsString("port=5712"));
        }

        // The file stays; the *lock* is what was released, and an OS lock cannot go
        // stale the way a leftover PID file would.
        assertThat(Files.exists(PortfolioFileLock.lockFileFor(file)), is(true));
    }

    /**
     * Within one JVM the second attempt is an overlapping lock, which is only ever a
     * configuration mistake - the same file listed twice.
     */
    @Test
    public void testTheSameFileTwiceInOneProcessIsRejected() throws IOException
    {
        var file = portfolio();

        try (var first = PortfolioFileLock.acquire(file, 5712))
        {
            PortfolioFileLock.acquire(file, 5712);
            Assert.fail("expected the second claim to be refused");
        }
        catch (PortfolioFileLock.FileInUseException e)
        {
            assertThat(e.getMessage(), containsString("listed more than once"));
        }
    }

    @Test
    public void testTheFileCanBeClaimedAgainAfterRelease() throws IOException
    {
        var file = portfolio();

        try (var first = PortfolioFileLock.acquire(file, 5712))
        {
            assertThat(first.lockFile().toFile().exists(), is(true));
        }

        try (var second = PortfolioFileLock.acquire(file, 5712))
        {
            assertThat(second.lockFile().toFile().exists(), is(true));
        }
    }

    @Test
    public void testDifferentFilesDoNotBlockEachOther() throws IOException
    {
        var one = folder.newFile("a.xml").toPath();
        var two = folder.newFile("b.xml").toPath();

        try (var first = PortfolioFileLock.acquire(one, 5712); var second = PortfolioFileLock.acquire(two, 5712))
        {
            assertThat(first.lockFile(), is(one.resolveSibling("a.xml.lock")));
            assertThat(second.lockFile(), is(two.resolveSibling("b.xml.lock")));
        }
    }

    @Test
    public void testReleaseAllIsSafeOnAnEmptyList()
    {
        PortfolioFileLock.releaseAll(java.util.List.of());
    }
}
