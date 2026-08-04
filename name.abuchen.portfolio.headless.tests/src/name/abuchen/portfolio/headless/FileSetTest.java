package name.abuchen.portfolio.headless;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.ClientFileType;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.rest.FileAccessRegistry;

/**
 * The set of open files is what the daemon serves, and it is now mutable - so the
 * properties worth pinning down are the ones a caller managing it depends on: an open
 * is idempotent, a created file is a file that can be loaded again, a copy carries
 * unsaved work across, a close persists and releases, and the file lock follows the
 * set rather than the process.
 */
@SuppressWarnings("nls")
public class FileSetTest
{
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FileSet files;
    private HeadlessHost host;

    @Before
    public void setUp()
    {
        var node = InstanceScope.INSTANCE.getNode("headless-fileset-" + UUID.randomUUID());
        files = new FileSet(new FileAccessRegistry(node), 5712, false);
        host = new HeadlessHost(files::listOpenFiles);
        files.attach(host);
    }

    @After
    public void tearDown()
    {
        files.releaseLocks();
        host.shutdown();
    }

    /**
     * A security as a loaded file has them. {@code updatedAt} matters: the protobuf
     * writer reads it unconditionally, so a hand-built security with none makes any
     * save of the file it is in fail - which is a property of the model, not of
     * anything here, and every security that came off disk or through the API has one.
     */
    private static Security security(String name)
    {
        var security = new Security();
        security.setName(name);
        security.setUpdatedAt(java.time.Instant.now());
        return security;
    }

    private Path path(String name)
    {
        return folder.getRoot().toPath().resolve(name);
    }

    private Path existingFile(String name) throws IOException
    {
        var target = path(name);
        var client = new Client();
        client.setBaseCurrency("EUR");
        ClientFactory.saveAs(client, target.toFile(), null, ClientFileType.BINARY.getFlags());
        return target;
    }

    @Test
    public void testACreatedFileCanBeLoadedAgainAndCarriesItsBaseCurrency() throws Exception
    {
        var opened = files.create(path("fresh.portfolio"), "CHF");

        assertThat(opened.outcome(), is(FileSet.Outcome.OPENED));
        assertThat(opened.file().getLabel(), is("fresh"));
        assertThat(opened.file().getClient().getBaseCurrency(), is("CHF"));

        var reloaded = ClientFactory.load(path("fresh.portfolio").toFile(), null, new NullProgressMonitor());
        assertThat(reloaded.getBaseCurrency(), is("CHF"));
    }

    @Test
    public void testCreatingOverAnExistingFileIsRefused() throws Exception
    {
        files.create(path("once.portfolio"), "EUR");

        try
        {
            files.create(path("once.portfolio"), "EUR");
            Assert.fail("expected the second create to be refused");
        }
        catch (java.nio.file.FileAlreadyExistsException e)
        {
            // The caller names the path, so overwriting one that exists would destroy a
            // file on a request that reads like a create.
        }
    }

    @Test
    public void testAnUnknownCurrencyIsRefusedRatherThanStored() throws Exception
    {
        try
        {
            files.create(path("bad.portfolio"), "NOTACURRENCY");
            Assert.fail("expected an unknown currency to be refused");
        }
        catch (IOException e)
        {
            assertThat(Files.exists(path("bad.portfolio")), is(false));
        }
    }

    /** The caller re-asserts its whole list on every start; a second open must be free. */
    @Test
    public void testOpeningTwiceReturnsTheSameFileRatherThanASecondCopy() throws Exception
    {
        var target = existingFile("twice.portfolio");

        var first = files.open(target, null);
        var second = files.open(target, null);

        assertThat(second.outcome(), is(FileSet.Outcome.ALREADY_OPEN));
        assertThat(second.id(), is(first.id()));
        assertThat(second.file() == first.file(), is(true));
        assertThat(files.list().size(), is(1));
    }

    @Test
    public void testAnIdAddressesTheFileAndSurvivesNothingElse() throws Exception
    {
        var opened = files.open(existingFile("addressed.portfolio"), null);

        assertThat(files.byId(opened.id()).isPresent(), is(true));
        assertThat(files.byId(UUID.randomUUID().toString()).isPresent(), is(false));
        assertThat(files.idOf(opened.file()).orElse(null), is(opened.id()));
    }

    /**
     * A copy is written from the live model, so unsaved work is part of what is
     * duplicated - which is the whole difference between this and a byte copy of the
     * file on disk.
     */
    @Test
    public void testACopyCarriesUnsavedChanges() throws Exception
    {
        var source = files.open(existingFile("source.portfolio"), null);

        source.file().getClient().addSecurity(security("Only In Memory"));
        assertThat(source.file().isDirty(), is(true));

        var copy = files.copy(source.id(), path("copy.portfolio"), null);

        assertThat(copy.id(), is(not(source.id())));
        assertThat(copy.file().getClient().getSecurities().size(), is(1));
        assertThat(copy.file().getClient().getSecurities().get(0).getName(), is("Only In Memory"));

        // The original is untouched by having been copied, including its own dirtiness:
        // exportAs, not saveAs, precisely so that nothing about the source changes.
        assertThat(source.file().isDirty(), is(true));
    }

    @Test
    public void testCopyingAnUnknownFileIsRefused() throws Exception
    {
        try
        {
            files.copy(UUID.randomUUID().toString(), path("nowhere.portfolio"), null);
            Assert.fail("expected an unknown source to be refused");
        }
        catch (java.nio.file.NoSuchFileException e)
        {
            assertThat(Files.exists(path("nowhere.portfolio")), is(false));
        }
    }

    @Test
    public void testClosingSavesUnlessTheCallerSaysNot() throws Exception
    {
        var opened = files.open(existingFile("closing.portfolio"), null);
        opened.file().getClient().addSecurity(security("Saved On Close"));

        files.close(opened.id(), true);

        assertThat(files.list().size(), is(0));

        var reloaded = ClientFactory.load(path("closing.portfolio").toFile(), null, new NullProgressMonitor());
        assertThat(reloaded.getSecurities().size(), is(1));
    }

    @Test
    public void testClosingWithoutSavingLeavesTheFileAsItWas() throws Exception
    {
        var opened = files.open(existingFile("discarded.portfolio"), null);
        opened.file().getClient().addSecurity(security("Discarded"));

        files.close(opened.id(), false);

        var reloaded = ClientFactory.load(path("discarded.portfolio").toFile(), null, new NullProgressMonitor());
        assertThat(reloaded.getSecurities().size(), is(0));
    }

    /**
     * The lock is what enforces one owner per file, and it has to follow the set: a
     * file given up must be claimable again, by this process or another.
     */
    @Test
    public void testTheLockIsReleasedOnCloseSoTheFileCanBeOpenedAgain() throws Exception
    {
        var target = existingFile("relockable.portfolio");
        var first = files.open(target, null);

        try
        {
            PortfolioFileLock.acquire(target, 5712);
            Assert.fail("expected the open file to be locked");
        }
        catch (PortfolioFileLock.FileInUseException e)
        {
            // Expected: this process holds it.
        }

        files.close(first.id(), false);

        try (var claimed = PortfolioFileLock.acquire(target, 5712))
        {
            assertThat(Files.exists(claimed.lockFile()), is(true));
        }

        // And the daemon can take it back afterwards, with a new id: the record went
        // with the close, which is what keeps a workspace from accumulating one per
        // file ever served.
        var reopened = files.open(target, null);
        assertThat(reopened.id(), is(not(first.id())));
    }

    @Test
    public void testAFileThatIsNotAPortfolioLeavesNothingBehind() throws Exception
    {
        var notAPortfolio = folder.newFile("notes.txt").toPath();
        Files.writeString(notAPortfolio, "hello");

        try
        {
            files.open(notAPortfolio, null);
            Assert.fail("expected a non-portfolio file to be refused");
        }
        catch (IOException | RuntimeException e)
        {
            // The claim is only justified while the file is served, so a failed load
            // must not leave one behind - otherwise the next attempt is refused for a
            // reason that has nothing to do with why the first one failed.
            assertThat(files.list().size(), is(0));

            try (var claimed = PortfolioFileLock.acquire(notAPortfolio, 5712))
            {
                assertThat(claimed.lockFile(), is(PortfolioFileLock.lockFileFor(notAPortfolio)));
            }
        }
    }
    private Path encryptedFile(String name, String password) throws IOException
    {
        var target = path(name);
        var client = new Client();
        client.setBaseCurrency("EUR");
        client.addSecurity(security("In an encrypted file"));
        ClientFactory.saveAs(client, target.toFile(), password.toCharArray(), ClientFileType.BINARY_AES256.getFlags());
        return target;
    }

    /**
     * An encrypted file is refused with its own exception rather than the loader's
     * message, which is localized and says the same thing for a corrupt file. The
     * caller's answer to it is to ask a human, so it has to be able to tell.
     */
    @Test
    public void testAnEncryptedFileWithoutAPasswordIsRefusedDistinguishably() throws Exception
    {
        var target = encryptedFile("locked.portfolio", "hunter2");

        try
        {
            files.open(target, null);
            Assert.fail("expected an encrypted file with no password to be refused");
        }
        catch (FileSet.PasswordRequiredException e)
        {
            assertThat(files.list().size(), is(0));
        }

        // and nothing is claimed, so the next attempt is not refused for the wrong reason
        try (var claimed = PortfolioFileLock.acquire(target, 5712))
        {
            assertThat(Files.exists(claimed.lockFile()), is(true));
        }
    }

    @Test
    public void testAWrongPasswordIsItsOwnAnswer() throws Exception
    {
        var target = encryptedFile("locked.portfolio", "hunter2");

        try
        {
            files.open(target, "not-it".toCharArray());
            Assert.fail("expected a wrong password to be refused");
        }
        catch (FileSet.PasswordRejectedException e)
        {
            assertThat(files.list().size(), is(0));
        }
    }

    @Test
    public void testAnEncryptedFileOpensWithItsPasswordAndSavesWithoutIt() throws Exception
    {
        var target = encryptedFile("locked.portfolio", "hunter2");

        var opened = files.open(target, "hunter2".toCharArray());
        assertThat(opened.file().getClient().getSecurities().size(), is(1));

        // The derived key stays on the client, which is what lets a write be persisted
        // without the password being kept anywhere.
        opened.file().getClient().addSecurity(security("Added after unlocking"));
        files.close(opened.id(), true);

        var reloaded = ClientFactory.load(target.toFile(), "hunter2".toCharArray(), new NullProgressMonitor());
        assertThat(reloaded.getSecurities().size(), is(2));
    }

    /**
     * A copy of an encrypted file is encrypted, with the password the caller supplied -
     * the source's own is not kept, and writing the copy in the clear would drop a
     * file's protection on the user's behalf.
     */
    @Test
    public void testACopyOfAnEncryptedFileStaysEncrypted() throws Exception
    {
        var source = files.open(encryptedFile("locked.portfolio", "hunter2"), "hunter2".toCharArray());

        try
        {
            files.copy(source.id(), path("copy.portfolio"), null);
            Assert.fail("expected a copy with no password to be refused");
        }
        catch (FileSet.PasswordRequiredException e)
        {
            assertThat(Files.exists(path("copy.portfolio")), is(false));
        }

        var copy = files.copy(source.id(), path("copy.portfolio"), "hunter2".toCharArray());

        assertThat(copy.file().getClient().getSecurities().size(), is(1));
        assertThat(ClientFactory.isEncrypted(path("copy.portfolio").toFile()), is(true));
    }

    /** A password on a file that has none is not a way to add encryption to a copy. */
    @Test
    public void testAPasswordDoesNotEncryptTheCopyOfAPlainFile() throws Exception
    {
        var source = files.open(existingFile("plain.portfolio"), null);

        files.copy(source.id(), path("copy.portfolio"), "hunter2".toCharArray());

        assertThat(ClientFactory.isEncrypted(path("copy.portfolio").toFile()), is(false));
    }
}
