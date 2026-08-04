package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.Test;

import com.google.gson.JsonObject;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.Values;

/**
 * {@code Client#getAllTransactions()} de-duplicates a cross entry to one leg,
 * so the cash account a buy debited would be invisible in the list without
 * {@code counterOwner}. A consumer reconstructing what an account was used for
 * (which purchases a margin debit funded, say) joins on it.
 */
@SuppressWarnings("nls")
public class TransactionCounterOwnerTest
{
    @Test
    public void testABuyNamesTheCashAccountItSettledAgainst()
    {
        var client = new Client();
        var account = new AccountBuilder("EUR") //
                        .deposit_("2024-01-02", Values.Amount.factorize(1000)) //
                        .addTo(client);
        var security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder(account) //
                        .buy(security, "2024-01-03", Values.Share.factorize(10), Values.Amount.factorize(500)) //
                        .addTo(client);

        JsonObject buy = null;
        JsonObject deposit = null;
        for (var pair : client.getAllTransactions())
        {
            var item = EntityJson.toJson(pair);
            if ("buy".equals(item.get("type").getAsString()))
                buy = item;
            if ("deposit".equals(item.get("type").getAsString()))
                deposit = item;
        }

        assertThat(buy, is(notNullValue()));
        assertThat(buy.get("owner").getAsJsonObject().get("type").getAsString(), is("investment-account"));

        var counter = buy.get("counterOwner").getAsJsonObject();
        assertThat(counter.get("uuid").getAsString(), is(account.getUUID()));
        assertThat(counter.get("type").getAsString(), is("cash-account"));

        // a deposit has no cross entry, and no counter owner
        assertThat(deposit, is(notNullValue()));
        assertThat(deposit.get("counterOwner"), is(nullValue()));
    }
}
