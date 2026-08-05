package name.abuchen.portfolio.rest.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Taxonomy;

public final class TaxonomiesHandler
{
    /**
     * What a write returns: the whole taxonomy, so a client can replace the tree
     * it holds rather than reconcile a fragment, plus the one-line description
     * the change log records.
     */
    public record Result(JsonObject entity, String taxonomyName, String detail)
    {
    }

    private TaxonomiesHandler()
    {
    }

    /**
     * The taxonomies the file defines, with their category trees.
     * <p>
     * This is the key to the {@code classifications} object on a holding, which is
     * keyed by taxonomy id and names its categories by path: without this list a
     * client has an id it cannot label and no way to know which categories exist but
     * hold nothing. The tree is what lets it show an empty category as empty rather
     * than not at all.
     */
    public static JsonElement list(Client client)
    {
        return EntityJson.envelope(client.getTaxonomies(), EntityJson::toJson);
    }

    public static JsonObject get(Client client, String taxonomyId)
    {
        return EntityJson.toJson(find(client, taxonomyId));
    }

    /**
     * Creates an empty taxonomy, exactly as the application's own "new taxonomy"
     * does: the root classification carries the same name, because the root
     * <em>is</em> the taxonomy - it is never reported as a category.
     */
    public static Result create(Client client, JsonObject body)
    {
        var errors = new ArrayList<ApiException.FieldError>();
        var name = requiredText(body, "name", errors); //$NON-NLS-1$

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        var taxonomy = new Taxonomy(name);
        taxonomy.setRootNode(new Classification(UUID.randomUUID().toString(), name));

        client.addTaxonomy(taxonomy);
        client.markDirty();

        return new Result(EntityJson.toJson(taxonomy), name, "created"); //$NON-NLS-1$
    }

    /**
     * Applies a JSON Merge Patch to the taxonomy itself. The root classification
     * is renamed with it: the two are one name to the user, and letting them
     * drift leaves a tree whose root disagrees with the tab above it.
     */
    public static Result patch(Client client, String taxonomyId, JsonObject body)
    {
        var taxonomy = find(client, taxonomyId);

        var errors = new ArrayList<ApiException.FieldError>();
        for (var field : body.keySet())
        {
            if (!"name".equals(field)) //$NON-NLS-1$
                errors.add(new ApiException.FieldError(field, "unknown-field", "field is not writable")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        var name = body.has("name") ? requiredText(body, "name", errors) : null; //$NON-NLS-1$ //$NON-NLS-2$

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        if (name == null || name.equals(taxonomy.getName()))
            return new Result(EntityJson.toJson(taxonomy), taxonomy.getName(), null);

        var before = taxonomy.getName();
        taxonomy.setName(name);
        taxonomy.getRoot().setName(name);
        client.markDirty();

        return new Result(EntityJson.toJson(taxonomy), name, "renamed from '" + before + "'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Deletes the taxonomy and every category in it. Nothing blocks it: a
     * taxonomy holds no data of its own, only a classification of instruments
     * and cash accounts that continue to exist without it - which is why the
     * application does not ask either.
     */
    public static String delete(Client client, String taxonomyId)
    {
        var taxonomy = find(client, taxonomyId);

        client.removeTaxonomy(taxonomy);
        client.markDirty();

        return taxonomy.getName();
    }

    /* package */ static Taxonomy find(Client client, String taxonomyId)
    {
        return Entities.byUuid(client.getTaxonomies(), Taxonomy::getId, taxonomyId);
    }

    /* package */ static String requiredText(JsonObject body, String field,
                    List<ApiException.FieldError> errors)
    {
        var value = body.get(field);

        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
        {
            errors.add(new ApiException.FieldError(field, "required", field + " must be a non-empty string")); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        return value.getAsString().strip();
    }
}
