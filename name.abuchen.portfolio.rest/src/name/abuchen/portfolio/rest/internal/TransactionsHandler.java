package name.abuchen.portfolio.rest.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

public final class TransactionsHandler
{
    /**
     * Which shape of request a type takes, and therefore which fields apply and
     * what gets built. A cash transaction lives in one account; everything else
     * is a {@code CrossEntry} spanning two owners, recorded on both sides and
     * reported once.
     */
    private enum Family
    {
        CASH, BUY_SELL, DELIVERY, CASH_TRANSFER, SECURITY_TRANSFER
    }

    private record TypeSpec(Family family, AccountTransaction.Type accountType, PortfolioTransaction.Type portfolioType)
    {
        static TypeSpec cash(AccountTransaction.Type type)
        {
            return new TypeSpec(Family.CASH, type, null);
        }
    }

    private static final Map<String, TypeSpec> CREATABLE_TYPES = Map.ofEntries( //
                    Map.entry("deposit", TypeSpec.cash(AccountTransaction.Type.DEPOSIT)),
                    Map.entry("removal", TypeSpec.cash(AccountTransaction.Type.REMOVAL)),
                    Map.entry("interest", TypeSpec.cash(AccountTransaction.Type.INTEREST)),
                    Map.entry("interest-charge", TypeSpec.cash(AccountTransaction.Type.INTEREST_CHARGE)),
                    Map.entry("dividends", TypeSpec.cash(AccountTransaction.Type.DIVIDENDS)),
                    Map.entry("fees", TypeSpec.cash(AccountTransaction.Type.FEES)),
                    Map.entry("fees-refund", TypeSpec.cash(AccountTransaction.Type.FEES_REFUND)),
                    Map.entry("taxes", TypeSpec.cash(AccountTransaction.Type.TAXES)),
                    Map.entry("tax-refund", TypeSpec.cash(AccountTransaction.Type.TAX_REFUND)),
                    Map.entry("buy", new TypeSpec(Family.BUY_SELL, null, PortfolioTransaction.Type.BUY)),
                    Map.entry("sell", new TypeSpec(Family.BUY_SELL, null, PortfolioTransaction.Type.SELL)),
                    Map.entry("delivery-inbound",
                                    new TypeSpec(Family.DELIVERY, null, PortfolioTransaction.Type.DELIVERY_INBOUND)),
                    Map.entry("delivery-outbound",
                                    new TypeSpec(Family.DELIVERY, null, PortfolioTransaction.Type.DELIVERY_OUTBOUND)),
                    Map.entry("transfer", new TypeSpec(Family.CASH_TRANSFER, null, null)),
                    Map.entry("security-transfer", new TypeSpec(Family.SECURITY_TRANSFER, null, null)));

    private static final Set<String> COMMON_FIELDS = Set.of("type", "date", "amount", "note", "uuid");

    /**
     * The fields each family accepts, on top of the common ones. A field that
     * exists but belongs to another family is rejected {@code not-applicable}
     * rather than {@code unknown-field}: sending {@code portfolio} with a
     * deposit is a different mistake from a typo, and worth saying so.
     */
    private static final Map<Family, Set<String>> FAMILY_FIELDS = Map.of( //
                    Family.CASH, Set.of("account", "security", "shares", "fees", "taxes", "exDate", "grossAmount"),
                    Family.BUY_SELL, Set.of("portfolio", "account", "security", "shares", "fees", "taxes",
                                    "grossAmount", "exchangeRate", "forexGrossAmount"),
                    Family.DELIVERY, Set.of("portfolio", "security", "shares", "fees", "taxes", "grossAmount"),
                    Family.CASH_TRANSFER, Set.of("fromAccount", "toAccount", "toAmount"),
                    Family.SECURITY_TRANSFER, Set.of("fromPortfolio", "toPortfolio", "security", "shares"));

    private static final Set<String> ALL_FIELDS = allFields();

    private static Set<String> allFields()
    {
        var fields = new java.util.HashSet<>(COMMON_FIELDS);
        FAMILY_FIELDS.values().forEach(fields::addAll);
        return Set.copyOf(fields);
    }

    public record CreateResult(JsonElement entity, String uuid, String typeName, String ownerName)
    {
    }

    public record DeleteResult(String uuid, String typeName, String ownerName)
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
        return list(client, null);
    }

    /**
     * As {@link #list(Client)}, optionally narrowed to one account. {@code
     * account} is the uuid of a cash account or an investment account and
     * selects every transaction that touches it: the cash legs of buys and
     * sells and both legs of a transfer, not only the legs the account owns in
     * the de-duplicated list. Each is reported from that account's side (its
     * {@code owner.uuid} is the requested account, the other side is the {@code
     * counterOwner}), once, so the signed {@code value}s of a cash account's
     * entries add up to its balance.
     */
    public static JsonElement list(Client client, String accountParam)
    {
        var transactions = new ArrayList<TransactionPair<?>>();

        if (accountParam == null)
        {
            transactions.addAll(client.getAllTransactions());
        }
        else
        {
            var cash = client.getAccounts().stream().filter(a -> accountParam.equals(a.getUUID())).findFirst();
            var investment = client.getPortfolios().stream().filter(p -> accountParam.equals(p.getUUID()))
                            .findFirst();

            if (cash.isPresent())
                cash.get().getTransactions().forEach(t -> transactions.add(new TransactionPair<>(cash.get(), t)));
            else if (investment.isPresent())
                investment.get().getTransactions()
                                .forEach(t -> transactions.add(new TransactionPair<>(investment.get(), t)));
            else
                throw ApiException.badRequest(List.of(new ApiException.FieldError("account", "invalid-value",
                                accountParam + " is not a known cash account or investment account")));
        }

        transactions.sort(TransactionPair.BY_DATE.reversed());
        return EntityJson.envelope(transactions, EntityJson::toJson);
    }

    /**
     * Books a transaction. Every violation is collected and reported at once,
     * and nothing is applied unless the whole request validates - a half-booked
     * cross entry would be worse than none.
     * <p/>
     * The rules enforced here are the transaction dialogs', restated: nothing in
     * the model enforces them - {@link Account#addTransaction} validates nothing
     * at all - so an API that skipped them would accept transactions the desktop
     * application refuses to create.
     * <p/>
     * {@code amount} is the net cash flow and is unsigned: the direction belongs
     * to the type, so a removal of -50 is a validation error rather than a
     * deposit. The gross value is derived from it and the fee and tax units; a
     * {@code grossAmount} sent along is checked against that derivation rather
     * than believed.
     * <p/>
     * An optional client-supplied {@code uuid} makes the call idempotent: a
     * retry after a 423, a timeout or a restart is answered {@code 409
     * duplicate-uuid} instead of booking the trade twice.
     */
    public static CreateResult create(Client client, JsonObject body)
    {
        var errors = new ArrayList<ApiException.FieldError>();

        var spec = readType(body, errors);
        checkFieldNames(body, spec, errors);

        var date = readDateTime(body, "date", errors, true);
        var note = readNote(body, errors);
        var uuid = readSuppliedUuid(client, body, errors);

        // The type decides everything else, so there is nothing further to say
        // until it is known - and saying it against a guessed shape would be noise.
        if (spec == null)
            throw ApiException.validation(errors);

        return switch (spec.family())
        {
            case CASH -> createCash(client, body, spec, date, note, uuid, errors);
            case BUY_SELL -> createBuySell(client, body, spec, date, note, uuid, errors);
            case DELIVERY -> createDelivery(client, body, spec, date, note, uuid, errors);
            case CASH_TRANSFER -> createCashTransfer(client, body, date, note, uuid, errors);
            case SECURITY_TRANSFER -> createSecurityTransfer(client, body, date, note, uuid, errors);
        };
    }

    /**
     * Removes a transaction, and with it the other leg of a cross entry - a buy
     * without its cash movement, or a transfer with one side left behind, is not
     * a state the model has a name for. {@code TransactionOwner#deleteTransaction}
     * is what does that; this only has to find the owner.
     */
    public static DeleteResult delete(Client client, String uuid)
    {
        var pair = findAnyLeg(client, uuid);
        if (pair == null)
            throw ApiException.notFound();

        var transaction = pair.getTransaction();
        var typeName = EntityJson.wireType(transaction);
        var ownerName = ownerName(pair.getOwner());

        deleteFrom(pair, client);
        client.markDirty();

        return new DeleteResult(uuid, typeName, ownerName);
    }

    @SuppressWarnings("unchecked")
    private static void deleteFrom(TransactionPair<?> pair, Client client)
    {
        ((name.abuchen.portfolio.model.TransactionOwner<Transaction>) pair.getOwner())
                        .deleteTransaction(pair.getTransaction(), client);
    }

    // ---------------------------------------------------------------- families

    private static CreateResult createCash(Client client, JsonObject body, TypeSpec spec, LocalDateTime date,
                    String note, String uuid, List<ApiException.FieldError> errors)
    {
        var type = spec.accountType();
        var account = readOwner(client.getAccounts(), Account::getUUID, body, "account", "no such cash account",
                        errors, true);
        var currency = account == null ? null : account.getCurrencyCode();

        var amount = readMoney(body, "amount", currency, errors, true);
        var fees = readMoney(body, "fees", currency, errors, false);
        var taxes = readMoney(body, "taxes", currency, errors, false);
        var grossAmount = readMoney(body, "grossAmount", currency, errors, false);
        var security = readSecurity(client, body, "security", errors);
        var shares = readShares(body, errors);
        var exDate = readDateTime(body, "exDate", errors, false);

        checkCashApplicability(type, body, shares, fees, taxes, errors);
        checkCashAmounts(type, amount, fees, taxes, grossAmount, errors);
        checkExDate(body, date, exDate, errors);
        require(!errors.isEmpty(), errors);

        var transaction = new AccountTransaction();
        applyUuid(transaction, uuid);
        transaction.setType(type);
        transaction.setCurrencyCode(currency);
        transaction.setDateTime(date);
        transaction.setAmount(amount);
        transaction.setSecurity(security);
        transaction.setShares(shares == null ? 0 : shares);
        transaction.setExDate(exDate);
        transaction.setNote(note);
        addFeeAndTax(transaction, currency, fees, taxes);

        account.addTransaction(transaction);
        client.markDirty();

        return result(new TransactionPair<>(account, transaction), account.getName());
    }

    private static CreateResult createBuySell(Client client, JsonObject body, TypeSpec spec, LocalDateTime date,
                    String note, String uuid, List<ApiException.FieldError> errors)
    {
        var type = spec.portfolioType();
        var portfolio = readOwner(client.getPortfolios(), Portfolio::getUUID, body, "portfolio",
                        "no such investment account", errors, true);
        var account = readOwner(client.getAccounts(), Account::getUUID, body, "account", "no such cash account",
                        errors, true);
        var security = readSecurity(client, body, "security", errors);
        if (security == null && !body.has("security"))
            errors.add(new ApiException.FieldError("security", "required", "an instrument is required"));

        var currency = account == null ? null : account.getCurrencyCode();
        var amount = readMoney(body, "amount", currency, errors, true);
        var fees = readMoney(body, "fees", currency, errors, false);
        var taxes = readMoney(body, "taxes", currency, errors, false);
        var grossAmount = readMoney(body, "grossAmount", currency, errors, false);
        var shares = requireShares(body, errors);

        var gross = checkTradeAmounts(type, amount, fees, taxes, grossAmount, errors);
        var forex = readForex(body, security, currency, gross, errors);
        require(!errors.isEmpty(), errors);

        var entry = new BuySellEntry(portfolio, account);
        applyUuid(entry.getPortfolioTransaction(), uuid);
        entry.setType(type);
        entry.setCurrencyCode(currency);
        entry.setDate(date);
        entry.setSecurity(security);
        entry.setShares(shares);
        entry.setAmount(amount);
        entry.setNote(note);
        addFeeAndTax(entry.getPortfolioTransaction(), currency, fees, taxes);
        if (forex != null)
            entry.getPortfolioTransaction().addUnit(forex);

        entry.insert();
        client.markDirty();

        return result(new TransactionPair<>(portfolio, entry.getPortfolioTransaction()), portfolio.getName());
    }

    private static CreateResult createDelivery(Client client, JsonObject body, TypeSpec spec, LocalDateTime date,
                    String note, String uuid, List<ApiException.FieldError> errors)
    {
        var type = spec.portfolioType();
        var portfolio = readOwner(client.getPortfolios(), Portfolio::getUUID, body, "portfolio",
                        "no such investment account", errors, true);
        var security = readSecurity(client, body, "security", errors);
        if (security == null && !body.has("security"))
            errors.add(new ApiException.FieldError("security", "required", "an instrument is required"));

        // A delivery has no cash leg, so no account fixes the currency: the caller
        // states it, and it only has to be a currency the application knows.
        var currency = readCurrencyOf(body, "amount", errors);
        var amount = readMoney(body, "amount", currency, errors, true);
        var fees = readMoney(body, "fees", currency, errors, false);
        var taxes = readMoney(body, "taxes", currency, errors, false);
        var grossAmount = readMoney(body, "grossAmount", currency, errors, false);
        var shares = requireShares(body, errors);

        checkTradeAmounts(type, amount, fees, taxes, grossAmount, errors);
        require(!errors.isEmpty(), errors);

        var transaction = new PortfolioTransaction();
        applyUuid(transaction, uuid);
        transaction.setType(type);
        transaction.setCurrencyCode(currency);
        transaction.setDateTime(date);
        transaction.setSecurity(security);
        transaction.setShares(shares);
        transaction.setAmount(amount);
        transaction.setNote(note);
        addFeeAndTax(transaction, currency, fees, taxes);

        portfolio.addTransaction(transaction);
        client.markDirty();

        return result(new TransactionPair<>(portfolio, transaction), portfolio.getName());
    }

    private static CreateResult createCashTransfer(Client client, JsonObject body, LocalDateTime date, String note,
                    String uuid, List<ApiException.FieldError> errors)
    {
        var from = readOwner(client.getAccounts(), Account::getUUID, body, "fromAccount", "no such cash account",
                        errors, true);
        var to = readOwner(client.getAccounts(), Account::getUUID, body, "toAccount", "no such cash account", errors,
                        true);
        if (from != null && from == to)
            errors.add(new ApiException.FieldError("toAccount", "invalid-value",
                            "a transfer needs two different accounts"));

        var amount = readMoney(body, "amount", from == null ? null : from.getCurrencyCode(), errors, true);
        var crossCurrency = from != null && to != null && !from.getCurrencyCode().equals(to.getCurrencyCode());
        var toAmount = readMoney(body, "toAmount", to == null ? null : to.getCurrencyCode(), errors, crossCurrency);

        if (!crossCurrency && toAmount != null)
            errors.add(new ApiException.FieldError("toAmount", "not-applicable",
                            "both accounts hold the same currency, so the amount received is the amount sent"));
        if (amount != null && amount <= 0)
            errors.add(new ApiException.FieldError("amount.value", "invalid-range",
                            "amount must be greater than zero"));
        if (toAmount != null && toAmount <= 0)
            errors.add(new ApiException.FieldError("toAmount.value", "invalid-range",
                            "toAmount must be greater than zero"));
        require(!errors.isEmpty(), errors);

        var entry = new AccountTransferEntry(from, to);
        applyUuid(entry.getSourceTransaction(), uuid);
        entry.getSourceTransaction().setCurrencyCode(from.getCurrencyCode());
        entry.getTargetTransaction().setCurrencyCode(to.getCurrencyCode());
        entry.setDate(date);
        entry.setNote(note);

        entry.getSourceTransaction().setAmount(amount);
        entry.getTargetTransaction().setAmount(crossCurrency ? toAmount : amount);

        if (crossCurrency)
        {
            // The rate is implied by the two amounts, so it is derived rather than
            // asked for: a rate sent alongside them could only agree or contradict,
            // and the dialog derives one from the other for the same reason.
            var rate = BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(toAmount), 10, RoundingMode.HALF_UP);
            entry.getSourceTransaction().addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE,
                            Money.of(from.getCurrencyCode(), amount), Money.of(to.getCurrencyCode(), toAmount), rate));
        }

        entry.insert();
        client.markDirty();

        return result(new TransactionPair<>(from, entry.getSourceTransaction()), from.getName());
    }

    private static CreateResult createSecurityTransfer(Client client, JsonObject body, LocalDateTime date, String note,
                    String uuid, List<ApiException.FieldError> errors)
    {
        var from = readOwner(client.getPortfolios(), Portfolio::getUUID, body, "fromPortfolio",
                        "no such investment account", errors, true);
        var to = readOwner(client.getPortfolios(), Portfolio::getUUID, body, "toPortfolio",
                        "no such investment account", errors, true);
        if (from != null && from == to)
            errors.add(new ApiException.FieldError("toPortfolio", "invalid-value",
                            "a transfer needs two different investment accounts"));

        var security = readSecurity(client, body, "security", errors);
        if (security == null && !body.has("security"))
            errors.add(new ApiException.FieldError("security", "required", "an instrument is required"));

        // The transferred position is valued in the instrument's own currency, as
        // the transfer dialog does: no cash changes hands, so nothing converts it.
        var currency = security == null ? null : security.getCurrencyCode();
        var amount = readMoney(body, "amount", currency, errors, true);
        var shares = requireShares(body, errors);

        if (amount != null && amount <= 0)
            errors.add(new ApiException.FieldError("amount.value", "invalid-range",
                            "amount must be greater than zero"));
        require(!errors.isEmpty(), errors);

        var entry = new PortfolioTransferEntry(from, to);
        applyUuid(entry.getSourceTransaction(), uuid);
        entry.setSecurity(security);
        entry.setDate(date);
        entry.setShares(shares);
        entry.setAmount(amount);
        entry.setCurrencyCode(currency);
        entry.setNote(note);

        entry.insert();
        client.markDirty();

        return result(new TransactionPair<>(from, entry.getSourceTransaction()), from.getName());
    }

    // ------------------------------------------------------------- validation

    private static void require(boolean hasErrors, List<ApiException.FieldError> errors)
    {
        if (hasErrors)
            throw ApiException.validation(errors);
    }

    private static CreateResult result(TransactionPair<?> pair, String ownerName)
    {
        return new CreateResult(EntityJson.toJson(pair), pair.getTransaction().getUUID(),
                        EntityJson.wireType(pair.getTransaction()), ownerName);
    }

    private static void checkFieldNames(JsonObject body, TypeSpec spec, List<ApiException.FieldError> errors)
    {
        for (var field : body.keySet())
        {
            if (!ALL_FIELDS.contains(field))
                errors.add(new ApiException.FieldError(field, "unknown-field", "field is not writable"));
            else if (spec != null && !COMMON_FIELDS.contains(field)
                            && !FAMILY_FIELDS.get(spec.family()).contains(field))
                errors.add(new ApiException.FieldError(field, "not-applicable",
                            "field does not apply to this transaction type"));
        }
    }

    /** Which optional fields a cash type accepts at all, per the dialogs' rules. */
    private static void checkCashApplicability(AccountTransaction.Type type, JsonObject body, Long shares, Long fees,
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
     * plus what a credit had withheld from it, or minus what a debit had added to
     * it. That is {@link AccountTransaction#getGrossValueAmount}, checked before
     * the transaction exists rather than after.
     */
    private static long checkCashAmounts(AccountTransaction.Type type, Long amount, Long fees, Long taxes,
                    Long grossAmount, List<ApiException.FieldError> errors)
    {
        checkNonNegative(amount, fees, taxes, errors);
        if (type == null || amount == null || amount < 0)
            return 0;

        var units = orZero(fees) + orZero(taxes);
        var gross = amount + (type.isCredit() ? units : -units);

        if (gross <= 0)
        {
            errors.add(new ApiException.FieldError("amount.value", "invalid-range",
                            "the gross value must be greater than zero"));
            return 0;
        }

        checkGrossEcho(grossAmount, gross, errors);
        return gross;
    }

    /**
     * The same reconciliation for a trade, where the model's rule is the mirror
     * image: a purchase pays its fees and taxes on top of the gross, a sale has
     * them taken out of it (see {@link PortfolioTransaction#getGrossValueAmount}).
     * A zero total is allowed only for an outbound delivery, which is what the
     * dialog says to use for a worthless position.
     */
    private static long checkTradeAmounts(PortfolioTransaction.Type type, Long amount, Long fees, Long taxes,
                    Long grossAmount, List<ApiException.FieldError> errors)
    {
        checkNonNegative(amount, fees, taxes, errors);
        if (type == null || amount == null || amount < 0)
            return 0;

        var units = orZero(fees) + orZero(taxes);
        var gross = type.isPurchase() ? amount - units : amount + units;

        if (gross <= 0 && type != PortfolioTransaction.Type.DELIVERY_OUTBOUND)
        {
            errors.add(new ApiException.FieldError("amount.value", "invalid-range",
                            type == PortfolioTransaction.Type.SELL
                                            ? "a sale with nothing left after fees and taxes is an outbound delivery"
                                            : "the gross value must be greater than zero"));
            return 0;
        }

        checkGrossEcho(grossAmount, gross, errors);
        return gross;
    }

    private static void checkNonNegative(Long amount, Long fees, Long taxes, List<ApiException.FieldError> errors)
    {
        if (amount != null && amount < 0)
            errors.add(new ApiException.FieldError("amount.value", "invalid-range",
                            "amount must not be negative; the transaction type carries the direction"));
        if (fees != null && fees < 0)
            errors.add(new ApiException.FieldError("fees.value", "invalid-range", "fees must not be negative"));
        if (taxes != null && taxes < 0)
            errors.add(new ApiException.FieldError("taxes.value", "invalid-range", "taxes must not be negative"));
    }

    private static void checkGrossEcho(Long grossAmount, long gross, List<ApiException.FieldError> errors)
    {
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

    /**
     * The rate a broker actually used, recorded as a {@code GROSS_VALUE} unit so
     * the trade reports in the instrument's currency exactly as it was executed
     * rather than at whatever rate the application would look up. Optional, and
     * meaningless when the instrument is already in the account's currency.
     */
    private static Transaction.Unit readForex(JsonObject body, Security security, String currency, long gross,
                    List<ApiException.FieldError> errors)
    {
        var hasRate = body.has("exchangeRate") && !body.get("exchangeRate").isJsonNull();
        var hasForex = body.has("forexGrossAmount") && !body.get("forexGrossAmount").isJsonNull();

        if (!hasRate && !hasForex)
            return null;
        if (hasRate != hasForex)
        {
            errors.add(new ApiException.FieldError(hasRate ? "forexGrossAmount" : "exchangeRate", "required",
                            "exchangeRate and forexGrossAmount are recorded together or not at all"));
            return null;
        }

        if (security == null || currency == null)
            return null;
        if (currency.equals(security.getCurrencyCode()))
        {
            errors.add(new ApiException.FieldError("exchangeRate", "not-applicable",
                            "the instrument is already denominated in the account's currency"));
            return null;
        }

        var forexGross = readMoney(body, "forexGrossAmount", security.getCurrencyCode(), errors, true);
        var rate = readDecimal(body, "exchangeRate", errors);
        if (forexGross == null || rate == null || gross <= 0)
            return null;

        if (rate.signum() <= 0)
        {
            errors.add(new ApiException.FieldError("exchangeRate", "invalid-range",
                            "exchange rate must be greater than zero"));
            return null;
        }

        try
        {
            // The model checks that gross, forex and rate agree within a rounding
            // tolerance; a mismatch is the caller's arithmetic, not an internal error.
            return new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE, Money.of(currency, gross),
                            Money.of(security.getCurrencyCode(), forexGross), rate);
        }
        catch (IllegalArgumentException e)
        {
            errors.add(new ApiException.FieldError("exchangeRate", "inconsistent-total",
                            "the gross value, the forex gross value and the exchange rate do not agree"));
            return null;
        }
    }

    // ---------------------------------------------------------------- readers

    private static TypeSpec readType(JsonObject body, List<ApiException.FieldError> errors)
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

        var spec = CREATABLE_TYPES.get(element.getAsString());
        if (spec == null)
            errors.add(new ApiException.FieldError("type", "invalid-value",
                            element.getAsString() + " is not a transaction type this API can create"));
        return spec;
    }

    /**
     * The optional client-supplied identity. Checked against both legs of every
     * cross entry, not only the list the read endpoint reports: a uuid that
     * collides with a hidden leg is just as much a collision.
     */
    private static String readSuppliedUuid(Client client, JsonObject body, List<ApiException.FieldError> errors)
    {
        var uuid = readUuid(body, "uuid", errors, false);
        if (uuid == null)
            return null;

        try
        {
            UUID.fromString(uuid);
        }
        catch (IllegalArgumentException e)
        {
            errors.add(new ApiException.FieldError("uuid", "invalid-value", "uuid must be a UUID"));
            return null;
        }

        if (findAnyLeg(client, uuid) != null)
        {
            throw ApiException.conflict("duplicate-uuid", "A transaction with this uuid already exists", null,
                            List.of(new ApiException.FieldError("uuid", "duplicate",
                                            "a transaction with this uuid already exists")));
        }

        return uuid;
    }

    private static void applyUuid(Transaction transaction, String uuid)
    {
        if (uuid != null)
            transaction.adoptUUID(uuid);
    }

    /* package */ static TransactionPair<?> findAnyLeg(Client client, String uuid)
    {
        for (var account : client.getAccounts())
            for (var transaction : account.getTransactions())
                if (uuid.equals(transaction.getUUID()))
                    return new TransactionPair<>(account, transaction);

        for (var portfolio : client.getPortfolios())
            for (var transaction : portfolio.getTransactions())
                if (uuid.equals(transaction.getUUID()))
                    return new TransactionPair<>(portfolio, transaction);

        return null;
    }

    private static <T> T readOwner(List<T> candidates, java.util.function.Function<T, String> uuidOf, JsonObject body,
                    String field, String message, List<ApiException.FieldError> errors, boolean required)
    {
        var uuid = readUuid(body, field, errors, required);
        if (uuid == null)
            return null;

        var owner = candidates.stream().filter(candidate -> uuid.equals(uuidOf.apply(candidate))).findAny()
                        .orElse(null);
        if (owner == null)
            errors.add(new ApiException.FieldError(field, "unknown-reference", message));
        return owner;
    }

    private static Security readSecurity(Client client, JsonObject body, String field,
                    List<ApiException.FieldError> errors)
    {
        return readOwner(client.getSecurities(), Security::getUUID, body, field, "no such instrument", errors, false);
    }

    private static String ownerName(Object owner)
    {
        if (owner instanceof Account account)
            return account.getName();
        if (owner instanceof Portfolio portfolio)
            return portfolio.getName();
        return String.valueOf(owner);
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

    /** The currency a money field names, for the cases where nothing else fixes it. */
    private static String readCurrencyOf(JsonObject body, String field, List<ApiException.FieldError> errors)
    {
        var element = body.get(field);
        if (element == null || !element.isJsonObject())
            return null;

        var currency = element.getAsJsonObject().get("currency");
        if (currency == null || !isString(currency))
            return null;

        var code = currency.getAsString();
        if (CurrencyUnit.getInstance(code) == null)
        {
            errors.add(new ApiException.FieldError(field + ".currency", "unknown-currency",
                            code + " is not a known currency"));
            return null;
        }
        return code;
    }

    /**
     * A monetary amount in the wire shape the reads use, {@code {value,
     * currency}}. The currency is required and checked against the one the
     * transaction is denominated in: converting - or ignoring - a mismatch
     * silently would book a figure the caller never sent.
     */
    private static Long readMoney(JsonObject body, String field, String expectedCurrency,
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
        else if (expectedCurrency != null && !expectedCurrency.equals(currency.getAsString()))
            errors.add(new ApiException.FieldError(field + ".currency", "currency-mismatch",
                            "must be " + expectedCurrency));

        var value = money.get("value");
        if (value == null || !isNumber(value))
        {
            errors.add(new ApiException.FieldError(field + ".value", "required", "value must be a number"));
            return null;
        }

        return scaled(field + ".value", value.getAsBigDecimal(), Values.Money.precision(), errors);
    }

    private static BigDecimal readDecimal(JsonObject body, String field, List<ApiException.FieldError> errors)
    {
        var element = body.get(field);
        if (element == null || element.isJsonNull())
            return null;
        if (!isNumber(element))
        {
            errors.add(new ApiException.FieldError(field, "invalid-type", field + " must be a number"));
            return null;
        }
        return element.getAsBigDecimal();
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

    /** Shares for the types that trade them: absent or zero is the dialogs' first error. */
    private static long requireShares(JsonObject body, List<ApiException.FieldError> errors)
    {
        var shares = readShares(body, errors);
        if (shares == null && !body.has("shares"))
            errors.add(new ApiException.FieldError("shares", "required", "shares are required"));
        else if (shares != null && shares == 0)
            errors.add(new ApiException.FieldError("shares", "invalid-range", "shares must be greater than zero"));
        return shares == null ? 0 : shares;
    }

    /**
     * Converts a decimal to the model's scaled long - 42.50 EUR is stored as
     * 4250. {@code setScale} without a rounding mode is what rejects a value with
     * more decimal places than the model keeps: rounding it would book an amount
     * the caller never sent, and do it silently.
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

    private static void addFeeAndTax(Transaction transaction, String currency, Long fees, Long taxes)
    {
        if (fees != null && fees != 0)
            transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of(currency, fees)));
        if (taxes != null && taxes != 0)
            transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of(currency, taxes)));
    }

    private static long orZero(Long value)
    {
        return value == null ? 0 : value;
    }

    /* package */ static String wireName(AccountTransaction.Type type)
    {
        return CREATABLE_TYPES.entrySet().stream()
                        .filter(entry -> entry.getValue().accountType() == type)
                        .map(Map.Entry::getKey).findAny().orElseThrow();
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
