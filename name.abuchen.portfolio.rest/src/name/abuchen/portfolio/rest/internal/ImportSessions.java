package name.abuchen.portfolio.rest.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import name.abuchen.portfolio.datatransfer.Extractor;

/**
 * The extraction sessions an import goes through. Extraction produces
 * {@link Extractor.Item}s that hold <em>live model references</em> - a security
 * that may not be in the file yet, an account the extractor guessed - so they
 * cannot round-trip through JSON. The client therefore reviews a rendering of
 * them and applies by index, and the items themselves stay here in the
 * meantime.
 * <p>
 * In memory and per process, deliberately: a session is a preview, and losing
 * one costs a re-upload. It is also scoped to the file it was extracted
 * against, so a session cannot be applied to a different one.
 */
public final class ImportSessions
{
    /** Long enough for a person to read a preview, short enough not to pin a file's model. */
    private static final Duration TTL = Duration.ofMinutes(30);

    /**
     * The one CSV target whose items are not transactions to book but prices to
     * merge into an instrument the caller names. Its extractor returns a single
     * {@code SecurityItem} carrying a nameless, currency-less placeholder
     * security that exists only to hold them.
     */
    public static final String SECURITY_PRICE_TARGET = "investment-vehicle-price"; //$NON-NLS-1$

    /**
     * @param csvTarget
     *            the CSV target the extraction was read with, or {@code null}
     *            for a PDF or Flex statement. Kept because what an apply has to
     *            do with the items depends on it, and the items themselves
     *            cannot say: a security-price extraction and one that creates
     *            an instrument both produce a {@code SecurityItem}.
     */
    public record Session(String id, String fileId, String fileName, List<Extractor.Item> items,
                    List<String> extractionErrors, String csvTarget, Instant createdAt)
    {
        public boolean isSecurityPriceImport()
        {
            return SECURITY_PRICE_TARGET.equals(csvTarget);
        }
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private ImportSessions()
    {
    }

    public static Session create(String fileId, String fileName, List<Extractor.Item> items,
                    List<String> extractionErrors, String csvTarget)
    {
        expire();

        var session = new Session(UUID.randomUUID().toString(), fileId, fileName, List.copyOf(items),
                        List.copyOf(extractionErrors), csvTarget, Instant.now());
        SESSIONS.put(session.id(), session);
        return session;
    }

    /**
     * The session, if it exists and belongs to this file. A session from another
     * file is reported as missing rather than as forbidden: the two cases are
     * indistinguishable to a caller that should not have the id at all, and
     * saying which would confirm that it exists.
     */
    public static Session get(String fileId, String id)
    {
        expire();

        var session = SESSIONS.get(id);
        return session != null && session.fileId().equals(fileId) ? session : null;
    }

    public static void discard(String id)
    {
        SESSIONS.remove(id);
    }

    private static void expire()
    {
        var cutoff = Instant.now().minus(TTL);
        SESSIONS.values().removeIf(session -> session.createdAt().isBefore(cutoff));
    }
}
