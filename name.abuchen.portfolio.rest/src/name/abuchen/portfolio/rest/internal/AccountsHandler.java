package name.abuchen.portfolio.rest.internal;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.google.gson.JsonElement;

import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.AccountSnapshot;

public final class AccountsHandler
{
    /** same bound as the performance series, for the same one-entry-per-day reason */
    private static final int MAX_SERIES_YEARS = 20;

    private AccountsHandler()
    {
    }

    /**
     * Lists every cash account with its current balance at the given date
     * (default: today), in the account's own currency - a plain running total
     * of its transactions, not affected by exchange rates. The factory is the
     * host's per-file instance - it registers a listener on the client, so
     * this handler must not construct its own.
     */
    public static JsonElement list(Client client, ExchangeRateProviderFactory factory, String dateParam)
    {
        var date = parseDate(dateParam);
        var converter = new CurrencyConverterImpl(factory, client.getBaseCurrency());
        return EntityJson.envelope(client.getAccounts(), account -> EntityJson.toJson(account, balanceOf(account, converter, date)));
    }

    public static JsonElement get(Client client, ExchangeRateProviderFactory factory, String uuid, String dateParam)
    {
        var date = parseDate(dateParam);
        var converter = new CurrencyConverterImpl(factory, client.getBaseCurrency());
        var account = Entities.byUuid(client.getAccounts(), Account::getUUID, uuid);
        return EntityJson.toJson(account, balanceOf(account, converter, date));
    }

    /**
     * Creates a new, empty cash account in the given currency, exactly as the
     * application's own "new account" does. {@code name} is required and
     * non-blank; {@code currencyCode} is required and must be one of the
     * currencies the application knows ({@link CurrencyUnit#getInstance}).
     */
    public static JsonObject create(Client client, JsonObject body)
    {
        var errors = new ArrayList<ApiException.FieldError>();
        var name = TaxonomiesHandler.requiredText(body, "name", errors); //$NON-NLS-1$
        var currencyCode = TaxonomiesHandler.requiredText(body, "currencyCode", errors); //$NON-NLS-1$

        if (currencyCode != null && CurrencyUnit.getInstance(currencyCode) == null)
            errors.add(new ApiException.FieldError("currencyCode", "invalid-value", //$NON-NLS-1$ //$NON-NLS-2$
                            "currencyCode must be a known ISO 4217 currency code")); //$NON-NLS-1$

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        var account = new Account(name);
        account.setCurrencyCode(currencyCode);

        client.addAccount(account);
        client.markDirty();

        return EntityJson.toJson(account, Money.of(currencyCode, 0));
    }

    private static Money balanceOf(Account account, CurrencyConverterImpl converter, LocalDate date)
    {
        return AccountSnapshot.create(account, converter, date).getUnconvertedFunds();
    }

    /**
     * The balance {@link #balanceOf} reports for one date, reported for the end of
     * every day of a range at once - in the account's own currency, never
     * converted, computed in one pass over the ledger rather than one snapshot per
     * day. One request instead of one per day, which is what a consumer
     * reconstructing balance episodes (a margin account's time under water, say)
     * needs; the per-account running balance cannot be rebuilt from the
     * transactions list, whose de-duplication reports a cross entry's cash leg
     * under the other owner.
     * <p>
     * {@code from} and {@code to} are optional and inclusive: {@code from}
     * defaults to the account's first transaction, {@code to} to today. The
     * response echoes the range it covers, and an account with no transactions
     * (or a range ending before its first one) covers nothing rather than
     * reporting a flat line of zeros. Capped like the performance series, and for
     * the same reason: one entry per calendar day.
     */
    public static JsonElement balances(Client client, String uuid, String fromParam, String toParam)
    {
        var account = Entities.byUuid(client.getAccounts(), Account::getUUID, uuid);

        var errors = new ArrayList<ApiException.FieldError>();
        var from = parseDate("from", fromParam, errors);
        var to = parseDate("to", toParam, errors);

        if (!errors.isEmpty())
            throw ApiException.badRequest(errors);

        // closed range, like the price series: a single day is meaningful and only
        // from *after* to is contradictory
        if (from != null && to != null && from.isAfter(to))
            throw ApiException.badRequest(List.of(new ApiException.FieldError("to", "invalid-range",
                            "to must not be before from")));

        var deltas = new TreeMap<LocalDate, Long>();
        for (var transaction : account.getTransactions())
            deltas.merge(transaction.getDateTime().toLocalDate(),
                            transaction.getType().isDebit() ? -transaction.getAmount() : transaction.getAmount(),
                            Long::sum);

        var start = from != null ? from : (deltas.isEmpty() ? null : deltas.firstKey());
        var end = to != null ? to : LocalDate.now();

        if (start == null || end.isBefore(start))
            return EntityJson.accountBalances(account, null, new long[0]);

        if (start.plusYears(MAX_SERIES_YEARS).isBefore(end))
            throw ApiException.badRequest(List.of(new ApiException.FieldError("to", "invalid-range",
                            "the range must not exceed " + MAX_SERIES_YEARS + " years")));

        var balance = deltas.headMap(start, false).values().stream().mapToLong(Long::longValue).sum();

        var days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        var balances = new long[days];
        for (var i = 0; i < days; i++)
        {
            var delta = deltas.get(start.plusDays(i));
            if (delta != null)
                balance += delta.longValue();
            balances[i] = balance;
        }

        return EntityJson.accountBalances(account, start, balances);
    }

    private static LocalDate parseDate(String field, String value, List<ApiException.FieldError> errors)
    {
        if (value == null)
            return null;

        try
        {
            return LocalDate.parse(value);
        }
        catch (DateTimeParseException e)
        {
            errors.add(new ApiException.FieldError(field, "invalid-value",
                            field + " must be an ISO 8601 date (YYYY-MM-DD)"));
            return null;
        }
    }

    private static LocalDate parseDate(String dateParam)
    {
        if (dateParam == null)
            return LocalDate.now();

        try
        {
            return LocalDate.parse(dateParam);
        }
        catch (DateTimeParseException e)
        {
            throw ApiException.badRequest(List.of(new ApiException.FieldError("date", "invalid-value", //$NON-NLS-1$ //$NON-NLS-2$
                            "date must be an ISO 8601 date (YYYY-MM-DD)"))); //$NON-NLS-1$
        }
    }
}
