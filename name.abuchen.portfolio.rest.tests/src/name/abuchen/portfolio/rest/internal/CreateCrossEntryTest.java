package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class CreateCrossEntryTest
{
    private static JsonObject json(String body)
    {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static List<String> fieldsOf(ApiException e)
    {
        return e.getErrors().stream().map(ApiException.FieldError::field).toList();
    }

    /** A client with one CHF cash account, one investment account and a CHF instrument. */
    private static Client fixture()
    {
        var client = new Client();
        var account = new AccountBuilder("CHF").addTo(client);
        new PortfolioBuilder(account).addTo(client);
        new SecurityBuilder("CHF").addTo(client);
        return client;
    }

    private static Account account(Client client)
    {
        return client.getAccounts().get(0);
    }

    private static Portfolio portfolio(Client client)
    {
        return client.getPortfolios().get(0);
    }

    private static Security security(Client client)
    {
        return client.getSecurities().get(0);
    }

    private static PortfolioTransaction onlyPortfolioTransaction(Client client)
    {
        var transactions = portfolio(client).getTransactions();
        assertThat(transactions.size(), is(1));
        return transactions.get(0);
    }

    // ------------------------------------------------------------- buy / sell

    @Test
    public void testABuyBooksBothLegsWithTheCostsOnTop()
    {
        var client = fixture();

        var result = TransactionsHandler.create(client, json("{\"type\":\"buy\",\"portfolio\":\""
                        + portfolio(client).getUUID() + "\",\"account\":\"" + account(client).getUUID()
                        + "\",\"security\":\"" + security(client).getUUID() + "\",\"date\":\"2026-07-20\","
                        + "\"shares\":10,\"amount\":{\"value\":1009.90,\"currency\":\"CHF\"},"
                        + "\"fees\":{\"value\":9.90,\"currency\":\"CHF\"}}"));

        var portfolioLeg = onlyPortfolioTransaction(client);
        assertThat(portfolioLeg.getType(), is(PortfolioTransaction.Type.BUY));
        assertThat(portfolioLeg.getAmount(), is(100990L));
        assertThat(portfolioLeg.getShares(), is(10L * Values.Share.factor()));

        // a purchase pays its costs on top, so the gross is the total less them
        assertThat(portfolioLeg.getGrossValueAmount(), is(100000L));

        // and the cash account really was debited: both legs, one call
        assertThat(account(client).getTransactions().size(), is(1));
        assertThat(account(client).getTransactions().get(0).getAmount(), is(100990L));

        // the reported leg is the one the read endpoint reports for a trade
        assertThat(result.entity().getAsJsonObject().get("owner").getAsJsonObject().get("type").getAsString(),
                        is("investment-account"));
        assertThat(result.typeName(), is("buy"));
    }

    @Test
    public void testASaleHasItsCostsTakenOutOfTheGross()
    {
        var client = fixture();

        TransactionsHandler.create(client, json("{\"type\":\"sell\",\"portfolio\":\"" + portfolio(client).getUUID()
                        + "\",\"account\":\"" + account(client).getUUID() + "\",\"security\":\""
                        + security(client).getUUID() + "\",\"date\":\"2026-07-20\",\"shares\":10,"
                        + "\"amount\":{\"value\":990.10,\"currency\":\"CHF\"},"
                        + "\"fees\":{\"value\":9.90,\"currency\":\"CHF\"}}"));

        assertThat(onlyPortfolioTransaction(client).getGrossValueAmount(), is(100000L));
    }

    @Test
    public void testATradeNeedsShares()
    {
        var client = fixture();

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"buy\",\"portfolio\":\"" + portfolio(client).getUUID()
                            + "\",\"account\":\"" + account(client).getUUID() + "\",\"security\":\""
                            + security(client).getUUID() + "\",\"date\":\"2026-07-20\","
                            + "\"amount\":{\"value\":100,\"currency\":\"CHF\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(fieldsOf(e), contains("shares"));
            assertThat(e.getErrors().get(0).code(), is("required"));
        }
    }

    @Test
    public void testASaleThatLeavesNothingIsAnOutboundDelivery()
    {
        var client = fixture();

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"sell\",\"portfolio\":\"" + portfolio(client).getUUID()
                            + "\",\"account\":\"" + account(client).getUUID() + "\",\"security\":\""
                            + security(client).getUUID() + "\",\"date\":\"2026-07-20\",\"shares\":10,"
                            + "\"amount\":{\"value\":0,\"currency\":\"CHF\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(fieldsOf(e), contains("amount.value"));
        }

        assertThat(portfolio(client).getTransactions().isEmpty(), is(true));
    }

    @Test
    public void testTheBrokersExchangeRateIsRecordedOnAForeignCurrencyTrade()
    {
        var client = fixture();
        var usd = new SecurityBuilder("USD").addTo(client);

        TransactionsHandler.create(client, json("{\"type\":\"buy\",\"portfolio\":\"" + portfolio(client).getUUID()
                        + "\",\"account\":\"" + account(client).getUUID() + "\",\"security\":\"" + usd.getUUID()
                        + "\",\"date\":\"2026-07-20\",\"shares\":10,"
                        + "\"amount\":{\"value\":800,\"currency\":\"CHF\"},"
                        + "\"forexGrossAmount\":{\"value\":1000,\"currency\":\"USD\"},\"exchangeRate\":0.8}"));

        var unit = onlyPortfolioTransaction(client).getUnit(Transaction.Unit.Type.GROSS_VALUE).orElse(null);
        assertThat(unit, notNullValue());
        assertThat(unit.getForex().getAmount(), is(100000L));
        assertThat(unit.getAmount().getAmount(), is(80000L));
    }

    @Test
    public void testAnExchangeRateThatDoesNotAgreeIsRejectedRatherThanStored()
    {
        var client = fixture();
        var usd = new SecurityBuilder("USD").addTo(client);

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"buy\",\"portfolio\":\"" + portfolio(client).getUUID()
                            + "\",\"account\":\"" + account(client).getUUID() + "\",\"security\":\"" + usd.getUUID()
                            + "\",\"date\":\"2026-07-20\",\"shares\":10,"
                            + "\"amount\":{\"value\":800,\"currency\":\"CHF\"},"
                            + "\"forexGrossAmount\":{\"value\":1000,\"currency\":\"USD\"},\"exchangeRate\":0.5}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(fieldsOf(e), contains("exchangeRate"));
            assertThat(e.getErrors().get(0).code(), is("inconsistent-total"));
        }
    }

    @Test
    public void testAnExchangeRateOnAnAlreadyLocalInstrumentIsRejected()
    {
        var client = fixture();

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"buy\",\"portfolio\":\"" + portfolio(client).getUUID()
                            + "\",\"account\":\"" + account(client).getUUID() + "\",\"security\":\""
                            + security(client).getUUID() + "\",\"date\":\"2026-07-20\",\"shares\":10,"
                            + "\"amount\":{\"value\":800,\"currency\":\"CHF\"},"
                            + "\"forexGrossAmount\":{\"value\":800,\"currency\":\"CHF\"},\"exchangeRate\":1}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(fieldsOf(e), contains("exchangeRate"));
            assertThat(e.getErrors().get(0).code(), is("not-applicable"));
        }
    }

    // -------------------------------------------------------------- deliveries

    @Test
    public void testAnInboundDeliveryTouchesNoCashAccount()
    {
        var client = fixture();

        TransactionsHandler.create(client, json("{\"type\":\"delivery-inbound\",\"portfolio\":\""
                        + portfolio(client).getUUID() + "\",\"security\":\"" + security(client).getUUID()
                        + "\",\"date\":\"2026-07-20\",\"shares\":5,"
                        + "\"amount\":{\"value\":500,\"currency\":\"CHF\"}}"));

        assertThat(onlyPortfolioTransaction(client).getType(), is(PortfolioTransaction.Type.DELIVERY_INBOUND));
        assertThat(account(client).getTransactions().isEmpty(), is(true));
    }

    @Test
    public void testAnOutboundDeliveryMayBeWorthNothing()
    {
        var client = fixture();

        TransactionsHandler.create(client, json("{\"type\":\"delivery-outbound\",\"portfolio\":\""
                        + portfolio(client).getUUID() + "\",\"security\":\"" + security(client).getUUID()
                        + "\",\"date\":\"2026-07-20\",\"shares\":5,"
                        + "\"amount\":{\"value\":0,\"currency\":\"CHF\"}}"));

        assertThat(onlyPortfolioTransaction(client).getAmount(), is(0L));
    }

    @Test
    public void testADeliveryNamesItsOwnCurrencyAndItMustBeAKnownOne()
    {
        var client = fixture();

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"delivery-inbound\",\"portfolio\":\""
                            + portfolio(client).getUUID() + "\",\"security\":\"" + security(client).getUUID()
                            + "\",\"date\":\"2026-07-20\",\"shares\":5,"
                            + "\"amount\":{\"value\":500,\"currency\":\"XYZ\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(fieldsOf(e), contains("amount.currency"));
            assertThat(e.getErrors().get(0).code(), is("unknown-currency"));
        }
    }

    // --------------------------------------------------------------- transfers

    @Test
    public void testASameCurrencyTransferMovesOneAmountBetweenTwoAccounts()
    {
        var client = fixture();
        var other = new AccountBuilder("CHF").addTo(client);

        TransactionsHandler.create(client, json("{\"type\":\"transfer\",\"fromAccount\":\""
                        + account(client).getUUID() + "\",\"toAccount\":\"" + other.getUUID()
                        + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":100,\"currency\":\"CHF\"}}"));

        assertThat(account(client).getTransactions().get(0).getAmount(), is(10000L));
        assertThat(other.getTransactions().get(0).getAmount(), is(10000L));
    }

    @Test
    public void testACrossCurrencyTransferNeedsAndRecordsTheAmountReceived()
    {
        var client = fixture();
        var usdAccount = new AccountBuilder("USD").addTo(client);

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"transfer\",\"fromAccount\":\""
                            + account(client).getUUID() + "\",\"toAccount\":\"" + usdAccount.getUUID()
                            + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":100,\"currency\":\"CHF\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(fieldsOf(e), contains("toAmount"));
            assertThat(e.getErrors().get(0).code(), is("required"));
        }

        TransactionsHandler.create(client, json("{\"type\":\"transfer\",\"fromAccount\":\""
                        + account(client).getUUID() + "\",\"toAccount\":\"" + usdAccount.getUUID()
                        + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":100,\"currency\":\"CHF\"},"
                        + "\"toAmount\":{\"value\":125,\"currency\":\"USD\"}}"));

        assertThat(account(client).getTransactions().get(0).getAmount(), is(10000L));
        assertThat(usdAccount.getTransactions().get(0).getAmount(), is(12500L));

        // the rate is derived from the two amounts rather than asked for
        var unit = account(client).getTransactions().get(0).getUnit(Transaction.Unit.Type.GROSS_VALUE).orElse(null);
        assertThat(unit, notNullValue());
        assertThat(unit.getForex().getAmount(), is(12500L));
    }

    @Test
    public void testATransferNeedsTwoDifferentAccounts()
    {
        var client = fixture();

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"transfer\",\"fromAccount\":\""
                            + account(client).getUUID() + "\",\"toAccount\":\"" + account(client).getUUID()
                            + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":100,\"currency\":\"CHF\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(fieldsOf(e), contains("toAccount"));
        }
    }

    @Test
    public void testASecurityTransferIsValuedInTheInstrumentsOwnCurrency()
    {
        var client = fixture();
        var target = new PortfolioBuilder(account(client)).addTo(client);
        var usd = new SecurityBuilder("USD").addTo(client);

        TransactionsHandler.create(client, json("{\"type\":\"security-transfer\",\"fromPortfolio\":\""
                        + portfolio(client).getUUID() + "\",\"toPortfolio\":\"" + target.getUUID()
                        + "\",\"security\":\"" + usd.getUUID() + "\",\"date\":\"2026-07-20\",\"shares\":3,"
                        + "\"amount\":{\"value\":300,\"currency\":\"USD\"}}"));

        assertThat(portfolio(client).getTransactions().get(0).getCurrencyCode(), is("USD"));
        assertThat(target.getTransactions().get(0).getShares(), is(3L * Values.Share.factor()));
    }

    // ------------------------------------------------------- fields and shapes

    @Test
    public void testAFieldOfAnotherTypeIsRejectedAsNotApplicableRatherThanUnknown()
    {
        var client = fixture();

        try
        {
            TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\""
                            + account(client).getUUID() + "\",\"portfolio\":\"" + portfolio(client).getUUID()
                            + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":10,\"currency\":\"CHF\"},"
                            + "\"nonsense\":1}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(fieldsOf(e), containsInAnyOrder("portfolio", "nonsense"));
            var codes = e.getErrors().stream()
                            .collect(java.util.stream.Collectors.toMap(ApiException.FieldError::field,
                                            ApiException.FieldError::code));
            assertThat(codes.get("portfolio"), is("not-applicable"));
            assertThat(codes.get("nonsense"), is("unknown-field"));
        }
    }

    // ------------------------------------------------------------ idempotency

    @Test
    public void testASuppliedUuidBecomesTheTransactionsIdentity()
    {
        var client = fixture();
        var uuid = "3f1c9a52-6b47-4d0e-9a71-2c8e5b04d913";

        var result = TransactionsHandler.create(client, json("{\"uuid\":\"" + uuid + "\",\"type\":\"deposit\","
                        + "\"account\":\"" + account(client).getUUID() + "\",\"date\":\"2026-07-20\","
                        + "\"amount\":{\"value\":10,\"currency\":\"CHF\"}}"));

        assertThat(result.uuid(), is(uuid));
        assertThat(account(client).getTransactions().get(0).getUUID(), is(uuid));
    }

    @Test
    public void testAReplayIsRefusedInsteadOfBookedTwice()
    {
        var client = fixture();
        var uuid = "3f1c9a52-6b47-4d0e-9a71-2c8e5b04d913";
        var body = "{\"uuid\":\"" + uuid + "\",\"type\":\"deposit\",\"account\":\"" + account(client).getUUID()
                        + "\",\"date\":\"2026-07-20\",\"amount\":{\"value\":10,\"currency\":\"CHF\"}}";

        TransactionsHandler.create(client, json(body));

        try
        {
            TransactionsHandler.create(client, json(body));
            Assert.fail("expected a conflict");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(409));
            assertThat(e.getType(), is("duplicate-uuid"));
        }

        assertThat(account(client).getTransactions().size(), is(1));
    }

    @Test
    public void testAUuidCollidingWithAHiddenCrossLegIsStillACollision()
    {
        var client = fixture();
        var uuid = "3f1c9a52-6b47-4d0e-9a71-2c8e5b04d913";

        // the uuid lands on the portfolio leg; the account leg is the hidden one
        TransactionsHandler.create(client, json("{\"uuid\":\"" + uuid + "\",\"type\":\"buy\",\"portfolio\":\""
                        + portfolio(client).getUUID() + "\",\"account\":\"" + account(client).getUUID()
                        + "\",\"security\":\"" + security(client).getUUID() + "\",\"date\":\"2026-07-20\","
                        + "\"shares\":1,\"amount\":{\"value\":100,\"currency\":\"CHF\"}}"));

        var hidden = account(client).getTransactions().get(0).getUUID();

        try
        {
            TransactionsHandler.create(client, json("{\"uuid\":\"" + hidden + "\",\"type\":\"deposit\","
                            + "\"account\":\"" + account(client).getUUID() + "\",\"date\":\"2026-07-20\","
                            + "\"amount\":{\"value\":10,\"currency\":\"CHF\"}}"));
            Assert.fail("expected a conflict");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(409));
        }
    }

    @Test
    public void testAnUnparsableUuidIsRejected()
    {
        var client = fixture();

        try
        {
            TransactionsHandler.create(client, json("{\"uuid\":\"not-a-uuid\",\"type\":\"deposit\",\"account\":\""
                            + account(client).getUUID() + "\",\"date\":\"2026-07-20\","
                            + "\"amount\":{\"value\":10,\"currency\":\"CHF\"}}"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(fieldsOf(e), contains("uuid"));
        }
    }

    // ---------------------------------------------------------------- deletion

    @Test
    public void testDeletingOneLegOfATradeRemovesBoth()
    {
        var client = fixture();

        var result = TransactionsHandler.create(client, json("{\"type\":\"buy\",\"portfolio\":\""
                        + portfolio(client).getUUID() + "\",\"account\":\"" + account(client).getUUID()
                        + "\",\"security\":\"" + security(client).getUUID() + "\",\"date\":\"2026-07-20\","
                        + "\"shares\":1,\"amount\":{\"value\":100,\"currency\":\"CHF\"}}"));

        var deleted = TransactionsHandler.delete(client, result.uuid());

        assertThat(deleted.typeName(), is("buy"));
        assertThat(portfolio(client).getTransactions().isEmpty(), is(true));
        assertThat(account(client).getTransactions().isEmpty(), is(true));
    }

    @Test
    public void testTheHiddenLegMayBeNamedToo()
    {
        var client = fixture();

        TransactionsHandler.create(client, json("{\"type\":\"buy\",\"portfolio\":\"" + portfolio(client).getUUID()
                        + "\",\"account\":\"" + account(client).getUUID() + "\",\"security\":\""
                        + security(client).getUUID() + "\",\"date\":\"2026-07-20\",\"shares\":1,"
                        + "\"amount\":{\"value\":100,\"currency\":\"CHF\"}}"));

        TransactionsHandler.delete(client, account(client).getTransactions().get(0).getUUID());

        assertThat(portfolio(client).getTransactions().isEmpty(), is(true));
        assertThat(account(client).getTransactions().isEmpty(), is(true));
    }

    @Test
    public void testDeletingSomethingThatIsNotThereIsNotFound()
    {
        var client = fixture();

        try
        {
            TransactionsHandler.delete(client, "3f1c9a52-6b47-4d0e-9a71-2c8e5b04d913");
            Assert.fail("expected a not-found");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(404));
        }
    }

    @Test
    public void testDeletingACashTransactionLeavesTheRestAlone()
    {
        var client = fixture();

        var kept = TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\""
                        + account(client).getUUID() + "\",\"date\":\"2026-07-20\","
                        + "\"amount\":{\"value\":10,\"currency\":\"CHF\"}}"));
        var removed = TransactionsHandler.create(client, json("{\"type\":\"deposit\",\"account\":\""
                        + account(client).getUUID() + "\",\"date\":\"2026-07-21\","
                        + "\"amount\":{\"value\":20,\"currency\":\"CHF\"}}"));

        TransactionsHandler.delete(client, removed.uuid());

        assertThat(account(client).getTransactions().size(), is(1));
        assertThat(account(client).getTransactions().get(0).getUUID(), is(kept.uuid()));
        assertThat(TransactionsHandler.findAnyLeg(client, removed.uuid()), nullValue());
    }
}
