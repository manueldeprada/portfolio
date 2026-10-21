package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonObject;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Values;

/**
 * {@code account=} selects every transaction that touches the account, not only
 * the ones it owns in the de-duplicated list.
 */
@SuppressWarnings("nls")
public class TransactionsAccountFilterTest
{
    /** Two cash accounts and two investment accounts, one of every kind of cross entry. */
    static final class Fixture
    {
        final Client client = new Client();
        final Account cashA;
        final Account cashB;
        final Portfolio brokerA;
        final Portfolio brokerB;
        final Security security;

        Fixture()
        {
            cashA = new AccountBuilder("EUR") //
                            .deposit_("2024-01-02", Values.Amount.factorize(10000)) //
                            .withdraw("2024-01-04", Values.Amount.factorize(50)) //
                            .addTo(client);
            cashB = new AccountBuilder("EUR") //
                            .deposit_("2024-01-02", Values.Amount.factorize(100)) //
                            .addTo(client);
            security = new SecurityBuilder("EUR").addTo(client);

            brokerA = new PortfolioBuilder(cashA) //
                            .buy(security, "2024-01-03", Values.Share.factorize(10), Values.Amount.factorize(1000)) //
                            .buy(security, "2024-01-08", Values.Share.factorize(5), Values.Amount.factorize(410),
                                            Values.Amount.factorize(10), 0) //
                            .sell(security, "2024-01-10", Values.Share.factorize(4), Values.Amount.factorize(500)) //
                            .addTo(client);
            brokerB = new PortfolioBuilder(cashB).addTo(client);

            cashA.addTransaction(new AccountTransaction(LocalDateTime.parse("2024-01-15T00:00"), "EUR",
                            Values.Amount.factorize(20), security, AccountTransaction.Type.DIVIDENDS));
            cashA.addTransaction(new AccountTransaction(LocalDateTime.parse("2024-01-16T00:00"), "EUR",
                            Values.Amount.factorize(5), null, AccountTransaction.Type.FEES));

            var cashTransfer = new AccountTransferEntry(cashA, cashB);
            cashTransfer.setDate(LocalDateTime.parse("2024-01-20T00:00"));
            cashTransfer.setAmount(Values.Amount.factorize(300));
            cashTransfer.setCurrencyCode("EUR");
            cashTransfer.insert();

            var securityTransfer = new PortfolioTransferEntry(brokerA, brokerB);
            securityTransfer.setDate(LocalDateTime.parse("2024-01-25T00:00"));
            securityTransfer.setSecurity(security);
            securityTransfer.setShares(Values.Share.factorize(3));
            securityTransfer.setAmount(Values.Amount.factorize(120));
            securityTransfer.setCurrencyCode("EUR");
            securityTransfer.insert();
        }
    }

    static List<JsonObject> forAccount(Client client, String uuid)
    {
        var items = new ArrayList<JsonObject>();
        TransactionsHandler.list(client, uuid).getAsJsonObject().getAsJsonArray("items")
                        .forEach(e -> items.add(e.getAsJsonObject()));
        return items;
    }

    private static List<String> types(List<JsonObject> items)
    {
        return items.stream().map(i -> i.get("type").getAsString()).toList();
    }

    @Test
    public void testACashAccountListsTheCashLegsOfTradesAndBothTransferLegs()
    {
        var f = new Fixture();

        // newest first
        assertThat(types(forAccount(f.client, f.cashA.getUUID())),
                        contains("transfer-out", "fees", "dividends", "sell", "buy", "removal", "buy", "deposit"));
    }

    @Test
    public void testTheReceivingLegOfATransferIsListedForTheReceivingAccount()
    {
        var f = new Fixture();

        var items = forAccount(f.client, f.cashB.getUUID());
        assertThat(types(items), contains("transfer-in", "deposit"));
        assertThat(items.get(0).get("value").getAsJsonObject().get("value").getAsDouble(), is(300d));
        assertThat(items.get(0).getAsJsonObject().get("counterOwner").getAsJsonObject().get("uuid").getAsString(),
                        is(f.cashA.getUUID()));
    }

    @Test
    public void testEveryEntryIsReportedFromTheRequestedAccountsSide()
    {
        var f = new Fixture();

        for (var account : List.of(f.cashA, f.cashB))
            for (var item : forAccount(f.client, account.getUUID()))
            {
                var owner = item.get("owner").getAsJsonObject();
                assertThat(owner.get("uuid").getAsString(), is(account.getUUID()));
                assertThat(owner.get("type").getAsString(), is("cash-account"));
            }
    }

    @Test
    public void testACashLegOfATradeCarriesTheInvestmentAccountAsCounterOwner()
    {
        var f = new Fixture();

        var buy = forAccount(f.client, f.cashA.getUUID()).stream()
                        .filter(i -> "buy".equals(i.get("type").getAsString())).findFirst().orElseThrow();
        assertThat(buy.get("value").getAsJsonObject().get("value").getAsDouble() < 0, is(true));
        assertThat(buy.get("counterOwner").getAsJsonObject().get("uuid").getAsString(), is(f.brokerA.getUUID()));
        assertThat(buy.get("counterOwner").getAsJsonObject().get("type").getAsString(), is("investment-account"));
    }

    @Test
    public void testAnInvestmentAccountListsBothSidesOfAPortfolioTransfer()
    {
        var f = new Fixture();

        assertThat(types(forAccount(f.client, f.brokerA.getUUID())),
                        contains("transfer-out", "sell", "buy", "buy"));
        var receiving = forAccount(f.client, f.brokerB.getUUID());
        assertThat(types(receiving), contains("transfer-in"));
        assertThat(receiving.get(0).get("shares").getAsDouble(), is(3d));
        assertThat(receiving.get(0).get("owner").getAsJsonObject().get("uuid").getAsString(),
                        is(f.brokerB.getUUID()));
    }

    @Test
    public void testWithoutTheFilterEveryCrossEntryIsStillListedOnce()
    {
        var f = new Fixture();

        var all = TransactionsHandler.list(f.client).getAsJsonObject().getAsJsonArray("items");
        var uuids = new ArrayList<String>();
        all.forEach(e -> uuids.add(e.getAsJsonObject().get("uuid").getAsString()));

        assertThat(uuids, hasSize(f.client.getAllTransactions().size()));
        assertThat(uuids.stream().distinct().count(), is((long) uuids.size()));
        // 15 legs, minus the three cash legs of trades and the two receiving transfer legs
        assertThat(all.size(), is(10));
    }

    @Test
    public void testAnUnknownAccountIsAFieldError()
    {
        var f = new Fixture();

        try
        {
            TransactionsHandler.list(f.client, "no-such-uuid");
            Assert.fail("expected a 400");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(400));
            assertThat(e.getErrors().get(0).field(), is("account"));
        }
    }

    @Test
    public void testTheSecurityOfAFilteredEntryIsReported()
    {
        var f = new Fixture();

        var dividend = forAccount(f.client, f.cashA.getUUID()).stream()
                        .filter(i -> "dividends".equals(i.get("type").getAsString())).findFirst().orElseThrow();
        assertThat(dividend.get("security").getAsJsonObject().get("uuid").getAsString(), is(f.security.getUUID()));
        assertThat(dividend.get("counterOwner"), is(nullValue()));
        assertThat(LocalDate.parse(dividend.get("date").getAsString().substring(0, 10)), is(LocalDate.of(2024, 1, 15)));
    }
}
