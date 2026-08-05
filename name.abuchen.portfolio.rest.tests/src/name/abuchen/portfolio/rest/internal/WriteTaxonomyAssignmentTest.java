package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;

@SuppressWarnings("nls")
public class WriteTaxonomyAssignmentTest
{
    private Client client;
    private Taxonomy taxonomy;
    private Security security;
    private Account account;

    private static JsonObject json(String body)
    {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    @Before
    public void setUp()
    {
        client = new Client();

        taxonomy = new Taxonomy("Regions");
        var root = new Classification(taxonomy.getId(), "Regions");
        taxonomy.setRootNode(root);
        root.addChild(new Classification(root, "europe", "Europe"));
        root.addChild(new Classification(root, "asia", "Asia"));
        client.addTaxonomy(taxonomy);

        security = new SecurityBuilder().addTo(client);
        account = new AccountBuilder().addTo(client);
    }

    private Classification category(String id)
    {
        return taxonomy.getClassificationById(id);
    }

    @Test
    public void testAnInstrumentIsAssignedWholeByDefault()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{}"));

        var assignments = category("europe").getAssignments();
        assertThat(assignments.size(), is(1));
        assertThat(assignments.get(0).getInvestmentVehicle(), is(security));
        assertThat(assignments.get(0).getWeight(), is(Classification.ONE_HUNDRED_PERCENT));
    }

    @Test
    public void testACashAccountCanBeAssignedToo()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", account.getUUID(), json("{}"));

        assertThat(category("europe").getAssignments().get(0).getInvestmentVehicle(), is(account));
    }

    @Test
    public void testAGivenWeightIsStoredAsAShare()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{\"weight\":0.3}"));

        assertThat(category("europe").getAssignments().get(0).getWeight(),
                        is(Classification.ONE_HUNDRED_PERCENT * 30 / 100));
    }

    /**
     * The default is what the rest of the taxonomy leaves, which is what dropping
     * a half-assigned security onto a second category means in the application.
     */
    @Test
    public void testASecondAssignmentDefaultsToWhatIsLeft()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{\"weight\":0.4}"));

        TaxonomiesHandler.assign(client, taxonomy.getId(), "asia", security.getUUID(), json("{}"));

        assertThat(category("asia").getAssignments().get(0).getWeight(),
                        is(Classification.ONE_HUNDRED_PERCENT * 60 / 100));
    }

    /** A silently reduced share would be a wrong allocation nobody sees. */
    @Test
    public void testAssigningMoreThanIsLeftIsRefused()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{\"weight\":0.8}"));

        var thrown = assertThrows(ApiException.class, () -> TaxonomiesHandler.assign(client, taxonomy.getId(),
                        "asia", security.getUUID(), json("{\"weight\":0.5}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).code(), is("invalid-range"));
        assertThat(thrown.getErrors().get(0).message(), containsString("0.2"));
        assertThat(category("asia").getAssignments().size(), is(0));
    }

    @Test
    public void testAssigningWhatIsAlreadyFullyAssignedIsRefused()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{}"));

        var thrown = assertThrows(ApiException.class,
                        () -> TaxonomiesHandler.assign(client, taxonomy.getId(), "asia", security.getUUID(),
                                        json("{}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).message(), containsString("already fully assigned"));
    }

    /**
     * The same call assigns and re-weights, so a client retrying after a 423 does
     * not have to know which of the two its first attempt was.
     */
    @Test
    public void testAssigningAgainChangesTheShareInPlace()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{\"weight\":0.4}"));

        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{\"weight\":0.9}"));

        assertThat(category("europe").getAssignments().size(), is(1));
        assertThat(category("europe").getAssignments().get(0).getWeight(),
                        is(Classification.ONE_HUNDRED_PERCENT * 90 / 100));
    }

    /** Its own share does not count against what is available to it. */
    @Test
    public void testRaisingAShareToTheWholeIsAllowed()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{\"weight\":0.4}"));

        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{\"weight\":1}"));

        assertThat(category("europe").getAssignments().get(0).getWeight(),
                        is(Classification.ONE_HUNDRED_PERCENT));
    }

    @Test
    public void testAssigningTheSameShareAgainChangesNothing()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{\"weight\":0.4}"));

        var result = TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(),
                        json("{\"weight\":0.4}"));

        assertThat(result.detail(), is(nullValue()));
    }

    @Test
    public void testAZeroShareIsRefusedRatherThanTakenAsARemoval()
    {
        var thrown = assertThrows(ApiException.class, () -> TaxonomiesHandler.assign(client, taxonomy.getId(),
                        "europe", security.getUUID(), json("{\"weight\":0}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(category("europe").getAssignments().size(), is(0));
    }

    @Test
    public void testAnUnknownVehicleIsNotFound()
    {
        var thrown = assertThrows(ApiException.class, () -> TaxonomiesHandler.assign(client, taxonomy.getId(),
                        "europe", "no-such-uuid", json("{}")));

        assertThat(thrown.getStatus(), is(404));
    }

    /** A split assignment is removed one category at a time. */
    @Test
    public void testUnassignLeavesTheOtherCategoriesAlone()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{\"weight\":0.5}"));
        TaxonomiesHandler.assign(client, taxonomy.getId(), "asia", security.getUUID(), json("{\"weight\":0.5}"));

        TaxonomiesHandler.unassign(client, taxonomy.getId(), "europe", security.getUUID());

        assertThat(category("europe").getAssignments().size(), is(0));
        assertThat(category("asia").getAssignments().size(), is(1));
    }

    @Test
    public void testUnassigningWhatIsNotAssignedHereIsNotFound()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{}"));

        var thrown = assertThrows(ApiException.class,
                        () -> TaxonomiesHandler.unassign(client, taxonomy.getId(), "asia", security.getUUID()));

        assertThat(thrown.getStatus(), is(404));
    }

    /** Deleting a category takes its assignments with it, and frees the share. */
    @Test
    public void testDeletingACategoryFreesWhatItHeld()
    {
        TaxonomiesHandler.assign(client, taxonomy.getId(), "europe", security.getUUID(), json("{}"));

        TaxonomiesHandler.deleteCategory(client, taxonomy.getId(), "europe");
        TaxonomiesHandler.assign(client, taxonomy.getId(), "asia", security.getUUID(), json("{}"));

        assertThat(category("asia").getAssignments().get(0).getWeight(), is(Classification.ONE_HUNDRED_PERCENT));
    }
}
