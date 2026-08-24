package name.abuchen.portfolio.rest.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.PortfolioSnapshot;

public final class PortfoliosHandler
{
    private PortfoliosHandler()
    {
    }

    /**
     * Lists every investment account with its total value at the given date
     * (default: today) in the given currency (default: the file's base
     * currency) - the sum of every security position it holds, valued at the
     * date's quotes and converted like the holdings endpoint. The factory is
     * the host's per-file instance - it registers a listener on the client,
     * so this handler must not construct its own.
     */
    /**
     * Creates a new, empty investment account, exactly as the application's own
     * "new account" (investment side) does. {@code name} is required and
     * non-blank; {@code referenceAccount}, if given, must be the uuid of an
     * existing cash account and becomes the portfolio's settlement account -
     * the account a buy/sell/delivery in it books its cash leg against.
     */
    public static JsonObject create(Client client, JsonObject body)
    {
        var errors = new ArrayList<ApiException.FieldError>();
        var name = TaxonomiesHandler.requiredText(body, "name", errors); //$NON-NLS-1$

        Account referenceAccount = null;
        if (body.has("referenceAccount") && !body.get("referenceAccount").isJsonNull()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            var uuid = body.get("referenceAccount").getAsString(); //$NON-NLS-1$
            referenceAccount = client.getAccounts().stream().filter(a -> a.getUUID().equals(uuid)).findAny()
                            .orElse(null);
            if (referenceAccount == null)
                errors.add(new ApiException.FieldError("referenceAccount", "invalid-value", //$NON-NLS-1$ //$NON-NLS-2$
                                "referenceAccount must be the uuid of an existing cash account")); //$NON-NLS-1$
        }

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        var portfolio = new Portfolio(name);
        portfolio.setReferenceAccount(referenceAccount);

        client.addPortfolio(portfolio);
        client.markDirty();

        var currency = referenceAccount != null ? referenceAccount.getCurrencyCode() : client.getBaseCurrency();
        return EntityJson.toJson(portfolio, Money.of(currency, 0));
    }

    public static JsonElement list(Client client, ExchangeRateProviderFactory factory, String dateParam,
                    String currencyParam)
    {
        var errors = new ArrayList<ApiException.FieldError>();
        var date = parseDate(dateParam, errors);
        var currency = CalcParams.reportingCurrency(client, currencyParam, errors);

        if (!errors.isEmpty())
            throw ApiException.badRequest(errors);

        var converter = new CurrencyConverterImpl(factory, currency);
        return EntityJson.envelope(client.getPortfolios(), portfolio -> EntityJson.toJson(portfolio, valueOf(portfolio, converter, date)));
    }

    public static JsonElement get(Client client, ExchangeRateProviderFactory factory, String uuid, String dateParam,
                    String currencyParam)
    {
        var errors = new ArrayList<ApiException.FieldError>();
        var date = parseDate(dateParam, errors);
        var currency = CalcParams.reportingCurrency(client, currencyParam, errors);

        if (!errors.isEmpty())
            throw ApiException.badRequest(errors);

        var converter = new CurrencyConverterImpl(factory, currency);
        var portfolio = Entities.byUuid(client.getPortfolios(), Portfolio::getUUID, uuid);
        return EntityJson.toJson(portfolio, valueOf(portfolio, converter, date));
    }

    private static Money valueOf(Portfolio portfolio, CurrencyConverterImpl converter, LocalDate date)
    {
        return PortfolioSnapshot.create(portfolio, converter, date).getValue();
    }

    private static LocalDate parseDate(String dateParam, List<ApiException.FieldError> errors)
    {
        return dateParam == null ? LocalDate.now() : CalcParams.date("date", dateParam, errors); //$NON-NLS-1$
    }
}
