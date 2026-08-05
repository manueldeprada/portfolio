package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Taxonomy;

@SuppressWarnings("nls")
public class WriteTaxonomyCategoryTest
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

    private static Taxonomy taxonomy(Client client)
    {
        return client.getTaxonomies().get(0);
    }

    /** the top-level category names, in the order the tree reports them */
    private static List<String> topLevel(Client client)
    {
        return EntityJson.toJson(taxonomy(client)).get("categories").getAsJsonArray().asList().stream()
                        .map(c -> c.getAsJsonObject().get("name").getAsString()).toList();
    }

    private static String addTopLevel(Client client, String name)
    {
        var result = TaxonomiesHandler.addCategory(client, taxonomy(client).getId(),
                        json("{\"name\":\"" + name + "\"}"));
        return result.entity().get("categories").getAsJsonArray().asList().stream()
                        .map(c -> c.getAsJsonObject()) //
                        .filter(c -> name.equals(c.get("name").getAsString())) //
                        .findFirst().orElseThrow().get("id").getAsString();
    }

    @Test
    public void testACategoryIsAddedUnderTheRootByDefault()
    {
        var client = clientWithTaxonomy();

        TaxonomiesHandler.addCategory(client, taxonomy(client).getId(), json("{\"name\":\"Europe\"}"));

        assertThat(taxonomy(client).getRoot().getChildren().size(), is(1));
        assertThat(taxonomy(client).getRoot().getChildren().get(0).getName(), is("Europe"));
    }

    @Test
    public void testACategoryCanBeAddedUnderAnother()
    {
        var client = clientWithTaxonomy();
        var europe = addTopLevel(client, "Europe");

        TaxonomiesHandler.addCategory(client, taxonomy(client).getId(),
                        json("{\"name\":\"Switzerland\",\"parent\":\"" + europe + "\"}"));

        var parent = taxonomy(client).getClassificationById(europe);
        assertThat(parent.getChildren().size(), is(1));
        assertThat(parent.getChildren().get(0).getName(), is("Switzerland"));
        assertThat(parent.getChildren().get(0).getParent(), is(parent));
    }

    /**
     * The application's own "add category" gives a new category whatever its
     * siblings leave unclaimed, so the first child of a parent is a whole 100%
     * and the next one nothing.
     */
    @Test
    public void testANewCategoryTakesTheWeightItsSiblingsLeave()
    {
        var client = clientWithTaxonomy();
        var first = addTopLevel(client, "Europe");
        var second = addTopLevel(client, "Asia");

        assertThat(taxonomy(client).getClassificationById(first).getWeight(),
                        is(Classification.ONE_HUNDRED_PERCENT));
        assertThat(taxonomy(client).getClassificationById(second).getWeight(), is(0));
    }

    @Test
    public void testAGivenWeightIsStoredAsAFractionOfTheParent()
    {
        var client = clientWithTaxonomy();

        TaxonomiesHandler.addCategory(client, taxonomy(client).getId(),
                        json("{\"name\":\"Europe\",\"weight\":0.25}"));

        assertThat(taxonomy(client).getRoot().getChildren().get(0).getWeight(),
                        is(Classification.ONE_HUNDRED_PERCENT / 4));
    }

    /** A client that meant 40 rather than 0.4 has to hear about it. */
    @Test
    public void testAWeightOutsideTheUnitRangeIsRefused()
    {
        var client = clientWithTaxonomy();

        var thrown = assertThrows(ApiException.class, () -> TaxonomiesHandler.addCategory(client,
                        taxonomy(client).getId(), json("{\"name\":\"Europe\",\"weight\":40}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).code(), is("invalid-range"));
        assertThat(taxonomy(client).getRoot().getChildren().size(), is(0));
    }

    @Test
    public void testAColorMustBeAHexTriplet()
    {
        var client = clientWithTaxonomy();

        var thrown = assertThrows(ApiException.class, () -> TaxonomiesHandler.addCategory(client,
                        taxonomy(client).getId(), json("{\"name\":\"Europe\",\"color\":\"blue\"}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).field(), is("color"));
    }

    /** The application colors every category it creates; so does this. */
    @Test
    public void testACategoryAddedWithoutAColorGetsOne()
    {
        var client = clientWithTaxonomy();

        TaxonomiesHandler.addCategory(client, taxonomy(client).getId(), json("{\"name\":\"Europe\"}"));

        assertThat(taxonomy(client).getRoot().getChildren().get(0).getColor(), is(not(nullValue())));
    }

    @Test
    public void testCategoriesAreAppendedInOrderAndAPositionInsertsAmongThem()
    {
        var client = clientWithTaxonomy();
        addTopLevel(client, "Europe");
        addTopLevel(client, "Asia");

        TaxonomiesHandler.addCategory(client, taxonomy(client).getId(),
                        json("{\"name\":\"Americas\",\"position\":1}"));

        assertThat(topLevel(client), contains("Europe", "Americas", "Asia"));
    }

    @Test
    public void testAPositionBeyondTheEndPutsTheCategoryLast()
    {
        var client = clientWithTaxonomy();
        addTopLevel(client, "Europe");

        TaxonomiesHandler.addCategory(client, taxonomy(client).getId(),
                        json("{\"name\":\"Asia\",\"position\":99}"));

        assertThat(topLevel(client), contains("Europe", "Asia"));
    }

    @Test
    public void testPatchRenamesRecolorsAndReweights()
    {
        var client = clientWithTaxonomy();
        var europe = addTopLevel(client, "Europe");

        TaxonomiesHandler.patchCategory(client, taxonomy(client).getId(), europe,
                        json("{\"name\":\"Developed Europe\",\"color\":\"#89afee\",\"weight\":0.5}"));

        var category = taxonomy(client).getClassificationById(europe);
        assertThat(category.getName(), is("Developed Europe"));
        assertThat(category.getColor(), is("#89afee"));
        assertThat(category.getWeight(), is(Classification.ONE_HUNDRED_PERCENT / 2));
    }

    @Test
    public void testAMoveTakesTheSubtreeWithIt()
    {
        var client = clientWithTaxonomy();
        var europe = addTopLevel(client, "Europe");
        var asia = addTopLevel(client, "Asia");
        TaxonomiesHandler.addCategory(client, taxonomy(client).getId(),
                        json("{\"name\":\"Switzerland\",\"parent\":\"" + europe + "\"}"));

        TaxonomiesHandler.patchCategory(client, taxonomy(client).getId(), europe,
                        json("{\"parent\":\"" + asia + "\"}"));

        assertThat(topLevel(client), contains("Asia"));
        var moved = taxonomy(client).getClassificationById(europe);
        assertThat(moved.getParent().getName(), is("Asia"));
        assertThat(moved.getChildren().get(0).getName(), is("Switzerland"));
    }

    /** An explicit null is the way back to the top level: the root is not a category. */
    @Test
    public void testANullParentMovesACategoryToTheTopLevel()
    {
        var client = clientWithTaxonomy();
        var europe = addTopLevel(client, "Europe");
        var result = TaxonomiesHandler.addCategory(client, taxonomy(client).getId(),
                        json("{\"name\":\"Switzerland\",\"parent\":\"" + europe + "\"}"));
        var switzerland = result.entity().get("categories").getAsJsonArray().get(0).getAsJsonObject()
                        .get("children").getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString();

        TaxonomiesHandler.patchCategory(client, taxonomy(client).getId(), switzerland, json("{\"parent\":null}"));

        assertThat(topLevel(client), contains("Europe", "Switzerland"));
    }

    /**
     * Moving a category into its own subtree would detach that subtree from the
     * tree - it would still exist, reachable from nothing.
     */
    @Test
    public void testACategoryCannotBeMovedIntoItsOwnSubtree()
    {
        var client = clientWithTaxonomy();
        var europe = addTopLevel(client, "Europe");
        var result = TaxonomiesHandler.addCategory(client, taxonomy(client).getId(),
                        json("{\"name\":\"Switzerland\",\"parent\":\"" + europe + "\"}"));
        var switzerland = result.entity().get("categories").getAsJsonArray().get(0).getAsJsonObject()
                        .get("children").getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString();

        var thrown = assertThrows(ApiException.class, () -> TaxonomiesHandler.patchCategory(client,
                        taxonomy(client).getId(), europe, json("{\"parent\":\"" + switzerland + "\"}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).field(), is("parent"));
        assertThat(taxonomy(client).getClassificationById(europe).getParent(), is(taxonomy(client).getRoot()));
    }

    @Test
    public void testAParentFromAnotherTaxonomyIsRejected()
    {
        var client = clientWithTaxonomy();
        var europe = addTopLevel(client, "Europe");

        var thrown = assertThrows(ApiException.class, () -> TaxonomiesHandler.patchCategory(client,
                        taxonomy(client).getId(), europe, json("{\"parent\":\"no-such-category\"}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).code(), is("unknown-reference"));
    }

    @Test
    public void testPositionAloneReordersWithinTheSameParent()
    {
        var client = clientWithTaxonomy();
        addTopLevel(client, "Europe");
        var asia = addTopLevel(client, "Asia");

        TaxonomiesHandler.patchCategory(client, taxonomy(client).getId(), asia, json("{\"position\":0}"));

        assertThat(topLevel(client), contains("Asia", "Europe"));
    }

    @Test
    public void testAPatchThatChangesNothingIsNotRecorded()
    {
        var client = clientWithTaxonomy();
        var europe = addTopLevel(client, "Europe");

        var result = TaxonomiesHandler.patchCategory(client, taxonomy(client).getId(), europe,
                        json("{\"name\":\"Europe\"}"));

        assertThat(result.detail(), is(nullValue()));
    }

    @Test
    public void testDeleteRemovesTheCategoryAndItsChildren()
    {
        var client = clientWithTaxonomy();
        var europe = addTopLevel(client, "Europe");
        TaxonomiesHandler.addCategory(client, taxonomy(client).getId(),
                        json("{\"name\":\"Switzerland\",\"parent\":\"" + europe + "\"}"));

        TaxonomiesHandler.deleteCategory(client, taxonomy(client).getId(), europe);

        assertThat(topLevel(client), is(List.of()));
        // the root remains: it is the taxonomy, not a category
        assertThat(taxonomy(client).getAllClassifications(), contains(taxonomy(client).getRoot()));
    }

    /**
     * The root names the taxonomy and is never reported as a category, so it is
     * not addressable as one either - a client that tries gets a 404 rather than
     * a taxonomy with no tree.
     */
    @Test
    public void testTheRootIsNotAddressableAsACategory()
    {
        var client = clientWithTaxonomy();
        var rootId = taxonomy(client).getRoot().getId();

        var thrown = assertThrows(ApiException.class,
                        () -> TaxonomiesHandler.deleteCategory(client, taxonomy(client).getId(), rootId));

        assertThat(thrown.getStatus(), is(404));
        assertThat(taxonomy(client).getRoot(), is(not(nullValue())));
    }
}
