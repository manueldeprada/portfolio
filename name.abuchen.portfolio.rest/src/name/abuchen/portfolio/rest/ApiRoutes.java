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
import name.abuchen.portfolio.rest.internal.FileResolver;
import name.abuchen.portfolio.rest.internal.FilesHandler;
import name.abuchen.portfolio.rest.internal.HoldingsHandler;
import name.abuchen.portfolio.rest.internal.ImportSessions;
import name.abuchen.portfolio.rest.internal.ImportsHandler;
import name.abuchen.portfolio.rest.internal.InstrumentChangeLog;
import name.abuchen.portfolio.rest.internal.InstrumentPerformanceHandler;
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
import name.abuchen.portfolio.rest.internal.TaxonomyChangeLog;
import name.abuchen.portfolio.rest.internal.TradesHandler;
import name.abuchen.portfolio.rest.internal.VersionHandler;
import name.abuchen.portfolio.rest.internal.mcp.McpEndpoint;
import name.abuchen.portfolio.rest.internal.TransactionChangeLog;
import name.abuchen.portfolio.rest.internal.TransactionsHandler;
import name.abuchen.portfolio.rest.spi.HostApplication;
import name.abuchen.portfolio.rest.spi.OpenFile;

/**
 * Registers all v1 routes, and the MCP endpoint that dispatches into them.
 * Reads and writes are marshalled to the UI thread;
 * writes are additionally rejected with 423 while an application-modal dialog
 * is open or the user edits a table cell. Calculation endpoints only resolve
 * the {file} scope on the UI thread and compute on the HTTP worker thread.
 */
public final class ApiRoutes
{
    /** Shared query parameters for the instrument performance list and item routes. */
    private static final String[] INSTRUMENT_PERFORMANCE_PARAMS = { "openingDate", "closingDate", //$NON-NLS-1$ //$NON-NLS-2$
                    "reportingCurrency", "costMethod", "taxesAndFees", "metrics" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** Query parameters shared by both trade routes. */
    private static final String[] TRADE_PARAMS = { "status", "grouping", "costMethod", "taxesAndFees", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "reportingCurrency" }; //$NON-NLS-1$

    private ApiRoutes()
    {
    }

    public static Router create(FileAccessRegistry registry, HostApplication host, PairingService pairing)
    {
        var router = new Router();
        var resolver = new FileResolver(registry, host);
        var files = new FilesHandler(registry, host);

        // the MCP front door: registered on this same table and dispatching
        // back into it, so the sixteen tools cannot drift from the routes they
        // are - and GET and DELETE get the router's own 405
        McpEndpoint.register(router);

        // the API's own description: a static resource, no UI thread, no auth
        router.add("GET", RestApiConstants.OPENAPI_ENDPOINT, request -> OpenApiHandler.serve()); //$NON-NLS-1$

        // the contract version: likewise static, and cheap enough to poll
        router.add("GET", RestApiConstants.VERSION_ENDPOINT, request -> VersionHandler.serve()); //$NON-NLS-1$

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
        router.add("GET", "/v1/files/{file}/instruments/{uuid}/prices", read(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (client, req) -> Response.json(200, SecurityPricesHandler.list(client,
                                        req.pathParam("uuid"), req.queryParam("from"), req.queryParam("to")))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "from", "to"); //$NON-NLS-1$ //$NON-NLS-2$
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
                                        AccountsHandler.list(context.client(), context.factory(), req.queryParam("date")))), //$NON-NLS-1$
                        "date"); //$NON-NLS-1$
        router.add("GET", "/v1/files/{file}/cash-accounts/{uuid}", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, AccountsHandler.get(context.client(), context.factory(),
                                        req.pathParam("uuid"), req.queryParam("date")))), //$NON-NLS-1$ //$NON-NLS-2$
                        "date"); //$NON-NLS-1$
        router.add("GET", "/v1/files/{file}/cash-accounts/{uuid}/balances", read(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (client, req) -> Response.json(200, AccountsHandler.balances(client, req.pathParam("uuid"), //$NON-NLS-1$
                                        req.queryParam("from"), req.queryParam("to")))), //$NON-NLS-1$ //$NON-NLS-2$
                        "from", "to"); //$NON-NLS-1$ //$NON-NLS-2$
        router.add("POST", "/v1/files/{file}/accounts", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var entity = AccountsHandler.create(file.getClient(), parseObject(req));
                            return Response.created("/v1/files/" + req.pathParam("file") + "/cash-accounts/" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                            + entity.get("uuid").getAsString(), entity); //$NON-NLS-1$
                        }));

        router.add("GET", "/v1/files/{file}/investment-accounts", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, PortfoliosHandler.list(context.client(), context.factory(),
                                        req.queryParam("date"), req.queryParam("reportingCurrency")))), //$NON-NLS-1$ //$NON-NLS-2$
                        "date", "reportingCurrency"); //$NON-NLS-1$ //$NON-NLS-2$
        router.add("GET", "/v1/files/{file}/investment-accounts/{uuid}", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, PortfoliosHandler.get(context.client(), context.factory(),
                                        req.pathParam("uuid"), req.queryParam("date"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("reportingCurrency")))), //$NON-NLS-1$
                        "date", "reportingCurrency"); //$NON-NLS-1$ //$NON-NLS-2$
        router.add("POST", "/v1/files/{file}/portfolios", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var entity = PortfoliosHandler.create(file.getClient(), parseObject(req));
                            return Response.created("/v1/files/" + req.pathParam("file") + "/investment-accounts/" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                            + entity.get("uuid").getAsString(), entity); //$NON-NLS-1$
                        }));

        router.add("GET", "/v1/files/{file}/taxonomies", read(resolver, host,
                        (client, req) -> Response.json(200, TaxonomiesHandler.list(client))));
        router.add("POST", "/v1/files/{file}/taxonomies", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var result = TaxonomiesHandler.create(file.getClient(), parseObject(req));
                            TaxonomyChangeLog.recordCreation(file.getLabel(), result.taxonomyName());
                            return Response.created("/v1/files/" + req.pathParam("file") + "/taxonomies/" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                            + result.entity().get("id").getAsString(), result.entity()); //$NON-NLS-1$
                        }));
        router.add("GET", "/v1/files/{file}/taxonomies/{taxonomy}", read(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (client, req) -> Response.json(200,
                                        TaxonomiesHandler.get(client, req.pathParam("taxonomy"))))); //$NON-NLS-1$
        router.add("PATCH", "/v1/files/{file}/taxonomies/{taxonomy}", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var result = TaxonomiesHandler.patch(file.getClient(), req.pathParam("taxonomy"), //$NON-NLS-1$
                                            parseObject(req));
                            TaxonomyChangeLog.record(file.getLabel(), result.taxonomyName(), result.detail());
                            return Response.json(200, result.entity());
                        }));
        router.add("DELETE", "/v1/files/{file}/taxonomies/{taxonomy}", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var name = TaxonomiesHandler.delete(file.getClient(), req.pathParam("taxonomy")); //$NON-NLS-1$
                            TaxonomyChangeLog.recordDeletion(file.getLabel(), name);
                            return Response.noContent();
                        }));

        router.add("POST", "/v1/files/{file}/taxonomies/{taxonomy}/categories", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var result = TaxonomiesHandler.addCategory(file.getClient(),
                                            req.pathParam("taxonomy"), parseObject(req)); //$NON-NLS-1$
                            TaxonomyChangeLog.record(file.getLabel(), result.taxonomyName(), result.detail());
                            return Response.json(201, result.entity());
                        }));
        router.add("PATCH", "/v1/files/{file}/taxonomies/{taxonomy}/categories/{category}", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var result = TaxonomiesHandler.patchCategory(file.getClient(),
                                            req.pathParam("taxonomy"), req.pathParam("category"), //$NON-NLS-1$ //$NON-NLS-2$
                                            parseObject(req));
                            TaxonomyChangeLog.record(file.getLabel(), result.taxonomyName(), result.detail());
                            return Response.json(200, result.entity());
                        }));
        router.add("DELETE", "/v1/files/{file}/taxonomies/{taxonomy}/categories/{category}", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var result = TaxonomiesHandler.deleteCategory(file.getClient(),
                                            req.pathParam("taxonomy"), req.pathParam("category")); //$NON-NLS-1$ //$NON-NLS-2$
                            TaxonomyChangeLog.record(file.getLabel(), result.taxonomyName(), result.detail());
                            return Response.noContent();
                        }));

        router.add("PUT", "/v1/files/{file}/taxonomies/{taxonomy}/categories/{category}/assignments/{uuid}", //$NON-NLS-1$ //$NON-NLS-2$
                        write(resolver, host, (file, req) -> {
                            var result = TaxonomiesHandler.assign(file.getClient(), req.pathParam("taxonomy"), //$NON-NLS-1$
                                            req.pathParam("category"), req.pathParam("uuid"), //$NON-NLS-1$ //$NON-NLS-2$
                                            parseOptionalObject(req));
                            TaxonomyChangeLog.record(file.getLabel(), result.taxonomyName(), result.detail());
                            return Response.json(200, result.entity());
                        }));
        router.add("DELETE", "/v1/files/{file}/taxonomies/{taxonomy}/categories/{category}/assignments/{uuid}", //$NON-NLS-1$ //$NON-NLS-2$
                        write(resolver, host, (file, req) -> {
                            var result = TaxonomiesHandler.unassign(file.getClient(), req.pathParam("taxonomy"), //$NON-NLS-1$
                                            req.pathParam("category"), req.pathParam("uuid")); //$NON-NLS-1$ //$NON-NLS-2$
                            TaxonomyChangeLog.record(file.getLabel(), result.taxonomyName(), result.detail());
                            return Response.noContent();
                        }));

        router.add("GET", "/v1/files/{file}/transactions", read(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (client, req) -> Response.json(200, TransactionsHandler.list(client))));
        router.add("POST", "/v1/files/{file}/transactions", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var result = TransactionsHandler.create(file.getClient(), parseObject(req));
                            TransactionChangeLog.recordCreation(file.getLabel(), result.typeName(),
                                            result.ownerName(), result.entity().getAsJsonObject().get("value") //$NON-NLS-1$
                                                            .toString());
                            return Response.created(
                                            "/v1/files/" + req.pathParam("file") + "/transactions/" + result.uuid(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                            result.entity());
                        }));
        router.add("DELETE", "/v1/files/{file}/transactions/{uuid}", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var result = TransactionsHandler.delete(file.getClient(), req.pathParam("uuid")); //$NON-NLS-1$
                            TransactionChangeLog.recordDeletion(file.getLabel(), result.typeName(),
                                            result.ownerName(), result.uuid());
                            return Response.noContent();
                        }));

        // Import runs extract -> review -> apply, because an extracted item holds
        // live model references and cannot round-trip through JSON. The extraction
        // is a write in the sense that matters here: it must not run while the user
        // has a dialog open, and it creates the securities a later apply will add.
        // ... and the body of both is the uploaded file itself, so they take the
        // upload limit rather than the JSON one every other route lives within
        router.addUpload("POST", "/v1/files/{file}/imports", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> Response.json(201, ImportsHandler.extract(file.getClient(),
                                        file.getPath(), req.queryParam("filename"), req.body(), //$NON-NLS-1$
                                        req.queryParam("config"), req.queryParam("mapping"), //$NON-NLS-1$ //$NON-NLS-2$
                                        "true".equals(req.queryParam("save"))))), //$NON-NLS-1$ //$NON-NLS-2$
                        "filename", "config", "mapping", "save"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        router.addUpload("POST", "/v1/files/{file}/imports/csv-preview", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> Response.json(200, ImportsHandler.previewCsv(file.getClient(),
                                        req.queryParam("filename"), req.body()))), //$NON-NLS-1$
                        "filename"); //$NON-NLS-1$
        router.add("GET", "/v1/files/{file}/imports/configs", onUiThread(host, req -> { //$NON-NLS-1$ //$NON-NLS-2$
            resolver.resolve(req.pathParam("file")); //$NON-NLS-1$
            return Response.json(200, ImportsHandler.configs());
        }));
        router.add("GET", "/v1/files/{file}/imports/{id}", onUiThread(host, req -> {
            var file = resolver.resolve(req.pathParam("file")).file(); //$NON-NLS-1$
            return Response.json(200, ImportsHandler.preview(file.getClient(), session(file, req)));
        }));
        router.add("POST", "/v1/files/{file}/imports/{id}", write(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (file, req) -> {
                            var result = ImportsHandler.apply(file.getClient(), session(file, req),
                                            parseObject(req));
                            TransactionChangeLog.recordImport(file.getLabel(), result.imported(),
                                            result.securitiesCreated());
                            return Response.json(200, result.entity());
                        }));

        router.add("GET", "/v1/files/{file}/holdings", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, HoldingsHandler.list(context.client(), context.factory(),
                                        req.queryParam("date"), req.queryParam("openingDate"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("reportingCurrency"), req.queryParam("costMethod")))), //$NON-NLS-1$ //$NON-NLS-2$
                        "date", "openingDate", "reportingCurrency", "costMethod"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        router.add("GET", "/v1/files/{file}/performance", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, PerformanceHandler.list(context.client(),
                                        context.factory(), req.queryParam("openingDate"), //$NON-NLS-1$
                                        req.queryParam("closingDate"), req.queryParam("reportingCurrency"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("costMethod")))), //$NON-NLS-1$
                        "openingDate", "closingDate", "reportingCurrency", "costMethod"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        // the performance report broken down by instrument. Five segments like
        // /files/{file}/instruments/{uuid}, but segment 4 is a literal in both
        // and they differ, so first-match routing cannot confuse the two and no
        // registration-order guard is needed
        router.add("GET", "/v1/files/{file}/performance/instruments", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, InstrumentPerformanceHandler.list(context.client(),
                                        context.factory(), req.queryParam("openingDate"), //$NON-NLS-1$
                                        req.queryParam("closingDate"), req.queryParam("reportingCurrency"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("costMethod"), req.queryParam("taxesAndFees"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("metrics")))), //$NON-NLS-1$
                        INSTRUMENT_PERFORMANCE_PARAMS);
        router.add("GET", "/v1/files/{file}/performance/instruments/{uuid}", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, InstrumentPerformanceHandler.get(context.client(),
                                        context.factory(), req.pathParam("uuid"), req.queryParam("openingDate"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("closingDate"), req.queryParam("reportingCurrency"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("costMethod"), req.queryParam("taxesAndFees"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("metrics")))), //$NON-NLS-1$
                        INSTRUMENT_PERFORMANCE_PARAMS);

        // matched trades for the whole file or one instrument sub-collection
        router.add("GET", "/v1/files/{file}/trades", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, TradesHandler.list(context.client(), context.factory(),
                                        req.queryParam("status"), req.queryParam("grouping"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("costMethod"), req.queryParam("taxesAndFees"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("reportingCurrency")))), //$NON-NLS-1$
                        TRADE_PARAMS);
        router.add("GET", "/v1/files/{file}/instruments/{uuid}/trades", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, TradesHandler.forInstrument(context.client(),
                                        context.factory(), req.pathParam("uuid"), req.queryParam("status"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("grouping"), req.queryParam("costMethod"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("taxesAndFees"), req.queryParam("reportingCurrency")))), //$NON-NLS-1$ //$NON-NLS-2$
                        TRADE_PARAMS);

        router.add("GET", "/v1/files/{file}/performance/series", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, PerformanceHandler.series(context.client(),
                                        context.factory(), req.queryParam("openingDate"), //$NON-NLS-1$
                                        req.queryParam("closingDate"), //$NON-NLS-1$
                                        req.queryParam("reportingCurrency")))), //$NON-NLS-1$
                        "openingDate", "closingDate", "reportingCurrency"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        router.add("GET", "/v1/files/{file}/performance/calendar", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, PerformanceCalendarHandler.list(context.client(),
                                        context.factory(), req.queryParam("openingDate"), //$NON-NLS-1$
                                        req.queryParam("closingDate"), //$NON-NLS-1$
                                        req.queryParam("reportingCurrency")))), //$NON-NLS-1$
                        "openingDate", "closingDate", "reportingCurrency"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        router.add("GET", "/v1/files/{file}/performance/securities", calc(resolver, host, //$NON-NLS-1$ //$NON-NLS-2$
                        (context, req) -> Response.json(200, SecurityPerformanceHandler.list(context.client(),
                                        context.factory(), req.queryParam("openingDate"), //$NON-NLS-1$
                                        req.queryParam("closingDate"), req.queryParam("reportingCurrency"), //$NON-NLS-1$ //$NON-NLS-2$
                                        req.queryParam("costMethod")))), //$NON-NLS-1$
                        "openingDate", "closingDate", "reportingCurrency", "costMethod"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

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

    private static Router.Handler read(FileResolver resolver, HostApplication host,
                    BiFunction<Client, Request, Response> body)
    {
        return onUiThread(host, request -> {
            var resolved = resolver.resolve(request.pathParam("file")); //$NON-NLS-1$
            return body.apply(resolved.file().getClient(), request);
        });
    }

    /** what a calculation endpoint needs, fetched from the host on the UI thread */
    /* package */ record CalcContext(Client client, ExchangeRateProviderFactory factory)
    {
    }

    /**
     * For read-only calculation endpoints: resolves the {file} scope on the UI
     * thread, but runs the calculation itself on the HTTP worker thread so
     * that an expensive computation cannot freeze the UI. Deliberately without
     * a consistency guard: a concurrent user edit may - rarely - yield a
     * transiently inconsistent response or an internal error; retrying is
     * cheap for the client, blocking the UI is not.
     */
    private static Router.Handler calc(FileResolver resolver, HostApplication host,
                    BiFunction<CalcContext, Request, Response> body)
    {
        return request -> {
            var context = host.syncExec(() -> {
                var file = resolver.resolve(request.pathParam("file")).file(); //$NON-NLS-1$
                return new CalcContext(file.getClient(), file.getExchangeRateProviderFactory());
            });
            return body.apply(context, request);
        };
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

    /**
     * The extraction session named by the path, or a 404. Scoped to the file it
     * was extracted against: applying one file's extraction to another would
     * book transactions against securities that are not in it.
     */
    private static ImportSessions.Session session(OpenFile file, Request request)
    {
        var session = ImportSessions.get(file.getPath(), request.pathParam("id")); //$NON-NLS-1$
        if (session == null)
            throw ApiException.notFound();
        return session;
    }

    /**
     * The body, or an empty object when there is none - for a request whose
     * every field has a default, where insisting on {@code &#123;&#125;} would
     * be ceremony.
     */
    private static JsonObject parseOptionalObject(Request request)
    {
        return request.body() == null || request.body().length == 0 ? new JsonObject() : parseObject(request);
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
