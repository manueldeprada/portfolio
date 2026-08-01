package name.abuchen.portfolio.rest.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * HTTP conditional GET for the file-scoped reads: an {@code ETag} derived from
 * inputs that are all known <em>before</em> the response is computed, so that a
 * matching {@code If-None-Match} can be answered without computing it at all.
 * That short-circuit is the entire point - a validator taken from the finished
 * body would save the client bandwidth but the application nothing.
 */
public final class ConditionalGet
{
    public static final String ETAG = "ETag"; //$NON-NLS-1$

    private static final String IF_NONE_MATCH = "If-None-Match"; //$NON-NLS-1$

    /** bytes of the digest kept, i.e. 128 bits - short enough to read in a log, far past collision */
    private static final int TAG_LENGTH = 16;

    private ConditionalGet()
    {
    }

    /**
     * The validator for a request against a file at a given change count. It
     * covers everything a response body depends on: the state of the model, the
     * current date (a response that defaults to "today" is a different response
     * tomorrow), and the request itself - method, path and query parameters,
     * the latter in a canonical order so that a caller reordering them still
     * gets a cache hit. The concrete path is used rather than the route
     * pattern: it subsumes the pattern and additionally separates two entities
     * served by the same route.
     */
    public static String etag(long changeCount, Request request)
    {
        var canonical = new StringBuilder();
        canonical.append(changeCount).append('\n');
        canonical.append(LocalDate.now()).append('\n');
        canonical.append(request.method()).append('\n');
        canonical.append(request.path()).append('\n');

        for (var entry : new TreeMap<>(request.queryParams()).entrySet())
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');

        return '"' + hash(canonical.toString()) + '"';
    }

    /**
     * Whether the client already holds the representation identified by
     * {@code etag}. Accepts the comma-separated list and the {@code *} wildcard
     * RFC 9110 allows, and ignores the weakness prefix: this API only ever
     * issues strong tags, so a {@code W/} echoed back names the same
     * representation.
     */
    public static boolean isNotModified(Request request, String etag)
    {
        var header = request.header(IF_NONE_MATCH);
        if (header == null)
            return false;

        for (var candidate : header.split(",")) //$NON-NLS-1$
        {
            var value = candidate.strip();
            if ("*".equals(value)) //$NON-NLS-1$
                return true;
            if (value.startsWith("W/")) //$NON-NLS-1$
                value = value.substring(2);
            if (value.equals(etag))
                return true;
        }

        return false;
    }

    /**
     * The 304 answer: the validator, and no body. RFC 9110 forbids a body here,
     * which is what makes the saved computation invisible to the client beyond
     * the status code.
     */
    public static Response notModified(String etag)
    {
        return new Response(304, null, new byte[0], Map.of(ETAG, etag));
    }

    private static String hash(String canonical)
    {
        try
        {
            var digest = MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));

            var hex = new StringBuilder(TAG_LENGTH * 2);
            for (var ii = 0; ii < TAG_LENGTH; ii++)
                hex.append(Character.forDigit((digest[ii] >> 4) & 0xf, 16))
                                .append(Character.forDigit(digest[ii] & 0xf, 16));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            // SHA-256 is required of every Java platform
            throw new IllegalStateException(e);
        }
    }
}
