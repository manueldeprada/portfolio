package name.abuchen.portfolio.rest.internal;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

public final class TransactionsHandler
{
    /**
     * The cash-account transaction types this API can create. Buys, sells,
     * transfers and deliveries are deliberately absent: each is a
     * {@code CrossEntry} spanning two owners, which is a different request
     * shape and a different set of rules.
     */
    private static final Map<String, AccountTransaction.Type> CREATABLE_TYPES = Map.of( //
                    "deposit", AccountTransaction.Type.DEPOSIT, //
                    "removal", AccountTransaction.Type.REMOVAL, //
                    "interest", AccountTransaction.Type.INTEREST, //
                    "interest-charge", AccountTransaction.Type.INTEREST_CHARGE, //
                    "dividends", AccountTransaction.Type.DIVIDENDS, //
                    "fees", AccountTransaction.Type.FEES, //
                    "fees-refund", AccountTransaction.Type.FEES_REFUND, //
                    "taxes", AccountTransaction.Type.TAXES, //
                    "tax-refund", AccountTransaction.Type.TAX_REFUND);

    private static final Set<String> KNOWN_FIELDS = Set.of("type", "account", "date", "amount", "grossAmount", "fees",
                    "taxes", "security", "shares", "exDate", "note");

    public record CreateResult(JsonElement entity, String uuid, String typeName, String accountName)
    {
    }

    private TransactionsHandler()
    {
    }

    /**
     * Every transaction across every account and investment account, newest
     * first. {@link Client#getAllTransactions()} already de-duplicates a
     * buy/sell pair (only the investment-account side survives) and a transfer
     * (only the outbound leg survives).
     */
    public static JsonElement list(Client client)
    {
        var transactions = new ArrayList<>(client.getAllTransactions());
        transactions.sort(TransactionPair.BY_DATE.reversed());
        return EntityJson.envelope(transactions, EntityJson::toJson);
    }

    /**
     * Books a cash-account transaction. Every violation is collected and
     * reported at once, and nothing is applied unless the whole request
     * validates - a partially booked transaction would be worse than none.
     * <p/>
     * The rules enforced here are the transaction dialogs', restated: nothing
     * in the model enforces them - {@link Account#addTransaction} validates
     * nothing at all - so an API that skipped them would accept transactions
     * the desktop application refuses to create.
     * <p/>
     * {@code amount} is the net cash flow and is unsigned: the direction
     * belongs to the type, so a removal of -50 is a validation error rather
     * than a deposit. The gross value is derived from it and the fee and tax
     * units; a {@code grossAmount} sent along is checked against that
     * derivation rather than believed.
     */
    public static CreateResult create(Client client, JsonObject body)
    {
        var errors = new ArrayList<ApiException.FieldError>();

        for (var field : body.keySet())
            if (!KNOWN_FIELDS.contains(field))
                errors.add(new ApiException.FieldError(field, "unknown-field", "field is not writable"));

        var type = readType(body, errors);
        var account = readAccount(client, body, errors);
        var date = readDateTime(body, "date", errors, true);
        var currency = account == null ? null : account.getCurrencyCode();

        var amount = readMoney(body, "amount", currency, errors, true);
        var fees = readMoney(body, "fees", currency, errors, false);
        var taxes = readMoney(body, "taxes", currency, errors, false);
        var grossAmount = readMoney(body, "grossAmount", currency, errors, false);
        var security = readSecurity(client, body, errors);
        var shares = readShares(body, errors);
        var exDate = readDateTime(body, "exDate", errors, false);
        var note = readNote(body, errors);

        if (type != null)
            checkApplicability(type, body, shares, fees, taxes, errors);
        checkAmounts(type, amount, fees, taxes, grossAmount, errors);
        checkExDate(body, date, exDate, errors);

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        var transaction = new AccountTransaction();
        transaction.setType(type);
        transaction.setCurrencyCode(currency);
        transaction.setDateTime(date);
        transaction.setAmount(amount);
        transaction.setSecurity(security);
        transaction.setShares(shares == null ? 0 : shares);
        transaction.setExDate(exDate);
        transaction.setNote(note);

        if (fees != null && fees != 0)
            transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of(currency, fees)));
        if (taxes != null && taxes != 0)
            transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of(currency, taxes)));

        account.addTransaction(transaction);
        client.markDirty();

        return new CreateResult(EntityJson.toJson(new TransactionPair<>(account, transaction)), transaction.getUUID(),
                        wireName(type), account.getName());
    }

    private static AccountTransaction.Type readType(JsonObject body, List<ApiException.FieldError> errors)
    {
        var element = body.get("type");
        if (element == null || element.isJsonNull())
        {
            errors.add(new ApiException.FieldError("type", "required", "type is required"));
            return null;
        }
        if (!isString(element))
        {
            errors.add(new ApiException.FieldError("type", "invalid-type", "type must be a string"));
            return null;
        }

        var type = CREATABLE_TYPES.get(element.getAsString());
        if (type == null)
            errors.add(new ApiException.FieldError("type", "invalid-value",
                            element.getAsString() + " is not a cash-account transaction type"));
        return type;
    }

    private static Account readAccount(Client client, JsonObject body, List<ApiException.FieldError> errors)
    {
        var uuid = readUuid(body, "account", errors, true);
        if (uuid == null)
            return null;

        var account = client.getAccounts().stream().filter(a -> uuid.equals(a.getUUID())).findAny().orElse(null);
        if (account == null)
            errors.add(new ApiException.FieldError("account", "unknown-reference", "no such cash account"));
        return account;
    }

    private static Security readSecurity(Client client, JsonObject body, List<ApiException.FieldError> errors)
    {
        var uuid = readUuid(body, "security", errors, false);
        if (uuid == null)
            return null;

        var security = client.getSecurities().stream().filter(s -> uuid.equals(s.getUUID())).findAny().orElse(null);
        if (security == null)
            errors.add(new ApiException.FieldError("security", "unknown-reference", "no such instrument"));
        return security;
    }

    private static String readUuid(JsonObject body, String field, List<ApiException.FieldError> errors,
                    boolean required)
    {
        var element = body.get(field);
        if (element == null || element.isJsonNull())
        {
            if (required)
                errors.add(new ApiException.FieldError(field, "required", field + " is required"));
            return null;
        }
        if (!isString(element))
        {
            errors.add(new ApiException.FieldError(field, "invalid-type", field + " must be a string"));
            return null;
        }
        return element.getAsString();
    }

    /**
     * A date, or a date and time. The model stores a {@link LocalDateTime} and
     * the dialogs default the time, so a plain date is accepted and taken as
     * midnight rather than rejected: a client booking a deposit has no
     * meaningful time to send.
     */
    private static LocalDateTime readDateTime(JsonObject body, String field, List<ApiException.FieldError> errors,
                    boolean required)
    {
        var element = body.get(field);
        if (element == null || element.isJsonNull())
        {
            if (required)
                errors.add(new ApiException.FieldError(field, "required", field + " is required"));
            return null;
        }
        if (!isString(element))
        {
            errors.add(new ApiException.FieldError(field, "invalid-type", field + " must be a string"));
            return null;
        }

        var text = element.getAsString();
        try
        {
            return LocalDateTime.parse(text);
        }
        catch (DateTimeException e)
        {
            // not a date-time; fall through to the date-only form
        }

        try
        {
            return LocalDate.parse(text).atStartOfDay();
        }
        catch (DateTimeException e)
        {
            errors.add(new ApiException.FieldError(field, "invalid-value",
                            field + " must be an ISO date or date-time"));
            return null;
        }
    }

    /**
     * A monetary amount in the wire shape the reads use, {@code {value,
     * currency}}. The currency is required and checked against the account's
     * own: a cash-account transaction is denominated in its account's currency,
     * and silently converting - or silently ignoring - a mismatch would book a
     * figure the caller never sent.
     */
    private static Long readMoney(JsonObject body, String field, String accountCurrency,
                    List<ApiException.FieldError> errors, boolean required)
    {
        var element = body.get(field);
        if (element == null || element.isJsonNull())
        {
            if (required)
                errors.add(new ApiException.FieldError(field, "required", field + " is required"));
            return null;
        }
        if (!element.isJsonObject())
        {
            errors.add(new ApiException.FieldError(field, "invalid-type",
                            field + " must be an object with value and currency"));
            return null;
        }

        var money = element.getAsJsonObject();
        var currency = money.get("currency");
        if (currency == null || !isString(currency))
            errors.add(new ApiException.FieldError(field + ".currency", "required", "currency is required"));
        else if (accountCurrency != null && !accountCurrency.equals(currency.getAsString()))
            errors.add(new ApiException.FieldError(field + ".currency", "currency-mismatch",
                            "must be the cash account's currency " + accountCurrency));

        var value = money.get("value");
        if (value == null || !isNumber(value))
        {
            errors.add(new ApiException.FieldError(field + ".value", "required", "value must be a number"));
            return null;
        }

        return scaled(field + ".value", value.getAsBigDecimal(), Values.Money.precision(), errors);
    }

    private static Long readShares(JsonObject body, List<ApiException.FieldError> errors)
    {
        var element = body.get("shares");
        if (element == null || element.isJsonNull())
            return null;
        if (!isNumber(element))
        {
            errors.add(new ApiException.FieldError("shares", "invalid-type", "shares must be a number"));
            return null;
        }

        var shares = scaled("shares", element.getAsBigDecimal(), Values.Share.precision(), errors);
        if (shares != null && shares < 0)
        {
            errors.add(new ApiException.FieldError("shares", "invalid-range", "shares must not be negative"));
            return null;
        }
        return shares;
    }

    /**
     * Converts a decimal to the model's scaled long - 42.50 EUR is stored as
     * 4250. {@code setScale} without a rounding mode is what rejects a value
     * with more decimal places than the model keeps: rounding it would book an
     * amount the caller never sent, and do it silently.
     */
    private static Long scaled(String field, BigDecimal value, int precision, List<ApiException.FieldError> errors)
    {
        try
        {
            return value.setScale(precision).unscaledValue().longValueExact();
        }
        catch (ArithmeticException e)
        {
            errors.add(new ApiException.FieldError(field, "invalid-value",
                            "value has more than " + precision + " decimal places, or is out of range"));
            return null;
        }
    }

    private static String readNote(JsonObject body, List<ApiException.FieldError> errors)
    {
        var element = body.get("note");
        if (element == null || element.isJsonNull())
            return null;
        if (!isString(element))
        {
            errors.add(new ApiException.FieldError("note", "invalid-type", "note must be a string"));
            return null;
        }
        return element.getAsString();
    }

    /** Which optional fields the type accepts at all, per the dialogs' rules. */
    private static void checkApplicability(AccountTransaction.Type type, JsonObject body, Long shares, Long fees,
                    Long taxes, List<ApiException.FieldError> errors)
    {
        var hasSecurity = body.has("security") && !body.get("security").isJsonNull();

        if (type == AccountTransaction.Type.DIVIDENDS && !hasSecurity)
            errors.add(new ApiException.FieldError("security", "required",
                            "a dividends transaction needs an instrument"));
        if (hasSecurity && !allowsSecurity(type))
            errors.add(new ApiException.FieldError("security", "not-applicable",
                            "a " + wireName(type) + " transaction has no instrument"));
        if (shares != null && type != AccountTransaction.Type.DIVIDENDS)
            errors.add(new ApiException.FieldError("shares", "not-applicable",
                            "a " + wireName(type) + " transaction has no shares"));
        if (fees != null && type != AccountTransaction.Type.DIVIDENDS)
            errors.add(new ApiException.FieldError("fees", "not-applicable",
                            "a " + wireName(type) + " transaction cannot carry fees"));
        if (taxes != null && type != AccountTransaction.Type.DIVIDENDS
                        && type != AccountTransaction.Type.INTEREST)
            errors.add(new ApiException.FieldError("taxes", "not-applicable",
                            "a " + wireName(type) + " transaction cannot carry taxes"));
    }

    private static boolean allowsSecurity(AccountTransaction.Type type)
    {
        return type == AccountTransaction.Type.DIVIDENDS //
                        || type == AccountTransaction.Type.TAXES //
                        || type == AccountTransaction.Type.TAX_REFUND //
                        || type == AccountTransaction.Type.FEES //
                        || type == AccountTransaction.Type.FEES_REFUND;
    }

    /**
     * The reconciliation the dialogs run while the user types: gross is the net
     * plus what a credit had withheld from it, or minus what a debit had added
     * to it. That is {@link AccountTransaction#getGrossValueAmount}, checked
     * before the transaction exists rather than after.
     */
    private static void checkAmounts(AccountTransaction.Type type, Long amount, Long fees, Long taxes,
                    Long grossAmount, List<ApiException.FieldError> errors)
    {
        if (amount != null && amount < 0)
            errors.add(new ApiException.FieldError("amount.value", "invalid-range",
                            "amount must not be negative; the transaction type carries the direction"));
        if (fees != null && fees < 0)
            errors.add(new ApiException.FieldError("fees.value", "invalid-range", "fees must not be negative"));
        if (taxes != null && taxes < 0)
            errors.add(new ApiException.FieldError("taxes.value", "invalid-range", "taxes must not be negative"));

        if (type == null || amount == null || amount < 0)
            return;

        var units = (fees == null ? 0 : fees) + (taxes == null ? 0 : taxes);
        var gross = amount + (type.isCredit() ? units : -units);

        if (gross <= 0)
        {
            errors.add(new ApiException.FieldError("amount.value", "invalid-range",
                            "the gross value must be greater than zero"));
            return;
        }

        if (grossAmount != null && grossAmount.longValue() != gross)
            errors.add(new ApiException.FieldError("grossAmount.value", "inconsistent-total",
                            "gross value does not reconcile with the amount, fees and taxes"));
    }

    private static void checkExDate(JsonObject body, LocalDateTime date, LocalDateTime exDate,
                    List<ApiException.FieldError> errors)
    {
        if (exDate == null)
            return;

        if (date != null && exDate.toLocalDate().isAfter(date.toLocalDate()))
            errors.add(new ApiException.FieldError("exDate", "invalid-range", "ex-date must not be after the date"));
        if (!body.has("security") || body.get("security").isJsonNull())
            errors.add(new ApiException.FieldError("exDate", "not-applicable", "an ex-date requires an instrument"));
    }

    /* package */ static String wireName(AccountTransaction.Type type)
    {
        return CREATABLE_TYPES.entrySet().stream().filter(entry -> entry.getValue() == type).map(Map.Entry::getKey)
                        .findAny().orElseThrow();
    }

    private static boolean isString(JsonElement value)
    {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement value)
    {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }
}
