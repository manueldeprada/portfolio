package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeSet;

import org.junit.Test;

import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.Values;

/**
 * The list a client shows for an account has to explain the account's balance:
 * the signed amounts of {@code GET .../transactions?account=} add up to the
 * series of {@code GET .../cash-accounts/{uuid}/balances}, on every day.
 */
@SuppressWarnings("nls")
public class AccountReconciliationTest
{
    /** What each wire type does to a cash balance, written down here rather than derived from the model. */
    private static final Map<String, Integer> CASH_DIRECTION = Map.ofEntries( //
                    Map.entry("deposit", 1), Map.entry("interest", 1), Map.entry("dividends", 1),
                    Map.entry("fees-refund", 1), Map.entry("tax-refund", 1), Map.entry("sell", 1),
                    Map.entry("transfer-in", 1), //
                    Map.entry("removal", -1), Map.entry("interest-charge", -1), Map.entry("fees", -1),
                    Map.entry("taxes", -1), Map.entry("buy", -1), Map.entry("transfer-out", -1));

    private static TransactionsAccountFilterTest.Fixture fixture()
    {
        var f = new TransactionsAccountFilterTest.Fixture();

        var rest = Map.of(AccountTransaction.Type.INTEREST, 3, AccountTransaction.Type.INTEREST_CHARGE, 4,
                        AccountTransaction.Type.FEES_REFUND, 2, AccountTransaction.Type.TAX_REFUND, 1,
                        AccountTransaction.Type.TAXES, 7);
        var day = 5;
        for (var entry : rest.entrySet())
            f.cashB.addTransaction(new AccountTransaction(LocalDateTime.of(2024, 1, day++, 0, 0), "EUR",
                            Values.Amount.factorize(entry.getValue()), null, entry.getKey()));

        return f;
    }

    /** the sum of the filtered list's amounts up to and including a day, signed by the type's direction */
    private static double listedBalance(Client client, String uuid, LocalDate through)
    {
        var sum = 0d;
        for (var item : TransactionsAccountFilterTest.forAccount(client, uuid))
        {
            if (LocalDate.parse(item.get("date").getAsString().substring(0, 10)).isAfter(through))
                continue;

            var direction = CASH_DIRECTION.get(item.get("type").getAsString());
            assertThat("a cash account lists only cash legs: " + item.get("type"), direction != null, is(true));
            sum += direction * Math.abs(item.get("value").getAsJsonObject().get("value").getAsDouble());
        }
        return sum;
    }

    private static double balanceOn(Client client, String uuid, LocalDate day)
    {
        var series = AccountsHandler.balances(client, uuid, "2024-01-02", "2024-02-01").getAsJsonObject()
                        .getAsJsonArray("items");
        for (var e : series)
        {
            JsonObject item = e.getAsJsonObject();
            if (day.toString().equals(item.get("date").getAsString()))
                return item.get("balance").getAsDouble();
        }
        throw new AssertionError("no balance for " + day);
    }

    @Test
    public void testEveryCashAccountsListAddsUpToItsBalanceOnEveryDay()
    {
        var f = fixture();

        for (var account : f.client.getAccounts())
        {
            for (var day = LocalDate.of(2024, 1, 2); !day.isAfter(LocalDate.of(2024, 2, 1)); day = day.plusDays(1))
                assertThat(account.getName() + " on " + day, listedBalance(f.client, account.getUUID(), day),
                                closeTo(balanceOn(f.client, account.getUUID(), day), 1e-6));
        }
    }

    @Test
    public void testTheFixtureCoversEveryCashLegKindTheListCanReport()
    {
        var f = fixture();

        var seen = new TreeSet<String>();
        for (var account : f.client.getAccounts())
            TransactionsAccountFilterTest.forAccount(f.client, account.getUUID())
                            .forEach(i -> seen.add(i.get("type").getAsString()));

        assertThat(seen, is(new TreeSet<>(CASH_DIRECTION.keySet())));
    }
}
