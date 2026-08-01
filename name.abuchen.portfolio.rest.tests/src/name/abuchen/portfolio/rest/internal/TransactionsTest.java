package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Values;

/**
 * The fixture is five transactions on five distinct days, spread over two cash
 * accounts and one investment account, two of them carrying the instrument.
 */
@SuppressWarnings("nls")
public class TransactionsTest
{
    private Client client;
    private Account cash;
    private Account savings;
    private Portfolio depot;
    private Security apple;

    @Before
    public void setUp()
    {
        client = new Client();
        client.setBaseCurrency("EUR");

        apple = new SecurityBuilder().addTo(client);

        cash = new AccountBuilder() //
                        .deposit_("2024-01-01", Values.Amount.factorize(1000)) //
                        .deposit_("2024-01-05", Values.Amount.factorize(500)) //
                        .dividend("2024-03-01", Values.Amount.factorize(50), apple) //
                        .addTo(client);

        savings = new AccountBuilder() //
                        .deposit_("2024-02-01", Values.Amount.factorize(200)) //
                        .addTo(client);

        depot = new PortfolioBuilder(cash) //
                        .buy(apple, "2024-02-15", PortfolioBuilder.sharesOf(10), PortfolioBuilder.amountOf(1000)) //
                        .addTo(client);
    }

    private JsonObject list(String from, String to, String account, String security)
    {
        return list(from, to, account, security, null, null);
    }

    private JsonObject list(String from, String to, String account, String security, String limit, String cursor)
    {
        return TransactionsHandler.list(client, from, to, account, security, limit, cursor).getAsJsonObject();
    }

    private static String nextCursor(JsonObject result)
    {
        var next = result.get("nextCursor");
        return next == null ? null : next.getAsString();
    }

    private static List<String> dates(JsonObject result)
    {
        var dates = new ArrayList<String>();
        for (var item : result.get("items").getAsJsonArray())
            dates.add(item.getAsJsonObject().get("date").getAsString());
        return dates;
    }

    private static List<String> owners(JsonObject result)
    {
        var uuids = new ArrayList<String>();
        for (var item : result.get("items").getAsJsonArray())
            uuids.add(item.getAsJsonObject().get("owner").getAsJsonObject().get("uuid").getAsString());
        return uuids;
    }

    /**
     * The unfiltered call is what the one existing consumer makes, so it must
     * keep returning the whole file, newest first.
     */
    @Test
    public void testWithoutFiltersEveryTransactionComesBackNewestFirst()
    {
        var result = list(null, null, null, null);

        assertThat(dates(result), contains("2024-03-01T00:00", "2024-02-15T00:00", "2024-02-01T00:00",
                        "2024-01-05T00:00", "2024-01-01T00:00"));
    }

    @Test
    public void testBothDateBoundsAreInclusive()
    {
        var result = list("2024-01-05", "2024-02-15", null, null);

        assertThat(dates(result), contains("2024-02-15T00:00", "2024-02-01T00:00", "2024-01-05T00:00"));
    }

    @Test
    public void testASingleDayIsAValidRange()
    {
        assertThat(dates(list("2024-02-01", "2024-02-01", null, null)), contains("2024-02-01T00:00"));
    }

    @Test
    public void testTheAccountFilterMatchesACashAccount()
    {
        var result = list(null, null, cash.getUUID(), null);

        assertThat(dates(result), contains("2024-03-01T00:00", "2024-01-05T00:00", "2024-01-01T00:00"));
        assertThat(owners(result), contains(cash.getUUID(), cash.getUUID(), cash.getUUID()));
    }

    /**
     * A buy is recorded on both sides but reported once, as its investment
     * account leg - so the same parameter that selects a cash account selects
     * the depot here, and the buy does not also answer to the reference
     * account's uuid.
     */
    @Test
    public void testTheAccountFilterMatchesAnInvestmentAccount()
    {
        assertThat(dates(list(null, null, depot.getUUID(), null)), contains("2024-02-15T00:00"));
        assertThat(dates(list(null, null, savings.getUUID(), null)), contains("2024-02-01T00:00"));
    }

    @Test
    public void testTheSecurityFilterKeepsOnlyTransactionsCarryingTheInstrument()
    {
        var result = list(null, null, null, apple.getUUID());

        assertThat(dates(result), contains("2024-03-01T00:00", "2024-02-15T00:00"));
    }

    @Test
    public void testTheFiltersCombine()
    {
        assertThat(dates(list(null, null, cash.getUUID(), apple.getUUID())), contains("2024-03-01T00:00"));

        // the same pair, but the one match is outside the range
        assertThat(dates(list("2024-01-01", "2024-02-28", cash.getUUID(), apple.getUUID())).size(), is(0));
    }

    @Test
    public void testAnInvertedRangeIsRejected()
    {
        assertFieldError(() -> list("2024-03-01", "2024-01-01", null, null), "to", "invalid-range");
    }

    @Test
    public void testAMalformedDateIsRejected()
    {
        assertFieldError(() -> list("01/02/2024", null, null, null), "from", "invalid-value");
        assertFieldError(() -> list(null, "tomorrow", null, null), "to", "invalid-value");
    }

    @Test
    public void testAnUnknownOwnerOrInstrumentIsRejected()
    {
        assertFieldError(() -> list(null, null, "no-such-uuid", null), "account", "invalid-value");
        assertFieldError(() -> list(null, null, null, "no-such-uuid"), "security", "invalid-value");
    }

    /** Every unusable parameter is reported at once, not just the first. */
    @Test
    public void testEveryParameterProblemIsReported()
    {
        try
        {
            list("yesterday", null, "no-such-uuid", "no-such-uuid");
            Assert.fail("expected ApiException");
        }
        catch (ApiException e)
        {
            assertThat(e.getErrors().size(), is(3));
            assertThat(e.getErrors().get(0).field(), is("from"));
            assertThat(e.getErrors().get(1).field(), is("account"));
            assertThat(e.getErrors().get(2).field(), is("security"));
        }
    }

    /**
     * The default must stay "everything": the one existing consumer loads a
     * file's transactions once and filters locally, so pagination has to be
     * opt-in.
     */
    @Test
    public void testWithoutALimitTheWholeListComesBackWithoutACursor()
    {
        var result = list(null, null, null, null);

        assertThat(dates(result).size(), is(5));
        assertThat(nextCursor(result), is(nullValue()));
    }

    @Test
    public void testALimitYieldsExactlyThatManyPlusACursor()
    {
        var result = list(null, null, null, null, "2", null);

        assertThat(dates(result), contains("2024-03-01T00:00", "2024-02-15T00:00"));
        assertThat(nextCursor(result), is(notNullValue()));
    }

    @Test
    public void testFollowingTheCursorHasNoOverlapAndNoGap()
    {
        var whole = dates(list(null, null, null, null));

        var collected = new ArrayList<String>();
        String cursor = null;
        do
        {
            var page = list(null, null, null, null, "2", cursor);
            collected.addAll(dates(page));
            cursor = nextCursor(page);
        }
        while (cursor != null);

        assertThat(collected, is(whole));
    }

    /** A page that exhausts the list carries no cursor, even when it is full. */
    @Test
    public void testTheLastPageHasNoCursor()
    {
        var exact = list(null, null, null, null, "5", null);
        assertThat(dates(exact).size(), is(5));
        assertThat(nextCursor(exact), is(nullValue()));

        var remainder = list(null, null, null, null, "3", nextCursor(list(null, null, null, null, "3", null)));
        assertThat(dates(remainder), contains("2024-01-05T00:00", "2024-01-01T00:00"));
        assertThat(nextCursor(remainder), is(nullValue()));
    }

    /**
     * A cursor carries a position, not a query - so the filters have to be
     * repeated, and when they are the second page is the continuation of the
     * filtered list rather than of the whole file.
     */
    @Test
    public void testACursorKeepsTheFilters()
    {
        var first = list(null, null, null, apple.getUUID(), "1", null);
        assertThat(dates(first), contains("2024-03-01T00:00"));

        var second = list(null, null, null, apple.getUUID(), "1", nextCursor(first));
        assertThat(dates(second), contains("2024-02-15T00:00"));
        assertThat(nextCursor(second), is(nullValue()));
    }

    @Test
    public void testAnUnusableLimitIsRejected()
    {
        assertFieldError(() -> list(null, null, null, null, "0", null), "limit", "invalid-value");
        assertFieldError(() -> list(null, null, null, null, "1001", null), "limit", "invalid-value");
        assertFieldError(() -> list(null, null, null, null, "all", null), "limit", "invalid-value");
    }

    @Test
    public void testAMalformedCursorIsRejected()
    {
        assertFieldError(() -> list(null, null, null, null, "2", "not a cursor!"), "cursor", "invalid-value");
        assertFieldError(() -> list(null, null, null, null, "2", encode("no-separator")), "cursor", "invalid-value");
        assertFieldError(() -> list(null, null, null, null, "2", encode("yesterday|some-uuid")), "cursor",
                        "invalid-value");
    }

    private static String encode(String raw)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
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
