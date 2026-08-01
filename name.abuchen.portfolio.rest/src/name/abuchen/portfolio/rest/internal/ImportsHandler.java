package name.abuchen.portfolio.rest.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.ImportAction;
import name.abuchen.portfolio.datatransfer.SecurityCache;
import name.abuchen.portfolio.datatransfer.actions.CheckCurrenciesAction;
import name.abuchen.portfolio.datatransfer.actions.CheckForexGrossValueAction;
import name.abuchen.portfolio.datatransfer.actions.CheckSecurityRelatedValuesAction;
import name.abuchen.portfolio.datatransfer.actions.CheckTransactionDateAction;
import name.abuchen.portfolio.datatransfer.actions.CheckValidTypesAction;
import name.abuchen.portfolio.datatransfer.actions.DetectDuplicatesAction;
import name.abuchen.portfolio.datatransfer.actions.InsertAction;
import name.abuchen.portfolio.datatransfer.ibflex.IBFlexStatementExtractor;
import name.abuchen.portfolio.datatransfer.pdf.PDFImportAssistant;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.money.Values;

/**
 * Import: extract, review, apply - the same three steps the desktop wizard
 * takes, and for the same reason. Extraction produces {@link Extractor.Item}s
 * holding live model references, so they cannot be handed to a client and given
 * back; the client reviews a rendering and applies by index while the items
 * wait in an {@link ImportSessions} session.
 * <p>
 * The checks are the wizard's six, run in its order, and each item's statuses
 * are reported per check rather than collapsed into one verdict: "this is a
 * duplicate" and "this currency does not match an account" call for different
 * decisions from whoever is reviewing.
 */
public final class ImportsHandler
{
    /**
     * The check actions, each under the code its findings are reported with.
     * Kept as an ordered map because the code cannot be recovered from a
     * {@code Status} - it carries a severity and a human message only - so the
     * action that produced it is what names it.
     */
    private static final Map<String, ImportActionFactory> CHECKS = new LinkedHashMap<>();

    @FunctionalInterface
    private interface ImportActionFactory
    {
        ImportAction create(Client client);
    }

    static
    {
        CHECKS.put("transaction-date", client -> new CheckTransactionDateAction());
        CHECKS.put("invalid-type", client -> new CheckValidTypesAction());
        CHECKS.put("security-values", client -> new CheckSecurityRelatedValuesAction());
        CHECKS.put("duplicate", DetectDuplicatesAction::new);
        CHECKS.put("currency-mismatch", client -> new CheckCurrenciesAction());
        CHECKS.put("forex-gross-value", client -> new CheckForexGrossValueAction());
    }

    public record ApplyResult(JsonElement entity, int imported, int securitiesCreated)
    {
    }

    private ImportsHandler()
    {
    }

    /**
     * Runs the extractor the file's name selects - the PDF assistant, which
     * auto-detects the issuing bank, or the Interactive Brokers Flex reader -
     * and keeps the result as a session to review.
     */
    public static JsonObject extract(Client client, String fileId, String fileName, byte[] content)
    {
        if (fileName == null || fileName.isBlank())
            throw ApiException.badRequest("the filename query parameter names the file being imported");
        if (content == null || content.length == 0)
            throw ApiException.badRequest("the request body must be the file to import");

        var lower = fileName.toLowerCase(Locale.ROOT);
        var isPdf = lower.endsWith(".pdf");
        var isFlex = lower.endsWith(".xml");
        if (!isPdf && !isFlex)
        {
            throw ApiException.validation(List.of(new ApiException.FieldError("filename", "invalid-value",
                            "only .pdf and Interactive Brokers .xml Flex statements are supported")));
        }

        // The extractors read a File, and the PDF one converts it to text first;
        // the upload is therefore spooled rather than parsed from memory, and the
        // temporary copy is removed before this method returns either way.
        File spooled = null;
        try
        {
            spooled = Files.createTempFile("pp-import-", isPdf ? ".pdf" : ".xml").toFile();
            Files.write(spooled.toPath(), content);

            var errors = new ArrayList<Exception>();
            var items = isPdf ? extractPdf(client, spooled, errors) : extractFlex(client, spooled, errors);

            var session = ImportSessions.create(fileId, fileName, items,
                            errors.stream().map(ImportsHandler::describe).toList());
            return preview(client, session);
        }
        catch (IOException e)
        {
            throw new ApiException(500, "internal-error", "The uploaded file could not be read");
        }
        finally
        {
            if (spooled != null)
                deleteQuietly(spooled.toPath());
        }
    }

    private static List<Extractor.Item> extractPdf(Client client, File file, List<Exception> errors)
    {
        var perFile = new LinkedHashMap<File, List<Exception>>();
        var byExtractor = new PDFImportAssistant(client, List.of(file))
                        .run(new NullProgressMonitor(), perFile);

        perFile.values().forEach(errors::addAll);

        var items = new ArrayList<Extractor.Item>();
        byExtractor.values().forEach(items::addAll);
        return items;
    }

    private static List<Extractor.Item> extractFlex(Client client, File file, List<Exception> errors)
    {
        var extractor = new IBFlexStatementExtractor(client);
        return extractor.extract(new SecurityCache(client), new Extractor.InputFile(file), errors);
    }

    public static JsonObject preview(Client client, ImportSessions.Session session)
    {
        var checks = new ArrayList<Map.Entry<String, ImportAction>>();
        for (var entry : CHECKS.entrySet())
            checks.add(Map.entry(entry.getKey(), entry.getValue().create(client)));

        var context = new PreviewContext(client);

        var items = new JsonArray();
        var currencies = new LinkedHashSet<String>();

        for (var index = 0; index < session.items().size(); index++)
            items.add(toJson(session.items().get(index), index, checks, context, currencies));

        var json = new JsonObject();
        json.addProperty("id", session.id());
        json.addProperty("fileName", session.fileName());
        json.add("items", items);

        // Which accounts the caller has to name when applying: an item is booked
        // into an account of its own currency, so the answer is per currency.
        var required = new JsonArray();
        currencies.forEach(required::add);
        json.add("requiredCurrencies", required);

        var errors = new JsonArray();
        session.extractionErrors().forEach(errors::add);
        json.add("errors", errors);

        return json;
    }

    private static JsonObject toJson(Extractor.Item item, int index,
                    List<Map.Entry<String, ImportAction>> checks, PreviewContext context, LinkedHashSet<String> currencies)
    {
        var json = new JsonObject();
        json.addProperty("index", index);
        json.addProperty("type", item.getTypeInformation());
        json.addProperty("createsInstrument", item instanceof Extractor.SecurityItem);

        if (item.getDate() != null)
            json.addProperty("date", item.getDate().toString());

        var amount = item.getAmount();
        if (amount != null)
        {
            json.add("amount", EntityJson.toJson(amount));
            currencies.add(amount.getCurrencyCode());
        }

        if (item.getShares() != 0)
            json.add("shares", EntityJson.decimal(item.getShares(), Values.Share.precision()));

        var security = item.getSecurity();
        if (security != null)
        {
            var securityJson = new JsonObject();
            securityJson.addProperty("name", security.getName());
            if (security.getIsin() != null)
                securityJson.addProperty("isin", security.getIsin());
            securityJson.addProperty("currency", security.getCurrencyCode());
            json.add("security", securityJson);
        }

        if (item.getSource() != null)
            json.addProperty("source", item.getSource());

        json.add("checks", runChecks(item, checks, context));
        json.addProperty("status", worstOf(item, json.getAsJsonArray("checks")));

        if (item.isFailure() && item.getFailureMessage() != null)
            json.addProperty("failure", item.getFailureMessage());

        return json;
    }

    private static JsonArray runChecks(Extractor.Item item, List<Map.Entry<String, ImportAction>> checks,
                    PreviewContext context)
    {
        var results = new JsonArray();

        // A failed or skipped item never reaches the checks in the wizard either:
        // there is nothing coherent left to check.
        if (item.isFailure() || item.isSkipped())
            return results;

        for (var check : checks)
        {
            ImportAction.Status status;
            try
            {
                status = item.apply(check.getValue(), context);
            }
            catch (RuntimeException e)
            {
                // The wizard does the same: one check throwing must not abort the
                // preview, it marks the item unimportable and carries on.
                status = new ImportAction.Status(ImportAction.Status.Code.ERROR, String.valueOf(e.getMessage()));
            }

            if (status == null || status.getCode() == ImportAction.Status.Code.OK)
                continue;

            var result = new JsonObject();
            result.addProperty("code", check.getKey());
            result.addProperty("severity", severityOf(status.getCode()));
            if (status.getMessage() != null)
                result.addProperty("message", status.getMessage());
            results.add(result);
        }

        return results;
    }

    private static String severityOf(ImportAction.Status.Code code)
    {
        return switch (code)
        {
            case OK -> "ok";
            case SKIP -> "skipped";
            case WARNING -> "warning";
            case ERROR -> "error";
        };
    }

    private static String worstOf(Extractor.Item item, JsonArray checks)
    {
        if (item.isFailure())
            return "error";
        if (item.isSkipped())
            return "skipped";

        var worst = "ok";
        for (var check : checks)
        {
            var severity = check.getAsJsonObject().get("severity").getAsString();
            if ("error".equals(severity))
                return "error";
            if ("warning".equals(severity) || ("skipped".equals(severity) && "ok".equals(worst)))
                worst = severity;
        }
        return worst;
    }

    /**
     * Applies the selected items. The caller names an account per currency and
     * one investment account, exactly as the wizard's review page does, and the
     * two options it offers. Nothing is applied unless every selected index is
     * valid and every account it needs was named - a half-applied import would
     * leave the file in a state nobody chose.
     */
    public static ApplyResult apply(Client client, ImportSessions.Session session, JsonObject body)
    {
        var errors = new ArrayList<ApiException.FieldError>();

        var indices = readIndices(body, session.items().size(), errors);
        var accounts = readAccounts(client, body, errors);
        var portfolio = readPortfolio(client, body, errors);
        var convertBuySellToDelivery = readFlag(body, "convertBuySellToDelivery", errors);
        var skipDividends = readFlag(body, "skipDividends", errors);

        var selected = new ArrayList<Extractor.Item>();
        for (var index : indices)
        {
            var item = session.items().get(index);
            if (item.isFailure() || item.isSkipped())
                errors.add(new ApiException.FieldError("items", "invalid-value",
                                "item " + index + " could not be extracted and cannot be imported"));
            else
                selected.add(item);
        }

        for (var item : selected)
        {
            var amount = item.getAmount();
            if (amount != null && !accounts.containsKey(amount.getCurrencyCode()))
                errors.add(new ApiException.FieldError("accounts." + amount.getCurrencyCode(), "required",
                                "an account is needed for " + amount.getCurrencyCode()));
        }

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        var action = new InsertAction(client);
        action.setConvertBuySellToDelivery(convertBuySellToDelivery);
        action.setRemoveDividends(skipDividends);

        var context = new ApplyContext(accounts, portfolio);
        var securitiesBefore = client.getSecurities().size();

        for (var item : selected)
            item.apply(action, context);

        client.markDirty();
        ImportSessions.discard(session.id());

        var json = new JsonObject();
        json.addProperty("imported", selected.size());
        json.addProperty("instrumentsCreated", client.getSecurities().size() - securitiesBefore);
        return new ApplyResult(json, selected.size(), client.getSecurities().size() - securitiesBefore);
    }

    private static List<Integer> readIndices(JsonObject body, int count, List<ApiException.FieldError> errors)
    {
        var element = body.get("items");
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty())
        {
            errors.add(new ApiException.FieldError("items", "required", "items must be a non-empty array of indices"));
            return List.of();
        }

        var indices = new ArrayList<Integer>();
        for (var entry : element.getAsJsonArray())
        {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber())
            {
                errors.add(new ApiException.FieldError("items", "invalid-type", "items must be indices"));
                return List.of();
            }

            var index = entry.getAsInt();
            if (index < 0 || index >= count)
                errors.add(new ApiException.FieldError("items", "invalid-value", "no item at index " + index));
            else if (!indices.contains(index))
                indices.add(index);
        }
        return indices;
    }

    private static Map<String, Account> readAccounts(Client client, JsonObject body,
                    List<ApiException.FieldError> errors)
    {
        var accounts = new LinkedHashMap<String, Account>();

        var element = body.get("accounts");
        if (element == null || element.isJsonNull())
            return accounts;
        if (!element.isJsonObject())
        {
            errors.add(new ApiException.FieldError("accounts", "invalid-type",
                            "accounts must be an object keyed by currency"));
            return accounts;
        }

        for (var entry : element.getAsJsonObject().entrySet())
        {
            var field = "accounts." + entry.getKey();
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
            {
                errors.add(new ApiException.FieldError(field, "invalid-type", "must be an account uuid"));
                continue;
            }

            var uuid = entry.getValue().getAsString();
            var account = client.getAccounts().stream().filter(a -> uuid.equals(a.getUUID())).findAny().orElse(null);
            if (account == null)
                errors.add(new ApiException.FieldError(field, "unknown-reference", "no such cash account"));
            else if (!account.getCurrencyCode().equals(entry.getKey()))
                errors.add(new ApiException.FieldError(field, "currency-mismatch",
                                account.getName() + " holds " + account.getCurrencyCode()));
            else
                accounts.put(entry.getKey(), account);
        }

        return accounts;
    }

    private static Portfolio readPortfolio(Client client, JsonObject body, List<ApiException.FieldError> errors)
    {
        var element = body.get("portfolio");
        if (element == null || element.isJsonNull())
            return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
        {
            errors.add(new ApiException.FieldError("portfolio", "invalid-type", "portfolio must be a uuid"));
            return null;
        }

        var uuid = element.getAsString();
        var portfolio = client.getPortfolios().stream().filter(p -> uuid.equals(p.getUUID())).findAny().orElse(null);
        if (portfolio == null)
            errors.add(new ApiException.FieldError("portfolio", "unknown-reference", "no such investment account"));
        return portfolio;
    }

    private static boolean readFlag(JsonObject body, String field, List<ApiException.FieldError> errors)
    {
        var element = body.get(field);
        if (element == null || element.isJsonNull())
            return false;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
        {
            errors.add(new ApiException.FieldError(field, "invalid-type", field + " must be true or false"));
            return false;
        }
        return element.getAsBoolean();
    }

    /**
     * What the checks are run against. Every account of the right currency is
     * offered, because the preview's job is to report what *would* happen with a
     * sensible target rather than to insist the caller pick one first.
     */
    private static final class PreviewContext implements ImportAction.Context
    {
        private final Client client;

        private PreviewContext(Client client)
        {
            this.client = client;
        }

        @Override
        public Account getAccount(String currencyCode)
        {
            return client.getAccounts().stream().filter(a -> a.getCurrencyCode().equals(currencyCode)).findFirst()
                            .orElse(null);
        }

        @Override
        public Portfolio getPortfolio()
        {
            return client.getPortfolios().isEmpty() ? null : client.getPortfolios().get(0);
        }

        @Override
        public Account getSecondaryAccount(String currencyCode)
        {
            return getAccount(currencyCode);
        }

        @Override
        public Portfolio getSecondaryPortfolio()
        {
            return getPortfolio();
        }
    }

    /** What the caller chose, which is what the items are actually booked into. */
    private record ApplyContext(Map<String, Account> accounts, Portfolio portfolio) implements ImportAction.Context
    {
        @Override
        public Account getAccount(String currencyCode)
        {
            return accounts.get(currencyCode);
        }

        @Override
        public Portfolio getPortfolio()
        {
            return portfolio;
        }

        @Override
        public Account getSecondaryAccount(String currencyCode)
        {
            return accounts.get(currencyCode);
        }

        @Override
        public Portfolio getSecondaryPortfolio()
        {
            return portfolio;
        }
    }

    private static String describe(Exception e)
    {
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            // a leftover temporary file is not worth failing an otherwise good import
        }
    }
}
