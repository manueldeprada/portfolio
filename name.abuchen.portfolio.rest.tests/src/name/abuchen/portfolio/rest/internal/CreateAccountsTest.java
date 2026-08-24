package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;

@SuppressWarnings("nls")
public class CreateAccountsTest
{
    private static JsonObject json(String body)
    {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    @Test
    public void testCreateAddsACashAccountInTheGivenCurrency()
    {
        var client = new Client();

        var entity = AccountsHandler.create(client, json("{\"name\":\"USD Cash\",\"currencyCode\":\"USD\"}"));

        assertThat(client.getAccounts().size(), is(1));
        var account = client.getAccounts().get(0);
        assertThat(account.getName(), is("USD Cash"));
        assertThat(account.getCurrencyCode(), is("USD"));
        assertThat(entity.get("uuid").getAsString(), is(account.getUUID()));
        assertThat(entity.get("balance").getAsJsonObject().get("currency").getAsString(), is("USD"));
    }

    @Test
    public void testCreateRequiresAName()
    {
        var client = new Client();

        var thrown = assertThrows(ApiException.class,
                        () -> AccountsHandler.create(client, json("{\"name\":\"  \",\"currencyCode\":\"USD\"}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).field(), is("name"));
        assertThat(client.getAccounts().size(), is(0));
    }

    @Test
    public void testCreateRejectsAnUnknownCurrency()
    {
        var client = new Client();

        var thrown = assertThrows(ApiException.class,
                        () -> AccountsHandler.create(client, json("{\"name\":\"Cash\",\"currencyCode\":\"XXX\"}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).field(), is("currencyCode"));
        assertThat(client.getAccounts().size(), is(0));
    }

    @Test
    public void testCreatePortfolioAddsAnEmptyInvestmentAccount()
    {
        var client = new Client();

        var entity = PortfoliosHandler.create(client, json("{\"name\":\"Broker\"}"));

        assertThat(client.getPortfolios().size(), is(1));
        var portfolio = client.getPortfolios().get(0);
        assertThat(portfolio.getName(), is("Broker"));
        assertThat(portfolio.getReferenceAccount(), is(nullValue()));
        assertThat(entity.get("uuid").getAsString(), is(portfolio.getUUID()));
    }

    @Test
    public void testCreatePortfolioSetsTheGivenReferenceAccount()
    {
        var client = new Client();
        var cash = new Account("Cash");
        cash.setCurrencyCode("USD");
        client.addAccount(cash);

        var entity = PortfoliosHandler.create(client,
                        json("{\"name\":\"Broker\",\"referenceAccount\":\"" + cash.getUUID() + "\"}"));

        var portfolio = client.getPortfolios().get(0);
        assertThat(portfolio.getReferenceAccount(), is(cash));
        assertThat(entity.get("referenceCashAccount").getAsString(), is(cash.getUUID()));
        assertThat(entity.get("value").getAsJsonObject().get("currency").getAsString(), is("USD"));
    }

    @Test
    public void testCreatePortfolioRejectsAnUnknownReferenceAccount()
    {
        var client = new Client();

        var thrown = assertThrows(ApiException.class, () -> PortfoliosHandler.create(client,
                        json("{\"name\":\"Broker\",\"referenceAccount\":\"no-such-uuid\"}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(thrown.getErrors().get(0).field(), is("referenceAccount"));
        assertThat(client.getPortfolios().size(), is(0));
    }

    @Test
    public void testCreatePortfolioRequiresAName()
    {
        var client = new Client();

        var thrown = assertThrows(ApiException.class,
                        () -> PortfoliosHandler.create(client, json("{\"name\":\"\"}")));

        assertThat(thrown.getStatus(), is(422));
        assertThat(client.getPortfolios().size(), is(0));
    }
}
