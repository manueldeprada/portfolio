package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Taxonomy;

@SuppressWarnings("nls")
public class WriteTaxonomyTest
{
    private static JsonObject json(String body)
    {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static Client clientWithTaxonomy()
    {
        var client = new Client();

        var taxonomy = new Taxonomy("Regions");
        taxonomy.setRootNode(new Classification(taxonomy.getId(), "Regions"));
        client.addTaxonomy(taxonomy);

        return client;
    }

    @Test
    public void testCreateAddsATaxonomyWithNoCategories()
    {
        var client = new Client();

        var result = TaxonomiesHandler.create(client, json("{\"name\":\"Regions\"}"));

        assertThat(client.getTaxonomies().size(), is(1));
        assertThat(client.getTaxonomies().get(0).getName(), is("Regions"));
        assertThat(result.entity().get("categories").getAsJsonArray().size(), is(0));
        assertThat(result.entity().get("id").getAsString(), is(not(emptyString())));
    }

    /**
     * The root is the taxonomy, not a category - the read side never reports it -
     * so it has to exist and carry the same name, exactly as the application's own
     * "new taxonomy" builds it.
     */
    @Test
    public void testACreatedTaxonomyHasARootNamedAfterIt()
    {
        var client = new Client();

        TaxonomiesHandler.create(client, json("{\"name\":\"Regions\"}"));

        var root = client.getTaxonomies().get(0).getRoot();
        assertThat(root, is(not(nullValue())));
        assertThat(root.getName(), is("Regions"));
    }

    @Test
    public void testCreateRequiresAName()
    {
        var client = new Client();

        var thrown = assertThrows(ApiException.class, () -> TaxonomiesHandler.create(client, json("{\"name\":\"  \"}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).field(), is("name"));
        assertThat(client.getTaxonomies().size(), is(0));
    }

    /**
     * The tab above the tree and the tree's own root show the same name to the
     * user; renaming one and not the other leaves the two disagreeing.
     */
    @Test
    public void testRenamingATaxonomyRenamesItsRoot()
    {
        var client = clientWithTaxonomy();
        var taxonomy = client.getTaxonomies().get(0);

        TaxonomiesHandler.patch(client, taxonomy.getId(), json("{\"name\":\"World Regions\"}"));

        assertThat(taxonomy.getName(), is("World Regions"));
        assertThat(taxonomy.getRoot().getName(), is("World Regions"));
    }

    @Test
    public void testPatchRejectsAFieldThatIsNotWritable()
    {
        var client = clientWithTaxonomy();
        var taxonomy = client.getTaxonomies().get(0);

        var thrown = assertThrows(ApiException.class,
                        () -> TaxonomiesHandler.patch(client, taxonomy.getId(), json("{\"id\":\"other\"}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).code(), is("unknown-field"));
        assertThat(taxonomy.getId(), is(not("other")));
    }

    /** An empty patch must not mark the file dirty and prompt a save of nothing. */
    @Test
    public void testAPatchThatChangesNothingIsNotRecorded()
    {
        var client = clientWithTaxonomy();
        var taxonomy = client.getTaxonomies().get(0);

        var result = TaxonomiesHandler.patch(client, taxonomy.getId(), json("{\"name\":\"Regions\"}"));

        assertThat(result.detail(), is(nullValue()));
    }

    @Test
    public void testDeleteRemovesTheTaxonomy()
    {
        var client = clientWithTaxonomy();
        var taxonomy = client.getTaxonomies().get(0);

        var name = TaxonomiesHandler.delete(client, taxonomy.getId());

        assertThat(name, is("Regions"));
        assertThat(client.getTaxonomies().size(), is(0));
    }

    @Test
    public void testAnUnknownTaxonomyIsNotFound()
    {
        var client = clientWithTaxonomy();

        var thrown = assertThrows(ApiException.class, () -> TaxonomiesHandler.get(client, "no-such-taxonomy"));

        assertThat(thrown.getStatus(), is(404));
    }
}
