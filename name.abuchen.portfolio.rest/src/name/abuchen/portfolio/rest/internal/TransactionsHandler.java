package name.abuchen.portfolio.rest.internal;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.JsonElement;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.TransactionPair;

public final class TransactionsHandler
{
    /**
     * Newest first, with the transaction uuid as the tiebreak. Deliberately not
     * {@link TransactionPair#BY_DATE}, whose last tiebreak is the identity hash
     * code: that orders two otherwise equal transactions differently on every
     * run, and an order that is not reproducible cannot carry a page boundary.
     */
    private static final Comparator<TransactionPair<?>> NEWEST_FIRST = Comparator
                    .comparing((TransactionPair<?> pair) -> pair.getTransaction().getDateTime())
                    .thenComparing((TransactionPair<?> pair) -> pair.getTransaction().getUUID()).reversed();

    private TransactionsHandler()
    {
    }

    /**
     * Every transaction across every account and investment account, newest
     * first. {@link Client#getAllTransactions()} already de-duplicates a
     * buy/sell pair (only the investment-account side survives) and a transfer
     * (only the outbound leg survives).
     * <p>
     * All four filters are optional and combine with AND. {@code from} and
     * {@code to} bound the transaction date inclusively - this endpoint selects
     * records rather than valuing a portfolio at two dates, so unlike a
     * reporting period the range is closed at both ends. {@code account} is the
     * uuid of the transaction's owner, which is a cash account for an account
     * transaction and an investment account for a portfolio one; one parameter
     * matches both because a transaction has exactly one owner, and it is the
     * uuid the response itself reports as {@code owner.uuid}.
     */
    public static JsonElement list(Client client, String fromParam, String toParam, String accountParam,
                    String securityParam)
    {
        var errors = new ArrayList<ApiException.FieldError>();

        var from = parseDate("from", fromParam, errors);
        var to = parseDate("to", toParam, errors);
        var owner = parseOwner(client, accountParam, errors);
        var security = parseSecurity(client, securityParam, errors);

        if (!errors.isEmpty())
            throw ApiException.badRequest(errors);

        if (from != null && to != null && from.isAfter(to))
            throw ApiException.badRequest(List.of(new ApiException.FieldError("to", "invalid-range",
                            "to must not be before from")));

        var selected = new ArrayList<TransactionPair<?>>();
        for (TransactionPair<?> pair : client.getAllTransactions())
        {
            if (matches(pair, from, to, owner, security))
                selected.add(pair);
        }
        selected.sort(NEWEST_FIRST);

        return EntityJson.envelope(selected, EntityJson::toJson);
    }

    private static boolean matches(TransactionPair<?> pair, LocalDate from, LocalDate to, String owner, String security)
    {
        var transaction = pair.getTransaction();

        var date = transaction.getDateTime().toLocalDate();
        if (from != null && date.isBefore(from))
            return false;
        if (to != null && date.isAfter(to))
            return false;

        if (owner != null && !owner.equals(ownerUuid(pair)))
            return false;

        return security == null
                        || (transaction.getSecurity() != null && security.equals(transaction.getSecurity().getUUID()));
    }

    /** The uuid the response reports as {@code owner.uuid}. */
    private static String ownerUuid(TransactionPair<?> pair)
    {
        return pair.isAccountTransaction() ? ((Account) pair.getOwner()).getUUID()
                        : ((Portfolio) pair.getOwner()).getUUID();
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

    /**
     * An unknown owner is a bad parameter, not a 404: the addressed resource is
     * the transaction list of a file that does exist, and reporting it per
     * field is what lets a caller passing several filters see which one of them
     * the file does not know.
     */
    private static String parseOwner(Client client, String uuid, List<ApiException.FieldError> errors)
    {
        if (uuid == null)
            return null;

        var known = client.getAccounts().stream().map(Account::getUUID).anyMatch(uuid::equals)
                        || client.getPortfolios().stream().map(Portfolio::getUUID).anyMatch(uuid::equals);
        if (known)
            return uuid;

        errors.add(new ApiException.FieldError("account", "invalid-value",
                        uuid + " is not a known cash account or investment account"));
        return null;
    }

    private static String parseSecurity(Client client, String uuid, List<ApiException.FieldError> errors)
    {
        if (uuid == null)
            return null;

        if (client.getSecurities().stream().map(Security::getUUID).anyMatch(uuid::equals))
            return uuid;

        errors.add(new ApiException.FieldError("security", "invalid-value", uuid + " is not a known instrument"));
        return null;
    }
}
