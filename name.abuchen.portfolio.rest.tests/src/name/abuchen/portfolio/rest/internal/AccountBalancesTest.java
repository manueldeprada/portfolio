package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.LocalDate;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class AccountBalancesTest
{
    private static JsonObject balances(Client client, String uuid, String from, String to)
    {
        return AccountsHandler.balances(client, uuid, from, to).getAsJsonObject();
    }

    private static JsonArray items(JsonObject result)
    {
        return result.get("items").getAsJsonArray();
    }

    private static double balanceAt(JsonObject result, int index)
    {
        return items(result).get(index).getAsJsonObject().get("balance").getAsDouble();
    }

    /**
     * Deposit 1000, withdraw 1250 three days later: the account goes negative,
     * which is exactly the state a consumer reads this series for.
     */
    private static Client clientWithLedger()
    {
        var client = new Client();
        client.setBaseCurrency("EUR");

        new AccountBuilder("CHF") //
                        .deposit_("2024-01-02", Values.Amount.factorize(1000)) //
                        .withdraw("2024-01-05", Values.Amount.factorize(1250)) //
                        .addTo(client);

        return client;
    }

    private static String uuid(Client client)
    {
        return client.getAccounts().get(0).getUUID();
    }

    @Test
    public void testTheBalanceIsCarriedAcrossDaysWithoutTransactions()
    {
        var client = clientWithLedger();
        var result = balances(client, uuid(client), "2024-01-02", "2024-01-06");

        assertThat(items(result).size(), is(5));
        assertThat(balanceAt(result, 0), closeTo(1000, 0.0001));
        assertThat(balanceAt(result, 1), closeTo(1000, 0.0001)); // no transaction that day
        assertThat(balanceAt(result, 3), closeTo(-250, 0.0001));
        assertThat(balanceAt(result, 4), closeTo(-250, 0.0001));

        assertThat(items(result).get(3).getAsJsonObject().get("date").getAsString(), is("2024-01-05"));
    }

    /** A series starting mid-history opens with the balance accumulated before it. */
    @Test
    public void testTheOpeningBalanceIncludesEverythingBeforeTheRange()
    {
        var client = clientWithLedger();
        var result = balances(client, uuid(client), "2024-01-04", "2024-01-05");

        assertThat(items(result).size(), is(2));
        assertThat(balanceAt(result, 0), closeTo(1000, 0.0001));
        assertThat(balanceAt(result, 1), closeTo(-250, 0.0001));
    }

    @Test
    public void testTheSeriesIsInTheAccountsOwnCurrency()
    {
        var client = clientWithLedger();
        var result = balances(client, uuid(client), "2024-01-02", "2024-01-02");

        assertThat(result.get("currency").getAsString(), is("CHF"));
        assertThat(result.get("uuid").getAsString(), is(uuid(client)));
    }

    /** From defaults to the first transaction, to defaults to today. */
    @Test
    public void testTheDefaultRangeRunsFromTheFirstTransactionUntilToday()
    {
        var client = clientWithLedger();
        var result = balances(client, uuid(client), null, null);

        assertThat(result.get("from").getAsString(), is("2024-01-02"));
        assertThat(result.get("to").getAsString(), is(LocalDate.now().toString()));

        var list = items(result);
        assertThat(balanceAt(result, list.size() - 1), closeTo(-250, 0.0001));
    }

    @Test
    public void testASingleDayIsAValidRange()
    {
        var client = clientWithLedger();
        var result = balances(client, uuid(client), "2024-01-05", "2024-01-05");

        assertThat(items(result).size(), is(1));
        assertThat(balanceAt(result, 0), closeTo(-250, 0.0001));
    }

    /**
     * An account with no transactions covers nothing: an empty series without a
     * range, not a flat line of zeros - mirroring an instrument without prices.
     */
    @Test
    public void testAnAccountWithoutTransactionsCoversNothing()
    {
        var client = new Client();
        client.setBaseCurrency("EUR");
        new AccountBuilder("CHF").addTo(client);

        var result = balances(client, uuid(client), null, null);

        assertThat(items(result).size(), is(0));
        assertThat(result.get("from"), is(nullValue()));
        assertThat(result.get("currency").getAsString(), is("CHF"));
    }

    @Test
    public void testARangeEndingBeforeTheFirstTransactionCoversNothing()
    {
        var client = clientWithLedger();
        var result = balances(client, uuid(client), null, "2023-12-31");

        assertThat(items(result).size(), is(0));
        assertThat(result.get("from"), is(nullValue()));
    }

    @Test
    public void testAnUnknownAccountIsNotFound()
    {
        var client = clientWithLedger();

        try
        {
            balances(client, "no-such-uuid", null, null);
            Assert.fail("expected ApiException");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(404));
        }
    }

    @Test
    public void testAMalformedDateIsRejected()
    {
        var client = clientWithLedger();
        assertFieldError(() -> balances(client, uuid(client), "01/02/2024", null), "from", "invalid-value");
        assertFieldError(() -> balances(client, uuid(client), null, "not-a-date"), "to", "invalid-value");
    }

    @Test
    public void testAnInvertedRangeIsRejected()
    {
        var client = clientWithLedger();
        assertFieldError(() -> balances(client, uuid(client), "2024-02-01", "2024-01-01"), "to", "invalid-range");
    }

    /** One entry per calendar day, so an unbounded range is an unbounded response. */
    @Test
    public void testARangeBeyondTwentyYearsIsRejected()
    {
        var client = clientWithLedger();
        assertFieldError(() -> balances(client, uuid(client), "2000-01-01", "2021-01-01"), "to", "invalid-range");
    }

    private static void assertFieldError(Runnable call, String field, String code)
    {
        try
        {
            call.run();
            Assert.fail("expected ApiException");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(400));
            assertThat(e.getErrors().get(0).field(), is(field));
            assertThat(e.getErrors().get(0).code(), is(code));
        }
    }
}
