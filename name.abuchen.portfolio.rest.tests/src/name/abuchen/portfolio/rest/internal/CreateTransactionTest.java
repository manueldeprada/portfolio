package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class CreateTransactionTest
{
    private static JsonObject json(String body)
    {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static Client clientWithAccount(String currency)
    {
        var client = new Client();
        new AccountBuilder(currency).addTo(client);
        return client;
    }

    private static String accountUuid(Client client)
    {
        return client.getAccounts().get(0).getUUID();
    }

    private static AccountTransaction only(Client client)
    {
        var transactions = client.getAccounts().get(0).getTransactions();
        assertThat(transactions.size(), is(1));
        return transactions.get(0);
    }

    /** the codes of every reported violation, which is how "all at once" is asserted */
    private static java.util.List<String> codesOf(ApiException e)
    {
        return e.getErrors().stream().map(ApiException.FieldError::field).toList();
    }

    @Test
    public void testDepositIsBookedWithTheAccountsCurrency()
    {
        var client = clientWithAccount("EUR");

        var result = TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\""
                        + accountUuid(client) + "\",\"date\":\"2026-07-20\","
                        + "\"amount\":{\"value\":1000,\"currency\":\"EUR\"},\"note\":\"Salary\"}"));

        var transaction = only(client);
        assertThat(transaction.getType(), is(AccountTransaction.Type.DEPOSIT));
        assertThat(transaction.getAmount(), is(1000L * Values.Amount.factor()));
        assertThat(transaction.getCurrencyCode(), is("EUR"));
        assertThat(transaction.getNote(), is("Salary"));
        assertThat(transaction.getSecurity(), nullValue());

        // a plain date is midnight, which is what the dialogs default to
        assertThat(transaction.getDateTime(), is(LocalDateTime.parse("2026-07-20T00:00")));

        assertThat(result.uuid(), is(transaction.getUUID()));
        assertThat(result.entity().getAsJsonObject().get("type").getAsString(), is("deposit"));
    }

    @Test
    public void testDateTimeIsKeptWhenOneIsSent()
    {
        var client = clientWithAccount("EUR");

        TransactionsHandler.create(client,
                        json("{\"type\":\"deposit\",\"account\":\"" + accountUuid(client)
                                        + "\",\"date\":\"2026-07-20T14:30\","
                                        + "\"amount\":{\"value\":1,\"currency\":\"EUR\"}}"));

        assertThat(only(client).getDateTime(), is(LocalDateTime.parse("2026-07-20T14:30")));
    }

    @Test
    public void testDividendKeepsTheGrossValueTheDialogsWouldCompute()
    {
        var client = clientWithAccount("EUR");
        var security = new SecurityBuilder("EUR").addTo(client);

        TransactionsHandler.create(client, json("{\"type\":\"dividends\",\"account\":\"" + accountUuid(client)
                        + "\",\"security\":\"" + security.getUUID() + "\",\"date\":\"2026-07-20\",\"shares\":10,"
                        + "\"amount\":{\"value\":42.5,\"currency\":\"EUR\"},"
                        + "\"taxes\":{\"value\":7.5,\"currency\":\"EUR\"}}"));

        var transaction = only(client);
        assertThat(transaction.getAmount(), is(4250L));
        assertThat(transaction.getUnitSum(Transaction.Unit.Type.TAX).getAmount(), is(750L));
        assertThat(transaction.getShares(), is(10L * Values.Share.factor()));
        assertThat(transaction.getSecurity(), is(security));

        // the reconciliation the whole endpoint exists to get right
        assertThat(transaction.getGrossValueAmount(), is(5000L));
    }

    @Test
    public void testGrossAmountIsCheckedRatherThanTrusted()
    {
        var client = clientWithAccount("EUR");
        var security = new SecurityBuilder("EUR").addTo(client);

        var body = "{\"type\":\"dividends\",\"account\":\"" + accountUuid(client) + "\",\"security\":\""
                        + security.getUUID() + "\",\"date\":\"2026-07-20\","
                        + "\"amount\":{\"value\":42.5,\"currency\":\"EUR\"},"
                        + "\"taxes\":{\"value\":7.5,\"currency\":\"EUR\"},"
                        + "\"grossAmount\":{\"value\":%s,\"currency\":\"EUR\"}}";

        TransactionsHandler.create(client, json(String.format(body, "50")));
        assertThat(only(client).getGrossValueAmount(), is(5000L));

        try
        {
            TransactionsHandler.create(client, json(String.format(body, "60")));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(codesOf(e), contains("grossAmount.value"));
        }

        // still exactly the one transaction booked above: nothing was applied
        assertThat(client.getAccounts().get(0).getTransactions().size(), is(1));
    }

    @Test
    public void testEveryViolationIsReportedAtOnceAndNothingIsBooked()
    {
        var client = clientWithAccount("EUR");

        try
        {
            TransactionsHandler.create(client,
                            json("{\"type\":\"nonsense\",\"account\":\"no-such-account\",\"date\":\"yesterday\","
                                            + "\"amount\":{\"value\":10,\"currency\":\"USD\"},\"bogus\":1}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            // The unresolvable account is *not* among them, and neither is the
            // currency: the type is what decides which fields a request even has,
            // so an unrecognised type stops there rather than guessing a shape and
            // reporting errors against it.
            assertThat(e.getStatus(), is(422));
            assertThat(codesOf(e), containsInAnyOrder("bogus", "type", "date"));
        }

        assertThat(client.getAccounts().get(0).getTransactions().isEmpty(), is(true));
    }

    @Test
    public void testAmountIsUnsignedBecauseTheTypeCarriesTheDirection()
    {
        var client = clientWithAccount("EUR");

        try
        {
            TransactionsHandler.create(client,
                            json("{\"type\":\"removal\",\"account\":\"" + accountUuid(client)
                                            + "\",\"date\":\"2026-07-20\","
                                            + "\"amount\":{\"value\":-50,\"currency\":\"EUR\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(codesOf(e), contains("amount.value"));
        }
    }

    @Test
    public void testZeroIsRejectedBecauseTheDialogsRequireAValue()
    {
        var client = clientWithAccount("EUR");

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\"" + accountUuid(client)
                            + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":0,\"currency\":\"EUR\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(codesOf(e), contains("amount.value"));
        }
    }

    @Test
    public void testCurrencyMustBeTheAccountsOwn()
    {
        var client = clientWithAccount("CHF");

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\"" + accountUuid(client)
                            + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":10,\"currency\":\"USD\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(codesOf(e), contains("amount.currency"));
            assertThat(e.getErrors().get(0).code(), is("currency-mismatch"));
        }
    }

    @Test
    public void testFieldsTheTypeDoesNotSupportAreRejectedRatherThanIgnored()
    {
        var client = clientWithAccount("EUR");
        var security = new SecurityBuilder("EUR").addTo(client);

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\"" + accountUuid(client)
                            + "\",\"security\":\"" + security.getUUID() + "\",\"date\":\"2026-07-20\",\"shares\":1,"
                            + "\"amount\":{\"value\":10,\"currency\":\"EUR\"},"
                            + "\"fees\":{\"value\":1,\"currency\":\"EUR\"},"
                            + "\"taxes\":{\"value\":1,\"currency\":\"EUR\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(codesOf(e), containsInAnyOrder("security", "shares", "fees", "taxes"));
            assertThat(e.getErrors().stream().map(ApiException.FieldError::code).distinct().toList(),
                            contains("not-applicable"));
        }
    }

    @Test
    public void testDividendsRequireAnInstrument()
    {
        var client = clientWithAccount("EUR");

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"dividends\",\"account\":\"" + accountUuid(client)
                            + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":10,\"currency\":\"EUR\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(codesOf(e), contains("security"));
            assertThat(e.getErrors().get(0).code(), is("required"));
        }
    }

    @Test
    public void testTaxesMayCarryAnOptionalInstrument()
    {
        var client = clientWithAccount("EUR");
        var security = new SecurityBuilder("EUR").addTo(client);

        TransactionsHandler.create(client, json("{\"type\":\"taxes\",\"account\":\"" + accountUuid(client)
                        + "\",\"security\":\"" + security.getUUID() + "\",\"date\":\"2026-07-20\","
                        + "\"amount\":{\"value\":10,\"currency\":\"EUR\"}}"));

        assertThat(only(client).getSecurity(), is(security));
    }

    @Test
    public void testExDateMustNotBeAfterTheDate()
    {
        var client = clientWithAccount("EUR");
        var security = new SecurityBuilder("EUR").addTo(client);

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"dividends\",\"account\":\"" + accountUuid(client)
                            + "\",\"security\":\"" + security.getUUID() + "\",\"date\":\"2026-07-20\","
                            + "\"exDate\":\"2026-07-21\",\"amount\":{\"value\":10,\"currency\":\"EUR\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(codesOf(e), contains("exDate"));
            assertThat(e.getErrors().get(0).code(), is("invalid-range"));
        }
    }

    @Test
    public void testExDateNeedsAnInstrument()
    {
        var client = clientWithAccount("EUR");

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\"" + accountUuid(client)
                            + "\",\"date\":\"2026-07-20\",\"exDate\":\"2026-07-19\","
                            + "\"amount\":{\"value\":10,\"currency\":\"EUR\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(codesOf(e), contains("exDate"));
        }
    }

    @Test
    public void testAnAmountFinerThanTheModelStoresIsRejectedRatherThanRounded()
    {
        var client = clientWithAccount("EUR");

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\"" + accountUuid(client)
                            + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":10.005,\"currency\":\"EUR\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(codesOf(e), contains("amount.value"));
            assertThat(e.getErrors().get(0).code(), is("invalid-value"));
        }
    }

    @Test
    public void testBookingMarksTheFileDirty()
    {
        var client = clientWithAccount("EUR");

        var dirty = new AtomicBoolean();
        client.addPropertyChangeListener("dirty", event -> dirty.set(true));

        TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\"" + accountUuid(client)
                        + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":10,\"currency\":\"EUR\"}}"));

        assertThat(dirty.get(), is(true));
    }

    @Test
    public void testATypeThisApiCannotCreateIsRejected()
    {
        var client = clientWithAccount("EUR");

        try
        {
            // a leg of a cross entry, never created on its own: a transfer-out
            // without its transfer-in is not a state the model has a name for
            TransactionsHandler.create(client, json("{\"type\":\"transfer-out\",\"account\":\"" + accountUuid(client)
                            + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":10,\"currency\":\"EUR\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(codesOf(e), contains("type"));
            assertThat(e.getErrors().get(0).code(), is("invalid-value"));
        }
    }

    @Test
    public void testTheBookedTransactionAppearsInTheListing()
    {
        var client = clientWithAccount("EUR");

        TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\"" + accountUuid(client)
                        + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":10,\"currency\":\"EUR\"}}"));

        var items = TransactionsHandler.list(client).getAsJsonObject().getAsJsonArray("items");
        assertThat(items.size(), is(1));
        assertThat(items.get(0).getAsJsonObject().get("uuid").getAsString(), is(only(client).getUUID()));
    }
}
