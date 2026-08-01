package name.abuchen.portfolio.rest;

import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.rest.internal.AccountsHandler;
import name.abuchen.portfolio.rest.internal.ApiException;
import name.abuchen.portfolio.rest.internal.ConditionalGet;
import name.abuchen.portfolio.rest.internal.FileResolver;
import name.abuchen.portfolio.rest.internal.FilesHandler;
import name.abuchen.portfolio.rest.internal.HoldingsHandler;
import name.abuchen.portfolio.rest.internal.InstrumentChangeLog;
import name.abuchen.portfolio.rest.internal.OpenApiHandler;
import name.abuchen.portfolio.rest.internal.PairingHandler;
import name.abuchen.portfolio.rest.internal.PerformanceCalendarHandler;
import name.abuchen.portfolio.rest.internal.PerformanceHandler;
import name.abuchen.portfolio.rest.internal.PortfoliosHandler;
import name.abuchen.portfolio.rest.internal.Request;
import name.abuchen.portfolio.rest.internal.Response;
import name.abuchen.portfolio.rest.internal.Router;
import name.abuchen.portfolio.rest.internal.SecuritiesHandler;
import name.abuchen.portfolio.rest.internal.SecurityPerformanceHandler;
import name.abuchen.portfolio.rest.internal.SecurityPricesHandler;
import name.abuchen.portfolio.rest.internal.TaxonomiesHandler;
import name.abuchen.portfolio.rest.internal.TradesHandler;
import name.abuchen.portfolio.rest.internal.TransactionsHandler;
import name.abuchen.portfolio.rest.spi.HostApplication;
import name.abuchen.portfolio.rest.spi.OpenFile;

/**
 * Registers all v1 routes. Reads and writes are marshalled to the UI thread;
 * writes are additionally rejected with 423 while an application-modal dialog
 * is open or the user edits a table cell. Calculation endpoints only resolve
 * the {file} scope on the UI thread and compute on the HTTP worker thread.
 * Every file-scoped read answers with an ETag and honors If-None-Match, which
 * lets a client skip the work rather than only the bytes.
 */
public final class ApiRoutes
{
    private ApiRoutes()
    {
    }

    public static Router create(FileAccessRegistry registry, HostApplication host, PairingService pairing)
    {
        var router = new Router();
        var resolver = new FileResolver(registry, host);
        var files = new FilesHandler(registry, host);

        // the API's own description: a static resource, no UI thread, no auth
        router.add("GET", RestApiConstants.OPENAPI_ENDPOINT, request -> OpenApiHandler.serve()); //$NON-NLS-1$

        // pairing endpoints run on the HTTP worker thread: the service is
        // thread-safe and prompting the user is asynchronous by contract
        router.add("POST", "/v1/auth/requests", //$NON-NLS-1$ //$NON-NLS-2$
                        request -> PairingHandler.create(pairing, parseObject(request)));
        router.add("GET", "/v1/auth/requests/{id}", //$NON-NLS-1$ //$NON-NLS-2$
                        request -> PairingHandler.poll(pairing, request.pathParam("id"))); //$NON-NLS-1$

        router.add("GET", "/v1/files", onUiThread(host, files::list)); //$NON-NLS-1$ //$NON-NLS-2$

        router.add("GET", "/v1/files/{file}/instruments", read(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (client, req) -> Response.json(200, SecuritiesHandler.list(client))));
        // literal sub-collection: must precede the {uuid} route (Router is first-match)
        router.add("GET", "/v1/files/{file}/instruments/attribute-types", read(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (client, req) -> Response.json(200, SecuritiesHandler.attributeTypes(client))));
        router.add("GET", "/v1/files/{file}/instruments/{uuid}", read(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (client, req) -> Response.json(200, SecuritiesHandler.get(client, req.pathParam("uuid"))))); //$NON-NLS-1$
        router.add("GET", "/v1/files/{file}/instruments/{uuid}/prices", read(resolver, host,
                        (client, req) -> Response.json(200, SecurityPricesHandler.list(client,
                                        req.pathParam("uuid"), req.queryParam("from"), req.queryParam("to")))));
        router.add("PATCH", "/v1/files/{file}/instruments/{uuid}", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var result = SecuritiesHandler.patch(file.getClient(), req.pathParam("uuid"), //$NON-NLS-1$
                                            parseObject(req));
                            InstrumentChangeLog.record(file.getLabel(), result.instrumentName(), result.changes());
                            return Response.json(200, result.entity());
                        }));
        router.add("DELETE", "/v1/files/{file}/instruments/{uuid}", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var name = SecuritiesHandler.delete(file.getClient(), req.pathParam("uuid")); //$NON-NLS-1$
                            InstrumentChangeLog.recordDeletion(file.getLabel(), name);
                            return Response.noContent();
                        }));

        router.add("GET", "/v1/files/{file}/cash-accounts", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200,
                                        AccountsHandler.list(context.client(), context.factory(), req.queryParam("date"))))); //$NON-NLS-1$
        router.add("GET", "/v1/files/{file}/cash-accounts/{uuid}", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, AccountsHandler.get(context.client(), context.factory(),
                                        req.pathParam("uuid"), req.queryParam("date"))))); //$NON-NLS-1$ //$NON-NLS-2$

        router.add("GET", "/v1/files/{file}/investment-accounts", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, PortfoliosHandler.list(context.client(), context.factory(),
                                        req.queryParam("date"), req.queryParam("currency"))))); //$NON-NLS-1$ //$NON-NLS-2$
        router.add("GET", "/v1/files/{file}/investment-accounts/{uuid}", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, PortfoliosHandler.get(context.client(), context.factory(),
                                        req.pathParam("uuid"), req.queryParam("date"), req.queryParam("currency"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        router.add("GET", "/v1/files/{file}/taxonomies", read(resolver, host,
                        (client, req) -> Response.json(200, TaxonomiesHandler.list(client))));

        router.add("GET", "/v1/files/{file}/transactions", read(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (client, req) -> Response.json(200, TransactionsHandler.list(client))));

        router.add("GET", "/v1/files/{file}/holdings", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, HoldingsHandler.list(context.client(), context.factory(),
                                        req.queryParam("date"), req.queryParam("openingDate"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("currency"), req.queryParam("costMethod"))))); //$NON-NLS-1$ //$NON-NLS-2$

        router.add("GET", "/v1/files/{file}/performance", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, PerformanceHandler.list(context.client(),
                                        context.factory(), req.queryParam("openingDate"), //$NON-NLS-1$
                                        req.queryParam("closingDate"), req.queryParam("currency"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("costMethod"))))); //$NON-NLS-1$

        router.add("GET", "/v1/files/{file}/performance/series", calc(resolver, host,
                        (context, req) -> Response.json(200, PerformanceHandler.series(context.client(),
                                        context.factory(), req.queryParam("openingDate"),
                                        req.queryParam("closingDate"), req.queryParam("currency")))));

        router.add("GET", "/v1/files/{file}/performance/calendar", calc(resolver, host,
                        (context, req) -> Response.json(200, PerformanceCalendarHandler.list(context.client(),
                                        context.factory(), req.queryParam("openingDate"),
                                        req.queryParam("closingDate"), req.queryParam("currency")))));

        router.add("GET", "/v1/files/{file}/trades", calc(resolver, host,
                        (context, req) -> Response.json(200, TradesHandler.list(context.client(), context.factory(),
                                        req.queryParam("currency"), req.queryParam("onlyClosed")))));

        router.add("GET", "/v1/files/{file}/performance/securities", calc(resolver, host,
                        (context, req) -> Response.json(200, SecurityPerformanceHandler.list(context.client(),
                                        context.factory(), req.queryParam("openingDate"),
                                        req.queryParam("closingDate"), req.queryParam("currency"),
                                        req.queryParam("costMethod")))));

        return router;
    }

    /**
     * Runs the handler on the UI thread. Everything that touches the open files
     * or the model must go through here - including resolving the {file}
     * segment, which reads the list of open files.
     */
    private static Router.Handler onUiThread(HostApplication host, Router.Handler handler)
    {
        return request -> host.syncExec(() -> handler.handle(request));
    }

    /**
     * For read-only endpoints served from the UI thread: resolves the {file}
     * scope, derives the validator from the file's change counter in the same
     * turn - a counter read afterwards could already describe a different
     * snapshot - and only then runs the handler, so a client that already holds
     * the representation costs nothing but the resolve.
     */
    private static Router.Handler read(FileResolver resolver, HostApplication host,
                    BiFunction<Client, Request, Response> body)
    {
        return onUiThread(host, request -> {
            var resolved = resolver.resolve(request.pathParam("file")); //$NON-NLS-1$

            var etag = ConditionalGet.etag(resolved.file().getChangeCount(), request);
            if (ConditionalGet.isNotModified(request, etag))
                return ConditionalGet.notModified(etag);

            return body.apply(resolved.file().getClient(), request).withHeader(ConditionalGet.ETAG, etag);
        });
    }

    /** what a calculation endpoint needs, fetched from the host on the UI thread */
    /* package */ record CalcContext(Client client, ExchangeRateProviderFactory factory, long changeCount)
    {
    }

    /**
     * How often a calculation is redone before its result is served without a
     * validator. A user edit landing inside one calculation is already rare;
     * three in a row means the model changes faster than it can be reported,
     * and waiting longer would only hold the connection open.
     */
    private static final int CALC_ATTEMPTS = 3;

    /**
     * For read-only calculation endpoints: resolves the {file} scope on the UI
     * thread, but runs the calculation itself on the HTTP worker thread so that
     * an expensive computation cannot freeze the UI. The change counter is
     * sampled on the UI thread before and after, and the calculation is redone
     * when it moved - which turns "a concurrent user edit may rarely yield a
     * transiently inconsistent response" into a guarantee, without ever
     * blocking the UI, because the retry is a repeat of work that was already
     * cheap enough to do off-thread. If the model keeps moving, the last result
     * is served <em>without</em> an ETag: it may straddle an edit, and nothing
     * that may be wrong should become cacheable.
     */
    private static Router.Handler calc(FileResolver resolver, HostApplication host,
                    BiFunction<CalcContext, Request, Response> body)
    {
        return request -> {
            var context = resolveForCalc(resolver, host, request);

            var etag = ConditionalGet.etag(context.changeCount(), request);
            if (ConditionalGet.isNotModified(request, etag))
                return ConditionalGet.notModified(etag);

            for (var attempt = 1;; attempt++)
            {
                var response = body.apply(context, request);

                var after = resolveForCalc(resolver, host, request);
                if (after.changeCount() == context.changeCount())
                    return response.withHeader(ConditionalGet.ETAG,
                                    ConditionalGet.etag(context.changeCount(), request));

                if (attempt >= CALC_ATTEMPTS)
                    return response;

                // recompute against the state that superseded it, not the one
                // whose snapshot the answer already contradicts
                context = after;
            }
        };
    }

    private static CalcContext resolveForCalc(FileResolver resolver, HostApplication host, Request request)
                    throws Exception
    {
        return host.syncExec(() -> {
            var file = resolver.resolve(request.pathParam("file")).file(); //$NON-NLS-1$
            return new CalcContext(file.getClient(), file.getExchangeRateProviderFactory(), file.getChangeCount());
        });
    }

    private static Router.Handler write(FileResolver resolver, HostApplication host,
                    BiFunction<OpenFile, Request, Response> body)
    {
        return onUiThread(host, request -> {
            // resolve first: an unknown file is a 404 even while the user edits
            var resolved = resolver.resolve(request.pathParam("file")); //$NON-NLS-1$

            if (host.isUserEditing())
                throw ApiException.locked();

            return body.apply(resolved.file(), request);
        });
    }

    private static JsonObject parseObject(Request request)
    {
        try
        {
            var element = JsonParser.parseString(new String(request.body(), StandardCharsets.UTF_8));
            if (!element.isJsonObject())
                throw ApiException.badRequest("request body must be a JSON object"); //$NON-NLS-1$
            return element.getAsJsonObject();
        }
        catch (JsonSyntaxException e)
        {
            throw ApiException.badRequest("request body is not valid JSON"); //$NON-NLS-1$
        }
    }
}
