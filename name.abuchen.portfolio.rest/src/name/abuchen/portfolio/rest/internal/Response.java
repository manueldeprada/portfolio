package name.abuchen.portfolio.rest.internal;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;

public record Response(int status, String contentType, byte[] body, Map<String, String> headers)
{
    public static Response json(int status, JsonElement element)
    {
        return new Response(status, "application/json", element.toString().getBytes(StandardCharsets.UTF_8), Map.of()); //$NON-NLS-1$
    }

    /** A response with a caller-supplied content type and raw body, e.g. the OpenAPI document. */
    public static Response of(int status, String contentType, byte[] body)
    {
        return new Response(status, contentType, body, Map.of());
    }

    public static Response noContent()
    {
        return new Response(204, null, new byte[0], Map.of());
    }

    /**
     * A copy carrying one more response header. Lets a route decorate a handler's
     * response (with a validator, say) without every handler having to know about
     * headers at all.
     */
    public Response withHeader(String name, String value)
    {
        var merged = new HashMap<>(headers);
        merged.put(name, value);
        return new Response(status, contentType, body, Map.copyOf(merged));
    }
}
