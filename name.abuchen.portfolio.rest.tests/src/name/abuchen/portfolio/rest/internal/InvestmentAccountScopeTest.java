package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.LocalDate;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Values;

/** {@code investmentAccount=} scopes holdings and performance to one investment account's instruments. */
@SuppressWarnings("nls")
public class InvestmentAccountScopeTest
{
    private static final String DATE = "2024-02-01";

    private static TransactionsAccountFilterTest.Fixture fixture()
    {
        var f = new TransactionsAccountFilterTest.Fixture();
        f.security.addPrice(new SecurityPrice(LocalDate.of(2024, 1, 30), Values.Quote.factorize(120)));
        return f;
    }

    static JsonObject holdings(Client client, String investmentAccount)
    {
        return HoldingsHandler.list(client, new ExchangeRateProviderFactory(client), DATE, null, null, null,
                        investmentAccount).getAsJsonObject();
    }

    private static double valueOfAccount(Client client, String uuid)
    {
        return PortfoliosHandler.get(client, new ExchangeRateProviderFactory(client), uuid, DATE, null)
                        .getAsJsonObject().get("value").getAsJsonObject().get("value").getAsDouble();
    }

    private static double total(JsonObject holdings)
    {
        return holdings.get("totalAssets").getAsJsonObject().get("value").getAsDouble();
    }

    private static ApiException refused(Runnable call)
    {
        try
        {
            call.run();
        }
        catch (ApiException e)
        {
            return e;
        }
        throw new AssertionError("expected a 400");
    }

    @Test
    public void testScopedHoldingsAreThatAccountsInstrumentsAndSumToItsValue()
    {
        var f = fixture();

        var scoped = holdings(f.client, f.brokerA.getUUID());

        // 10 + 5 bought, 4 sold, 3 transferred out
        assertThat(scoped.getAsJsonArray("items").size(), is(1));
        var position = scoped.getAsJsonArray("items").get(0).getAsJsonObject();
        assertThat(position.get("type").getAsString(), is("instrument"));
        assertThat(position.get("uuid").getAsString(), is(f.security.getUUID()));
        assertThat(position.get("shares").getAsDouble(), is(8d));

        assertThat(total(scoped), closeTo(valueOfAccount(f.client, f.brokerA.getUUID()), 1e-6));
        assertThat(total(scoped), closeTo(8 * 120d, 1e-6));
        assertThat(scoped.get("investmentAccount").getAsJsonObject().get("uuid").getAsString(),
                        is(f.brokerA.getUUID()));
    }

    @Test
    public void testTheReceivingAccountOfATransferHoldsTheTransferredShares()
    {
        var f = fixture();

        var scoped = holdings(f.client, f.brokerB.getUUID());

        assertThat(scoped.getAsJsonArray("items").get(0).getAsJsonObject().get("shares").getAsDouble(), is(3d));
        assertThat(total(scoped), closeTo(valueOfAccount(f.client, f.brokerB.getUUID()), 1e-6));
    }

    @Test
    public void testUnscopedHoldingsAreUnchanged()
    {
        var f = fixture();

        var all = holdings(f.client, null);

        assertThat(all.get("investmentAccount"), is(nullValue()));
        var cash = 0;
        var shares = 0d;
        for (var item : all.getAsJsonArray("items"))
        {
            if ("cash-account".equals(item.getAsJsonObject().get("type").getAsString()))
                cash++;
            else
                shares += item.getAsJsonObject().get("shares").getAsDouble();
        }
        assertThat(cash, is(2));
        assertThat(shares, is(11d));
    }

    @Test
    public void testAScopedSeriesEndsAtTheAccountsValue()
    {
        var f = fixture();

        var series = PerformanceHandler.series(f.client, new ExchangeRateProviderFactory(f.client), "2024-01-01",
                        DATE, null, f.brokerA.getUUID()).getAsJsonObject();

        var days = series.getAsJsonArray("days");
        var last = days.get(days.size() - 1).getAsJsonObject();
        assertThat(last.get("date").getAsString(), is(DATE));
        assertThat(last.get("totalAssets").getAsDouble(), closeTo(valueOfAccount(f.client, f.brokerA.getUUID()), 1e-6));
        assertThat(series.get("investmentAccount").getAsJsonObject().get("name").getAsString(),
                        is(f.brokerA.getName()));

        var unscoped = PerformanceHandler.series(f.client, new ExchangeRateProviderFactory(f.client), "2024-01-01",
                        DATE, null).getAsJsonObject();
        assertThat(unscoped.get("investmentAccount"), is(nullValue()));
        var unscopedDays = unscoped.getAsJsonArray("days");
        assertThat(unscopedDays.get(unscopedDays.size() - 1).getAsJsonObject().get("totalAssets").getAsDouble(),
                        closeTo(valueOfAccount(f.client, f.brokerA.getUUID())
                                        + valueOfAccount(f.client, f.brokerB.getUUID())
                                        + cashBalance(f.client),
                                        1e-6));
    }

    private static double cashBalance(Client client)
    {
        var sum = 0d;
        for (var account : client.getAccounts())
        {
            var items = AccountsHandler.balances(client, account.getUUID(), "2024-01-02", DATE).getAsJsonObject()
                            .getAsJsonArray("items");
            sum += items.get(items.size() - 1).getAsJsonObject().get("balance").getAsDouble();
        }
        return sum;
    }

    @Test
    public void testAScopedPerformanceNamesItsAccount()
    {
        var f = fixture();

        JsonElement scoped = PerformanceHandler.list(f.client, new ExchangeRateProviderFactory(f.client),
                        "2024-01-01", DATE, null, null, f.brokerA.getUUID());

        var json = scoped.getAsJsonObject();
        assertThat(json.get("investmentAccount").getAsJsonObject().get("uuid").getAsString(),
                        is(f.brokerA.getUUID()));
        assertThat(json.get("breakdown").getAsJsonObject().get("closingValue").getAsJsonObject().get("value")
                        .getAsDouble(), closeTo(valueOfAccount(f.client, f.brokerA.getUUID()), 1e-6));
    }

    @Test
    public void testAnUnknownInvestmentAccountIsAFieldErrorOnEveryScopedRoute()
    {
        var f = fixture();
        var factory = new ExchangeRateProviderFactory(f.client);

        for (var call : new Runnable[] { //
                        () -> HoldingsHandler.list(f.client, factory, DATE, null, null, null, "nope"),
                        () -> PerformanceHandler.series(f.client, factory, "2024-01-01", DATE, null, "nope"),
                        () -> PerformanceHandler.list(f.client, factory, "2024-01-01", DATE, null, null, "nope") })
        {
            var e = refused(call);
            assertThat(e.getStatus(), is(400));
            assertThat(e.getErrors().get(0).field(), is("investmentAccount"));
            assertThat(e.getErrors().get(0).code(), is("unknown-reference"));
        }

        // a cash account's uuid is not an investment account
        Assert.assertEquals(400, refused(() -> HoldingsHandler.list(f.client, factory, DATE, null, null, null,
                        f.cashA.getUUID())).getStatus());
    }
}
