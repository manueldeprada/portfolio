package name.abuchen.portfolio.rest.internal;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.util.Interval;

public final class PerformanceHandler
{
    private static final int MAX_SERIES_YEARS = 20;

    private PerformanceHandler()
    {
    }

    /**
     * Computes the portfolio's performance between the opening and closing
     * valuation dates in the given currency (default: the file's base
     * currency). Reports the time-weighted (TTWROR) and money-weighted (IRR)
     * return plus the value-change breakdown that reconciles the opening to the
     * closing value. The dates are valuation snapshots, not a record range: an
     * activity on the opening date is part of the opening balance, not the
     * period. The factory is the host's per-file instance - it registers a
     * listener on the client, so this handler must not construct its own.
     */
    public static JsonElement list(Client client, ExchangeRateProviderFactory factory, String openingDateParam,
                    String closingDateParam, String currencyParam, String costMethodParam)
    {
        return list(client, factory, openingDateParam, closingDateParam, currencyParam, costMethodParam, null);
    }

    /** As above, scoped to one investment account's instruments when {@code investmentAccountParam} is set. */
    public static JsonElement list(Client client, ExchangeRateProviderFactory factory, String openingDateParam,
                    String closingDateParam, String currencyParam, String costMethodParam,
                    String investmentAccountParam)
    {
        var errors = new ArrayList<ApiException.FieldError>();

        // null (not today) when unparseable: the range check must not judge a
        // value that never parsed
        var openingDate = CalcParams.openingDate(openingDateParam, errors);
        var closingDate = CalcParams.closingDate(closingDateParam, errors);
        var currency = CalcParams.reportingCurrency(client, currencyParam, errors);
        var costMethod = CalcParams.costMethod(costMethodParam, errors);
        var portfolio = CalcParams.investmentAccount(client, investmentAccountParam, errors);
        CalcParams.requireRange(openingDate, closingDate, errors);

        if (!errors.isEmpty())
            throw ApiException.badRequest(errors);

        var scoped = CalcParams.scope(client, portfolio);
        var converter = new CurrencyConverterImpl(factory, currency);
        var interval = Interval.of(openingDate, closingDate);

        var performance = new ClientPerformanceSnapshot(scoped, converter, interval, costMethod.useFifo());

        // TTWROR comes from a second engine; IRR is money-weighted, both are
        // independent of the cost method
        var ttwror = PerformanceIndex.forClient(scoped, converter, interval, new ArrayList<>())
                        .getFinalAccumulatedPercentage();
        var irr = performance.getPerformanceIRR();

        return EntityJson.performance(openingDate, closingDate, currency, costMethod, ttwror, irr, performance,
                        portfolio);
    }

    /**
     * The daily index behind the same period {@link #list} reports on: one point
     * per calendar day with the accumulated TTWROR, total assets, invested
     * capital, that day's external flow and the running drawdown, plus the risk
     * metrics derived from the series. The cost method is irrelevant here - none
     * of these figures depend on it.
     */
    public static JsonElement series(Client client, ExchangeRateProviderFactory factory, String openingDateParam,
                    String closingDateParam, String currencyParam)
    {
        return series(client, factory, openingDateParam, closingDateParam, currencyParam, null);
    }

    /** As above, scoped to one investment account's instruments when {@code investmentAccountParam} is set. */
    public static JsonElement series(Client client, ExchangeRateProviderFactory factory, String openingDateParam,
                    String closingDateParam, String currencyParam, String investmentAccountParam)
    {
        var errors = new ArrayList<ApiException.FieldError>();

        var openingDate = CalcParams.openingDate(openingDateParam, errors);
        var closingDate = CalcParams.closingDate(closingDateParam, errors);
        var currency = CalcParams.reportingCurrency(client, currencyParam, errors);
        var portfolio = CalcParams.investmentAccount(client, investmentAccountParam, errors);
        CalcParams.requireRange(openingDate, closingDate, errors);

        if (!errors.isEmpty())
            throw ApiException.badRequest(errors);

        // one array slot per calendar day upstream, so an unbounded range is an
        // unbounded response
        if (openingDate.plusYears(MAX_SERIES_YEARS).isBefore(closingDate))
            throw ApiException.badRequest(List.of(new ApiException.FieldError("closingDate", "invalid-range", //$NON-NLS-1$ //$NON-NLS-2$
                            "the range must not exceed " + MAX_SERIES_YEARS + " years"))); //$NON-NLS-1$ //$NON-NLS-2$

        var converter = new CurrencyConverterImpl(factory, currency);
        var interval = Interval.of(openingDate, closingDate);
        var index = PerformanceIndex.forClient(CalcParams.scope(client, portfolio), converter, interval,
                        new ArrayList<>());

        return EntityJson.performanceSeries(client, openingDate, closingDate, currency, index, portfolio);
    }
}
