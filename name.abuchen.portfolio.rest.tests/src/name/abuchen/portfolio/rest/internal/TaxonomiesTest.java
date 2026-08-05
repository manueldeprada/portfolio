package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.model.TaxonomyTemplate;

@SuppressWarnings("nls")
public class TaxonomiesTest
{
    private static JsonArray items(Client client)
    {
        return TaxonomiesHandler.list(client).getAsJsonObject().get("items").getAsJsonArray();
    }

    /** the reported category with that id, from anywhere in the first taxonomy's tree */
    private static JsonObject category(Client client, String id)
    {
        return find(items(client).get(0).getAsJsonObject().get("categories").getAsJsonArray(), id);
    }

    private static JsonObject find(JsonArray categories, String id)
    {
        for (var element : categories)
        {
            var category = element.getAsJsonObject();
            if (id.equals(category.get("id").getAsString()))
                return category;

            var children = category.get("children");
            if (children != null)
            {
                var match = find(children.getAsJsonArray(), id);
                if (match != null)
                    return match;
            }
        }
        return null;
    }

    private static Client clientWithTaxonomy()
    {
        var client = new Client();

        var taxonomy = new Taxonomy("Asset Allocation");
        var root = new Classification(taxonomy.getId(), "Asset Allocation");
        taxonomy.setRootNode(root);

        var equity = new Classification(root, "equity", "Equity");
        equity.setColor("#89afee");
        root.addChild(equity);
        equity.addChild(new Classification(equity, "europe", "Europe"));
        equity.addChild(new Classification(equity, "us", "North America"));

        root.addChild(new Classification(root, "cash", "Cash"));

        client.addTaxonomy(taxonomy);
        return client;
    }

    @Test
    public void testTheTaxonomyIsReportedWithItsIdAndName()
    {
        var list = items(clientWithTaxonomy());

        assertThat(list.size(), is(1));
        var taxonomy = list.get(0).getAsJsonObject();
        assertThat(taxonomy.get("id").getAsString(), is(not(emptyString())));
        assertThat(taxonomy.get("name").getAsString(), is("Asset Allocation"));
    }

    /**
     * The root names the taxonomy, not a category - and a holding's classification
     * paths skip it too, so the two only line up if it is left out here as well.
     */
    @Test
    public void testTheRootIsNotReportedAsACategory()
    {
        var categories = items(clientWithTaxonomy()).get(0).getAsJsonObject().get("categories").getAsJsonArray();

        assertThat(categories.size(), is(2));
        assertThat(categories.get(0).getAsJsonObject().get("name").getAsString(), is("Equity"));
        assertThat(categories.get(1).getAsJsonObject().get("name").getAsString(), is("Cash"));
    }

    @Test
    public void testTheTreeIsNested()
    {
        var categories = items(clientWithTaxonomy()).get(0).getAsJsonObject().get("categories").getAsJsonArray();
        var children = categories.get(0).getAsJsonObject().get("children").getAsJsonArray();

        assertThat(children.size(), is(2));
        assertThat(children.get(0).getAsJsonObject().get("name").getAsString(), is("Europe"));
    }

    /** A leaf omits the key rather than carrying an empty array on every leaf. */
    @Test
    public void testALeafHasNoChildrenKey()
    {
        var categories = items(clientWithTaxonomy()).get(0).getAsJsonObject().get("categories").getAsJsonArray();

        assertThat(categories.get(1).getAsJsonObject().get("children"), is(nullValue()));
    }

    /**
     * The application assigns every category a color when it is created, so one is
     * always reported - the field is optional in the schema for the de-serialized
     * case, not because a category normally lacks one.
     */
    @Test
    public void testTheColorIsReported()
    {
        var categories = items(clientWithTaxonomy()).get(0).getAsJsonObject().get("categories").getAsJsonArray();

        assertThat(categories.get(0).getAsJsonObject().get("color").getAsString(), is("#89afee"));
        assertThat(categories.get(1).getAsJsonObject().get("color").getAsString(), is(not(emptyString())));
    }

    @Test
    public void testAFileWithoutTaxonomiesReportsAnEmptyList()
    {
        assertThat(items(new Client()).size(), is(0));
    }

    /**
     * The ids a holding's classifications are keyed by have to be the ids reported
     * here, or a client cannot join the two.
     */
    @Test
    public void testTheIdMatchesTheOneUsedOnHoldings()
    {
        var client = clientWithTaxonomy();
        var reported = items(client).get(0).getAsJsonObject().get("id").getAsString();

        assertThat(reported, is(client.getTaxonomies().get(0).getId()));
    }

    /**
     * The id is what a write names a category by, so it has to be the
     * classification's own - not an index into the reported tree, which changes
     * the moment anything is added or reordered.
     */
    @Test
    public void testACategoryReportsItsOwnId()
    {
        var client = clientWithTaxonomy();
        var categories = items(client).get(0).getAsJsonObject().get("categories").getAsJsonArray();

        assertThat(categories.get(0).getAsJsonObject().get("id").getAsString(), is("equity"));
        assertThat(categories.get(1).getAsJsonObject().get("id").getAsString(), is("cash"));
    }

    /**
     * The application sorts a taxonomy's children by rank. Reporting the model's
     * list order would show the tree in an order the user never arranged.
     */
    @Test
    public void testCategoriesAreReportedInRankOrder()
    {
        var client = clientWithTaxonomy();
        var root = client.getTaxonomies().get(0).getRoot();
        root.getChildren().get(0).setRank(2); // Equity
        root.getChildren().get(1).setRank(1); // Cash

        var categories = items(client).get(0).getAsJsonObject().get("categories").getAsJsonArray();

        assertThat(categories.get(0).getAsJsonObject().get("name").getAsString(), is("Cash"));
        assertThat(categories.get(1).getAsJsonObject().get("name").getAsString(), is("Equity"));
    }

    /**
     * The weight is the rebalancing target, a fraction of the parent category. It
     * is always reported, including the 100% a category is created with, or a
     * client editing targets would have to guess what an absent field means.
     */
    @Test
    public void testTheTargetWeightIsReportedAsAFraction()
    {
        var client = clientWithTaxonomy();
        client.getTaxonomies().get(0).getRoot().getChildren().get(0)
                        .setWeight(Classification.ONE_HUNDRED_PERCENT / 4);

        var categories = items(client).get(0).getAsJsonObject().get("categories").getAsJsonArray();

        assertThat(categories.get(0).getAsJsonObject().get("weight").getAsBigDecimal(),
                        is(new BigDecimal("0.25")));
        assertThat(categories.get(1).getAsJsonObject().get("weight").getAsInt(), is(1));
    }

    @Test
    public void testAnAssignedInstrumentIsReportedOnItsCategory()
    {
        var client = clientWithTaxonomy();
        var security = new SecurityBuilder().addTo(client);
        var europe = client.getTaxonomies().get(0).getClassificationById("europe");
        europe.addAssignment(new Assignment(security));

        var assignments = category(client, "europe").get("assignments").getAsJsonArray();

        assertThat(assignments.size(), is(1));
        var assignment = assignments.get(0).getAsJsonObject();
        assertThat(assignment.get("type").getAsString(), is("instrument"));
        assertThat(assignment.get("uuid").getAsString(), is(security.getUUID()));
        assertThat(assignment.get("name").getAsString(), is(security.getName()));
        assertThat(assignment.get("weight").getAsInt(), is(1));
    }

    /**
     * An asset-allocation taxonomy commonly classifies cash accounts too, and the
     * vehicle type is what tells the two apart on the wire.
     */
    @Test
    public void testAnAssignedCashAccountIsReportedAsSuch()
    {
        var client = clientWithTaxonomy();
        var account = new AccountBuilder().addTo(client);
        client.getTaxonomies().get(0).getClassificationById("cash").addAssignment(new Assignment(account));

        var assignment = category(client, "cash").get("assignments").getAsJsonArray().get(0).getAsJsonObject();

        assertThat(assignment.get("type").getAsString(), is("cash-account"));
        assertThat(assignment.get("uuid").getAsString(), is(account.getUUID()));
    }

    /** A split assignment reports the share it holds, not the whole vehicle. */
    @Test
    public void testAPartialAssignmentReportsItsShare()
    {
        var client = clientWithTaxonomy();
        var security = new SecurityBuilder().addTo(client);
        client.getTaxonomies().get(0).getClassificationById("europe")
                        .addAssignment(new Assignment(security, Classification.ONE_HUNDRED_PERCENT * 30 / 100));

        var assignment = category(client, "europe").get("assignments").getAsJsonArray().get(0).getAsJsonObject();

        assertThat(assignment.get("weight").getAsBigDecimal(), is(new BigDecimal("0.3")));
    }

    /** Most leaves of a deep taxonomy hold nothing; an empty array on each is noise. */
    @Test
    public void testACategoryWithoutAssignmentsHasNoAssignmentsKey()
    {
        assertThat(category(clientWithTaxonomy(), "cash").get("assignments"), is(nullValue()));
    }

    /**
     * A taxonomy built from one of the bundled templates carries the template's id
     * as its root's classification key, and that is the only thing distinguishing
     * the stock schemes every file starts with from one somebody built by hand.
     */
    @Test
    public void testATemplateBuiltTaxonomyReportsTheTemplateItCameFrom()
    {
        var client = new Client();
        var taxonomy = TaxonomyTemplate.byId("security-type").build();
        client.addTaxonomy(taxonomy);

        assertThat(items(client).get(0).getAsJsonObject().get("template").getAsString(), is("security-type"));
    }

    @Test
    public void testAHandBuiltTaxonomyReportsNoTemplate()
    {
        assertThat(items(clientWithTaxonomy()).get(0).getAsJsonObject().get("template"), is(nullValue()));
    }

    @Test
    public void testEveryTaxonomyIsListed()
    {
        var client = clientWithTaxonomy();
        var second = new Taxonomy("Regions");
        second.setRootNode(new Classification(second.getId(), "Regions"));
        client.addTaxonomy(second);

        var list = items(client);
        assertThat(list.size(), is(2));

        var empty = (JsonObject) list.get(1);
        assertThat(empty.get("name").getAsString(), is("Regions"));
        assertThat(empty.get("categories").getAsJsonArray().size(), is(0));
    }
}
