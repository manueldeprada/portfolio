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
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

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
import name.abuchen.portfolio.datatransfer.csv.CSVConfig;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.Field;
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
     * The configurations a {@code .csv} upload may name, each as its
     * {@code label} and the extractor it targets ({@code account-transaction},
     * {@code portfolio-transaction}, {@code investment-vehicle},
     * {@code investment-vehicle-price} or {@code portfolio} - the same codes
     * {@link name.abuchen.portfolio.datatransfer.csv.CSVExtractor#getCode()}
     * returns). Includes both the bundled configurations and any saved with
     * {@code save=true} on a {@code mapping} upload; see {@link CSVConfigs}
     * for why a desktop-saved custom one is still not included.
     */
    public static JsonArray configs()
    {
        var array = new JsonArray();
        for (var config : CSVConfigs.all())
        {
            var json = new JsonObject();
            json.addProperty("label", config.getLabel());
            json.addProperty("target", config.getTarget());
            array.add(json);
        }
        return array;
    }

    /**
     * The header row, a handful of sample data rows, and the fields every CSV
     * target extractor knows how to fill - everything a client needs to render
     * an interactive column-mapping UI before it commits to a {@code mapping}
     * on {@code POST .../imports}. Read with the importer's default delimiter,
     * encoding and header assumptions (a comma-or-semicolon-per-locale
     * delimiter, the platform default encoding, first line as header): a file
     * that needs different ones still previews - the columns are then labeled
     * with whatever the wrong delimiter produced - but its mapping should be
     * built against the fields it will actually reach after posting to
     * {@code .../imports} with those settings named explicitly.
     */
    public static JsonObject previewCsv(Client client, String fileName, byte[] content)
    {
        if (fileName == null || fileName.isBlank())
            throw ApiException.badRequest("the filename query parameter names the file being previewed");
        if (content == null || content.length == 0)
            throw ApiException.badRequest("the request body must be the CSV file to preview");
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".csv"))
        {
            throw ApiException.validation(List.of(
                            new ApiException.FieldError("filename", "invalid-value", "only .csv files can be previewed")));
        }

        File spooled = null;
        try
        {
            spooled = Files.createTempFile("pp-import-preview-", ".csv").toFile();
            Files.write(spooled.toPath(), content);

            var importer = new CSVImporter(client, spooled);
            importer.processFile(true);

            var json = new JsonObject();

            var columns = new JsonArray();
            for (var column : importer.getColumns())
            {
                var columnJson = new JsonObject();
                columnJson.addProperty("index", column.getColumnIndex());
                columnJson.addProperty("label", column.getLabel());
                columns.add(columnJson);
            }
            json.add("columns", columns);

            var sampleRows = new JsonArray();
            var rawValues = importer.getRawValues();
            for (var ii = 0; ii < Math.min(5, rawValues.size()); ii++)
            {
                var row = new JsonArray();
                for (var value : rawValues.get(ii))
                    row.add(value);
                sampleRows.add(row);
            }
            json.add("sampleRows", sampleRows);

            var targets = new JsonArray();
            for (var extractor : importer.getExtractors())
            {
                var targetJson = new JsonObject();
                targetJson.addProperty("target", extractor.getCode());

                var fields = new JsonArray();
                for (var field : extractor.getFields())
                    fields.add(fieldToJson(field, client));
                targetJson.add("fields", fields);

                targets.add(targetJson);
            }
            json.add("targets", targets);

            return json;
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

    private static JsonObject fieldToJson(Field field, Client client)
    {
        var json = new JsonObject();
        json.addProperty("code", field.getCode());
        json.addProperty("name", field.getName());
        json.addProperty("optional", field.isOptional());

        var formats = new JsonArray();
        for (var format : field.getAvailableFieldFormats())
            if (format.getCode() != null)
                formats.add(format.getCode());
        json.add("formats", formats);

        return json;
    }

    /**
     * Runs the extractor the file's name selects - the PDF assistant, which
     * auto-detects the issuing bank, the Interactive Brokers Flex reader, or a
     * CSV file read with a named saved {@code configLabel} - and keeps the
     * result as a session to review.
     */
    public static JsonObject extract(Client client, String fileId, String fileName, byte[] content,
                    String configLabel)
    {
        return extract(client, fileId, fileName, content, configLabel, null, false);
    }

    /**
     * As above, with a CSV upload's two other ways to name a column mapping: an
     * inline {@code mappingJson} - the same shape {@code GET .../imports/configs}
     * cannot show yet, {@link CSVConfig#fromJSON} field for field - built by a
     * client that just walked its user through the columns {@code
     * .../imports/csv-preview} reported, and {@code save}, which keeps it under
     * its own {@code label} for later reuse by name. Exactly one of
     * {@code configLabel} and {@code mappingJson} is required for a {@code .csv}
     * upload; naming both is rejected rather than picking one silently.
     */
    public static JsonObject extract(Client client, String fileId, String fileName, byte[] content,
                    String configLabel, String mappingJson, boolean save)
    {
        if (fileName == null || fileName.isBlank())
            throw ApiException.badRequest("the filename query parameter names the file being imported");
        if (content == null || content.length == 0)
            throw ApiException.badRequest("the request body must be the file to import");

        var lower = fileName.toLowerCase(Locale.ROOT);
        var isPdf = lower.endsWith(".pdf");
        var isFlex = lower.endsWith(".xml");
        var isCsv = lower.endsWith(".csv");
        if (!isPdf && !isFlex && !isCsv)
        {
            throw ApiException.validation(List.of(new ApiException.FieldError("filename", "invalid-value",
                            "only .pdf, Interactive Brokers .xml Flex statements and .csv files are supported")));
        }

        CSVConfig csvConfig = null;
        if (isCsv)
        {
            var hasConfig = configLabel != null && !configLabel.isBlank();
            var hasMapping = mappingJson != null && !mappingJson.isBlank();

            if (hasConfig && hasMapping)
            {
                throw ApiException.validation(List.of(new ApiException.FieldError("mapping", "conflicting-value",
                                "name either a saved config or an inline mapping, not both")));
            }
            else if (hasConfig)
            {
                csvConfig = CSVConfigs.byLabel(configLabel).orElseThrow(() -> ApiException.validation(
                                List.of(new ApiException.FieldError("config", "unknown-reference",
                                                "no saved CSV configuration named \"" + configLabel + "\""))));
            }
            else if (hasMapping)
            {
                csvConfig = parseMapping(mappingJson);
                if (save)
                    CSVConfigs.save(csvConfig);
            }
            else
            {
                throw ApiException.validation(List.of(new ApiException.FieldError("config", "required",
                                "the config query parameter names the saved CSV configuration to read the file with, "
                                                + "or mapping supplies one inline; "
                                                + "GET .../imports/configs lists the available saved ones")));
            }
        }

        // The extractors read a File, and the PDF one converts it to text first;
        // the upload is therefore spooled rather than parsed from memory, and the
        // temporary copy is removed before this method returns either way.
        File spooled = null;
        try
        {
            var suffix = isPdf ? ".pdf" : isCsv ? ".csv" : ".xml";
            spooled = Files.createTempFile("pp-import-", suffix).toFile();
            Files.write(spooled.toPath(), content);

            var errors = new ArrayList<Exception>();
            List<Extractor.Item> items;
            try
            {
                items = isPdf ? extractPdf(client, spooled, errors)
                                : isCsv ? extractCsv(client, spooled, csvConfig, errors)
                                                : extractFlex(client, spooled, errors);
            }
            catch (RuntimeException e)
            {
                // A CSV mapping naming a target whose fields don't fit the data - most often
                // a "type" column holding values that aren't valid for the chosen target's
                // transaction type, e.g. "BUY" against account-transaction's own enum, which
                // only knows deposit/removal/interest/... - throws from inside the importer
                // itself rather than failing to parse a single value. That is a client mistake
                // (the wrong target or column mapping), not a server fault, so it is reported
                // as a validation error naming the mapping rather than surfacing as a 500.
                if (isCsv)
                {
                    throw ApiException.validation(List.of(new ApiException.FieldError("mapping", "invalid-value",
                                    "the file could not be read with this mapping: " + e.getMessage())));
                }
                throw e;
            }

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

    /**
     * Reads the spooled CSV with the named saved configuration and produces
     * the same kind of {@link Extractor.Item}s the PDF and Flex extractors do,
     * so review and apply need no CSV-specific handling. This mirrors the
     * desktop wizard's own sequence in {@code CSVImportDefinitionPage}: the
     * configuration is written into the importer before the first parse, so
     * the file is read once, already mapped, rather than auto-detected and
     * then corrected.
     */
    /**
     * Parses an inline {@code mapping} into the same {@link CSVConfig} a saved
     * one is, using its own {@link CSVConfig#fromJSON} - so the shape is
     * exactly what {@code CSVConfig#toJSON} produces and what a client that
     * exported a desktop-built config already has: {@code label}, {@code
     * target}, {@code delimiter}, {@code encoding}, {@code skipLines}, {@code
     * isFirstLineHeader} and {@code columns} (each an ordered {@code label} /
     * optional {@code field} code / optional {@code format} code, matched to
     * the uploaded file's columns by position, not by column label).
     */
    private static CSVConfig parseMapping(String mappingJson)
    {
        try
        {
            var parsed = JSONValue.parseWithException(mappingJson);
            if (!(parsed instanceof JSONObject json))
            {
                throw ApiException.validation(List.of(
                                new ApiException.FieldError("mapping", "invalid-type", "mapping must be a JSON object")));
            }

            var config = new CSVConfig();
            config.fromJSON(json);
            return config;
        }
        catch (ParseException | RuntimeException e)
        {
            if (e instanceof ApiException apiException)
                throw apiException;
            throw ApiException.validation(List.of(new ApiException.FieldError("mapping", "invalid-value",
                            String.valueOf(e.getMessage()))));
        }
    }

    private static List<Extractor.Item> extractCsv(Client client, File file, CSVConfig config,
                    List<Exception> errors) throws IOException
    {
        var importer = new CSVImporter(client, file);
        config.writeTo(importer);
        importer.processFile(false);
        return importer.createItems(errors);
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
