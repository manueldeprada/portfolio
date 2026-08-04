package name.abuchen.portfolio.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

@SuppressWarnings("nls")
public class ClientFactoryTest
{
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testGetFlagsForZipFile() throws IOException
    {
        var zipFile = tempFolder.newFile("test.zip");
        var flags = ClientFactory.getFlags(zipFile);

        assertEquals(EnumSet.of(SaveFlag.XML, SaveFlag.COMPRESSED), flags);
    }

    @Test
    public void testGetFlagsForZipFileCaseInsensitive() throws IOException
    {
        var zipFile = tempFolder.newFile("test.ZIP");
        var flags = ClientFactory.getFlags(zipFile);

        assertEquals(EnumSet.of(SaveFlag.XML, SaveFlag.COMPRESSED), flags);
    }

    @Test
    public void testGetFlagsForEncryptedPortfolio() throws IOException
    {
        var encryptedFile = tempFolder.newFile("test.portfolio");

        // Write encrypted signature (PORTFOLIO in bytes)
        try (var out = new FileOutputStream(encryptedFile))
        {
            out.write(new byte[] { 80, 79, 82, 84, 70, 79, 76, 73, 79 });
        }

        var flags = ClientFactory.getFlags(encryptedFile);
        assertEquals(EnumSet.of(SaveFlag.ENCRYPTED), flags);
    }

    @Test
    public void testGetFlagsForCompressedPortfolio() throws IOException
    {
        var compressedFile = tempFolder.newFile("test.portfolio");

        // Write ZIP signature (PK\x03\x04)
        try (var out = new FileOutputStream(compressedFile))
        {
            out.write(new byte[] { 80, 75, 3, 4 });
        }

        var flags = ClientFactory.getFlags(compressedFile);
        assertEquals(EnumSet.of(SaveFlag.COMPRESSED), flags);
    }

    @Test
    public void testGetFlagsForAribitraryPortfolio() throws IOException
    {
        var compressedFile = tempFolder.newFile("arbitrary.portfolio");

        // Write ZIP signature (PK\x03\x04)
        try (var out = new FileOutputStream(compressedFile))
        {
            out.write(new byte[] { 44, 45, 43, 46 });
        }

        var flags = ClientFactory.getFlags(compressedFile);
        assertEquals(EnumSet.of(SaveFlag.XML), flags);
    }

    @Test
    public void testGetFlagsForXmlFileWithoutIdReferences() throws IOException
    {
        var xmlFile = tempFolder.newFile("test.xml");

        try (var out = new FileOutputStream(xmlFile))
        {
            out.write("<client><version>123</version>".getBytes(StandardCharsets.UTF_8));
        }

        var flags = ClientFactory.getFlags(xmlFile);
        assertEquals(EnumSet.of(SaveFlag.XML), flags);
    }

    @Test
    public void testGetFlagsForXmlFileWithIdReferences() throws IOException
    {
        var xmlFile = tempFolder.newFile("test.xml");

        try (var out = new FileOutputStream(xmlFile))
        {
            out.write("<client id=\"123\">".getBytes(StandardCharsets.UTF_8));
        }

        var flags = ClientFactory.getFlags(xmlFile);
        assertTrue(flags.contains(SaveFlag.XML));
        assertTrue(flags.contains(SaveFlag.ID_REFERENCES));
    }

    @Test
    public void testGetFlagsForEmptyFile() throws IOException
    {
        var emptyFile = tempFolder.newFile("empty.dat");

        var flags = ClientFactory.getFlags(emptyFile);
        assertEquals(EnumSet.of(SaveFlag.XML), flags);
    }

    @Test
    public void testGetFlagsForFileWithoutExtension() throws IOException
    {
        var file = tempFolder.newFile("noextension");

        try (var out = new FileOutputStream(file))
        {
            out.write("<client><version>123</version>".getBytes(StandardCharsets.UTF_8));
        }

        var flags = ClientFactory.getFlags(file);
        assertEquals(EnumSet.of(SaveFlag.XML), flags);
    }

    @Test
    public void testGetFlagsForEncryptedFileWithoutExtension() throws IOException
    {
        var file = tempFolder.newFile("encrypted");

        // Write encrypted signature
        try (var out = new FileOutputStream(file))
        {
            out.write(new byte[] { 80, 79, 82, 84, 70, 79, 76, 73, 79 });
        }

        var flags = ClientFactory.getFlags(file);
        assertEquals(EnumSet.of(SaveFlag.ENCRYPTED), flags);
    }

    @Test
    public void testGetFlagsForCompressedFileWithoutExtension() throws IOException
    {
        var file = tempFolder.newFile("compressed");

        // Write ZIP signature
        try (var out = new FileOutputStream(file))
        {
            out.write(new byte[] { 80, 75, 3, 4 });
        }

        var flags = ClientFactory.getFlags(file);
        assertEquals(EnumSet.of(SaveFlag.COMPRESSED), flags);
    }

    @Test
    public void testGetFlagsForUnknownFileContent() throws IOException
    {
        var file = tempFolder.newFile("unknown.dat");

        // Write some random content that doesn't match any signature
        try (var out = new FileOutputStream(file))
        {
            out.write(new byte[] { 1, 2, 3, 4, 5 });
        }

        var flags = ClientFactory.getFlags(file);
        assertEquals(EnumSet.of(SaveFlag.XML), flags);
    }

    @Test
    public void testGetFlagsForUnknownShortFileContent() throws IOException
    {
        var file = tempFolder.newFile("unknown.dat");

        // Write some random content that doesn't match any signature
        try (var out = new FileOutputStream(file))
        {
            out.write(new byte[] { 1 });
        }

        var flags = ClientFactory.getFlags(file);
        assertEquals(EnumSet.of(SaveFlag.XML), flags);
    }

    @Test
    public void testGetFlagsForXmlFileWithIdReferencesInFallback() throws IOException
    {
        var file = tempFolder.newFile("unknown.dat");

        try (var out = new FileOutputStream(file))
        {
            out.write("<client id=\"1\"><version>123</version>".getBytes(StandardCharsets.UTF_8));
        }

        var flags = ClientFactory.getFlags(file);
        assertTrue(flags.contains(SaveFlag.XML));
        assertTrue(flags.contains(SaveFlag.ID_REFERENCES));
    }
    private static Client clientWith(String securityName)
    {
        var client = new Client();
        client.setBaseCurrency("EUR");

        var security = new Security();
        security.setName(securityName);
        security.setCurrencyCode("EUR");
        client.addSecurity(security);

        return client;
    }

    /**
     * A save that cannot complete must leave the file it was asked to write exactly
     * as it was. It used to write into that file directly, so the previous contents
     * were gone the moment the stream opened and anything failing afterwards - a full
     * disk, an unserializable model - left a truncated file. The bytes now go to a
     * sibling that is renamed into place, and this pins where they go: with the
     * sibling's name occupied by a directory, the save fails and the file is untouched.
     */
    @Test
    public void testAFailedSaveLeavesTheExistingFileIntact() throws IOException
    {
        var file = tempFolder.newFile("portfolio.xml");
        ClientFactory.save(clientWith("Before"), file);

        var saved = Files.readAllBytes(file.toPath());
        assertTrue(saved.length > 0);

        assertTrue(new File(tempFolder.getRoot(), "portfolio.xml.part").mkdir());

        try
        {
            ClientFactory.save(clientWith("After"), file);
            fail("expected the save to fail with its temporary file's name taken");
        }
        catch (IOException e)
        {
            assertArrayEquals(saved, Files.readAllBytes(file.toPath()));
        }
    }

    /** A successful save leaves no half-written sibling behind. */
    @Test
    public void testASuccessfulSaveCleansUpAfterItself() throws IOException
    {
        var file = tempFolder.newFile("portfolio.xml");

        ClientFactory.save(clientWith("Whatever"), file);

        assertTrue(file.length() > 0);
        assertFalse(new File(tempFolder.getRoot(), "portfolio.xml.part").exists());
    }

    @Test
    public void testASaveKeepsThePermissionsOfTheFileItReplaces() throws IOException
    {
        var file = tempFolder.newFile("portfolio.xml");
        var path = file.toPath();

        assumeTrue(Files.getFileAttributeView(path, PosixFileAttributeView.class) != null);

        // a file the user restricted to themselves must not be widened by a save,
        // which a rename would do: the temporary file was created with the default mask
        var restricted = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(path, restricted);

        ClientFactory.save(clientWith("Whatever"), file);

        assertEquals(restricted, Files.getPosixFilePermissions(path));
    }

    /**
     * Replacing a file is governed by its directory's permissions, not its own, so a
     * rename would overwrite a file the user marked read-only - which writing into it
     * never did.
     */
    @Test
    public void testAReadOnlyFileIsStillRefused() throws IOException
    {
        var file = tempFolder.newFile("portfolio.xml");
        ClientFactory.save(clientWith("Before"), file);

        var saved = Files.readAllBytes(file.toPath());
        assumeTrue(file.setWritable(false));

        try
        {
            ClientFactory.save(clientWith("After"), file);
            fail("expected a read-only file to be refused");
        }
        catch (IOException e)
        {
            assertArrayEquals(saved, Files.readAllBytes(file.toPath()));
        }
        finally
        {
            file.setWritable(true);
        }
    }

    /**
     * Writing through a symlink rather than replacing it: the rename would otherwise
     * turn the link into a regular file and leave what it pointed at untouched.
     */
    @Test
    public void testASaveFollowsASymlinkInsteadOfReplacingIt() throws IOException
    {
        var real = tempFolder.newFile("real.xml");
        var link = new File(tempFolder.getRoot(), "link.xml");

        try
        {
            Files.createSymbolicLink(link.toPath(), real.toPath());
        }
        catch (IOException | UnsupportedOperationException e)
        {
            assumeTrue("symbolic links are not available here", false);
            return;
        }

        ClientFactory.save(clientWith("Whatever"), link);

        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertTrue(Files.readAllBytes(real.toPath()).length > 0);
    }

    /**
     * {@code updatedAt} is unset on anything built in memory that nothing has changed,
     * and on anything read from a file old enough to predate the field. Saving such a
     * model to the binary format used to fail on a null dereference, reported as an
     * IOException naming neither the field nor the file.
     */
    @Test
    public void testAModelWithNoUpdatedAtCanBeSavedAndReloaded() throws IOException
    {
        var file = new File(tempFolder.getRoot(), "no-timestamps.portfolio");

        var client = clientWith("Never touched");
        var security = client.getSecurities().get(0);
        security.setUpdatedAt(null);

        ClientFactory.saveAs(client, file, null, EnumSet.of(SaveFlag.BINARY, SaveFlag.COMPRESSED));

        var reloaded = ClientFactory.load(file, null, new NullProgressMonitor());
        assertEquals(1, reloaded.getSecurities().size());
        assertEquals("Never touched", reloaded.getSecurities().get(0).getName());

        // and it comes back unset rather than as an invented instant
        assertEquals(null, reloaded.getSecurities().get(0).getUpdatedAt());
    }
}
