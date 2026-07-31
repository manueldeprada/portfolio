package name.abuchen.portfolio.rest.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;

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
