package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.util.TextUtil;

/**
 * The import pipeline end to end, driven with a minimal Interactive Brokers
 * Flex statement: one purchase of an instrument the file does not have yet.
 * Deliberately not a PDF - a PDF fixture would test the bank-specific
 * extractors, which are core's own concern and are tested there; what belongs
 * here is that extract, review and apply hang together.
 */
@SuppressWarnings("nls")
public class ImportsTest
{
    private static final String FLEX_STATEMENT = """
                    <FlexQueryResponse queryName="AccountActivities" type="AF">
                    <FlexStatements count="1">
                    <FlexStatement accountId="U123456" fromDate="20260701" toDate="20260731" period="" \
                    whenGenerated="20260801 01:25:14">
                    <Trades>
                    <Trade accountId="U123456" currency="USD" assetCategory="STK" fxRateToBase="1" symbol="AAPL" \
                    description="APPLE INC" conid="265598" securityID="US0378331005" securityIDType="ISIN" \
                    isin="US0378331005" multiplier="1" tradeID="1" reportDate="20260715" tradeDate="20260715" \
                    tradeTime="120000" transactionType="ExchTrade" exchange="NASDAQ" quantity="10" tradePrice="100" \
                    tradeMoney="1000" proceeds="-1000" taxes="0" ibCommission="-1" ibCommissionCurrency="USD" \
                    closePrice="100" openCloseIndicator="O" notes="" cost="1001" fifoPnlRealized="0" mtmPnl="0" \
                    transactionID="1" buySell="BUY" levelOfDetail="EXECUTION" netCash="-1001" orderType="LMT" />
                    </Trades>
                    </FlexStatement>
                    </FlexStatements>
                    </FlexQueryResponse>
                    """;

    private static Client fixture()
    {
        var client = new Client();
        client.setBaseCurrency("USD");
        var account = new AccountBuilder("USD").addTo(client);
        new PortfolioBuilder(account).addTo(client);
        return client;
    }

    private static Account account(Client client)
    {
        return client.getAccounts().get(0);
    }

    private static JsonObject extract(Client client, String fileName, String content)
    {
        return ImportsHandler.extract(client, "/tmp/test.portfolio", fileName,
                        content.getBytes(StandardCharsets.UTF_8), null);
    }

    private static List<String> typesOf(JsonObject preview)
    {
        var types = new ArrayList<String>();
        for (var item : preview.getAsJsonArray("items"))
            types.add(item.getAsJsonObject().get("type").getAsString());
        return types;
    }

    private static JsonArray indices(JsonObject preview)
    {
        var indices = new JsonArray();
        for (var item : preview.getAsJsonArray("items"))
            indices.add(item.getAsJsonObject().get("index").getAsInt());
        return indices;
    }

    @Test
    public void testExtractionPreviewsWithoutWritingAnything()
    {
        var client = fixture();

        var preview = extract(client, "flex.xml", FLEX_STATEMENT);

        assertThat(preview.get("id").getAsString(), notNullValue());
        assertThat(preview.get("fileName").getAsString(), is("flex.xml"));
        assertThat(preview.getAsJsonArray("items").size(), greaterThan(0));

        // the whole point of a preview: the file is untouched until apply
        assertThat(account(client).getTransactions().isEmpty(), is(true));
        assertThat(client.getSecurities().isEmpty(), is(true));
    }

    @Test
    public void testThePreviewNamesTheInstrumentItWouldCreateAndTheCurrenciesItNeeds()
    {
        var client = fixture();

        var preview = extract(client, "flex.xml", FLEX_STATEMENT);

        var createsInstrument = false;
        for (var item : preview.getAsJsonArray("items"))
            createsInstrument |= item.getAsJsonObject().get("createsInstrument").getAsBoolean();
        assertThat(createsInstrument, is(true));

        var currencies = new ArrayList<String>();
        preview.getAsJsonArray("requiredCurrencies").forEach(c -> currencies.add(c.getAsString()));
        assertThat(currencies, hasItem("USD"));
    }

    @Test
    public void testASessionCanBeReadBackAndBelongsToItsFile()
    {
        var client = fixture();
        var preview = extract(client, "flex.xml", FLEX_STATEMENT);
        var id = preview.get("id").getAsString();

        var session = ImportSessions.get("/tmp/test.portfolio", id);
        assertThat(session, notNullValue());
        assertThat(typesOf(ImportsHandler.preview(client, session)), is(typesOf(preview)));

        // another file's session is simply not there, rather than forbidden
        assertThat(ImportSessions.get("/tmp/other.portfolio", id), nullValue());
    }

    @Test
    public void testApplyingBooksTheItemsAndCreatesTheInstrument()
    {
        var client = fixture();
        var preview = extract(client, "flex.xml", FLEX_STATEMENT);
        var session = ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString());

        var body = new JsonObject();
        body.add("items", indices(preview));
        var accounts = new JsonObject();
        accounts.addProperty("USD", account(client).getUUID());
        body.add("accounts", accounts);
        body.addProperty("portfolio", client.getPortfolios().get(0).getUUID());

        var result = ImportsHandler.apply(client, session, body);

        assertThat(result.imported(), greaterThan(0));
        assertThat(client.getSecurities().size(), is(1));
        assertThat(client.getSecurities().get(0).getIsin(), is("US0378331005"));
        assertThat(client.getPortfolios().get(0).getTransactions().size(), is(1));

        // the session is spent, so a replay imports nothing rather than twice
        assertThat(ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString()), nullValue());
    }

    @Test
    public void testApplyingWithoutAnAccountForACurrencyIsRefusedBeforeAnythingIsBooked()
    {
        var client = fixture();
        var preview = extract(client, "flex.xml", FLEX_STATEMENT);
        var session = ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString());

        var body = new JsonObject();
        body.add("items", indices(preview));
        body.addProperty("portfolio", client.getPortfolios().get(0).getUUID());

        try
        {
            ImportsHandler.apply(client, session, body);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("accounts.USD"));
        }

        assertThat(client.getPortfolios().get(0).getTransactions().isEmpty(), is(true));
    }

    @Test
    public void testAnAccountWhoseCurrencyContradictsItsKeyIsRejected()
    {
        var client = fixture();
        var chf = new AccountBuilder("CHF").addTo(client);
        var preview = extract(client, "flex.xml", FLEX_STATEMENT);
        var session = ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString());

        var body = new JsonObject();
        body.add("items", indices(preview));
        var accounts = new JsonObject();
        accounts.addProperty("USD", chf.getUUID());
        body.add("accounts", accounts);

        try
        {
            ImportsHandler.apply(client, session, body);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getErrors().get(0).code(), is("currency-mismatch"));
        }
    }

    @Test
    public void testAnIndexThatIsNotInThePreviewIsRejected()
    {
        var client = fixture();
        var preview = extract(client, "flex.xml", FLEX_STATEMENT);
        var session = ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString());

        var body = new JsonObject();
        var items = new JsonArray();
        items.add(999);
        body.add("items", items);

        try
        {
            ImportsHandler.apply(client, session, body);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getErrors().get(0).field(), is("items"));
        }
    }

    @Test
    public void testImportingTheSameStatementTwiceIsFlaggedAsDuplicate()
    {
        var client = fixture();

        var first = extract(client, "flex.xml", FLEX_STATEMENT);
        var session = ImportSessions.get("/tmp/test.portfolio", first.get("id").getAsString());
        var body = new JsonObject();
        body.add("items", indices(first));
        var accounts = new JsonObject();
        accounts.addProperty("USD", account(client).getUUID());
        body.add("accounts", accounts);
        body.addProperty("portfolio", client.getPortfolios().get(0).getUUID());
        ImportsHandler.apply(client, session, body);

        // the duplicate check is what makes re-importing a statement safe rather
        // than merely possible: it is reported per item, not as one verdict
        var second = extract(client, "flex.xml", FLEX_STATEMENT);
        var codes = new ArrayList<String>();
        for (var item : second.getAsJsonArray("items"))
            for (var check : item.getAsJsonObject().getAsJsonArray("checks"))
                codes.add(check.getAsJsonObject().get("code").getAsString());

        assertThat(codes, hasItem("duplicate"));
    }

    @Test
    public void testAFileTypeWithNoExtractorIsRejected()
    {
        var client = fixture();

        try
        {
            extract(client, "statement.ofx", "a,b,c");
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().stream().map(ApiException.FieldError::field).toList(), contains("filename"));
        }
    }

    @Test
    public void testAnEmptyUploadIsARequestError()
    {
        var client = fixture();

        try
        {
            ImportsHandler.extract(client, "/tmp/test.portfolio", "flex.xml", new byte[0], null);
            Assert.fail("expected a bad request");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(400));
        }
    }

    @Test
    public void testACsvUploadWithoutAConfigIsRejected()
    {
        var client = fixture();

        try
        {
            ImportsHandler.extract(client, "/tmp/test.portfolio", "statement.csv",
                            "a,b,c".getBytes(StandardCharsets.UTF_8), null);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("config"));
            assertThat(e.getErrors().get(0).code(), is("required"));
        }
    }

    @Test
    public void testACsvUploadWithAnUnknownConfigIsRejected()
    {
        var client = fixture();

        try
        {
            ImportsHandler.extract(client, "/tmp/test.portfolio", "statement.csv",
                            "a,b,c".getBytes(StandardCharsets.UTF_8), "no such config");
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("config"));
            assertThat(e.getErrors().get(0).code(), is("unknown-reference"));
        }
    }

    @Test
    public void testTheSavedConfigsAreListedByLabelAndTarget()
    {
        var configs = ImportsHandler.configs();

        assertThat(configs.size(), greaterThan(0));

        var config = configs.get(0).getAsJsonObject();
        assertThat(config.get("label").getAsString().isBlank(), is(false));
        assertThat(config.get("target").getAsString().isBlank(), is(false));
    }

    @Test
    public void testACsvUploadWithASavedConfigExtractsItemsTheSameWayAsAnyOtherStatement()
    {
        var client = fixture();

        var configs = ImportsHandler.configs();
        var portfolioConfig = "";
        for (var element : configs)
        {
            var config = element.getAsJsonObject();
            if ("portfolio".equals(config.get("target").getAsString()))
            {
                portfolioConfig = config.get("label").getAsString();
                break;
            }
        }
        assertThat("a bundled config targeting the portfolio extractor is expected to exist",
                        portfolioConfig.isBlank(), is(false));

        // deliberately not exercising the built-in mapping's business rules (which
        // columns are optional, which formats it expects) - that is core's own
        // concern; what this asserts is that a CSV upload with a named config
        // reaches the same extract/review pipeline PDF and Flex do, without error.
        var csv = "Stück/Nom.;Bezeichnung;WKN;Typ;Währung;Akt. Kurs\n\n\n\n\n10;APPLE INC;865985;Aktie;USD;100,00\n";
        var preview = ImportsHandler.extract(client, "/tmp/test.portfolio", "depot.csv",
                        csv.getBytes(StandardCharsets.UTF_8), portfolioConfig);

        assertThat(preview.get("id").getAsString(), notNullValue());
        assertThat(preview.get("fileName").getAsString(), is("depot.csv"));
        assertThat(preview.has("items"), is(true));
        assertThat(preview.has("errors"), is(true));

        // extraction alone must not write anything, exactly as for PDF and Flex
        assertThat(client.getSecurities().isEmpty(), is(true));
    }

    @Test
    public void testAStatementThatExtractsNothingIsAnEmptyPreviewRatherThanAnError()
    {
        var client = fixture();

        var preview = extract(client, "flex.xml", "<FlexQueryResponse></FlexQueryResponse>");

        assertThat(preview.getAsJsonArray("items").size(), is(0));
        assertThat(JsonParser.parseString(preview.toString()).getAsJsonObject().has("errors"), is(true));
    }

    private static final String SECURITIES_CSV = "Stück/Nom.;Bezeichnung;WKN;Typ;Währung;Akt. Kurs\n"
                    + "10;APPLE INC;865985;Aktie;USD;100,00\n";

    @Test
    public void testCsvPreviewReportsColumnsSampleRowsAndTargetFields()
    {
        var client = fixture();

        var preview = ImportsHandler.previewCsv(client, "depot.csv", SECURITIES_CSV.getBytes(StandardCharsets.UTF_8));

        var columns = preview.getAsJsonArray("columns");
        assertThat(columns.size(), is(6));
        assertThat(columns.get(0).getAsJsonObject().get("index").getAsInt(), is(0));
        assertThat(columns.get(1).getAsJsonObject().get("label").getAsString(), is("Bezeichnung"));

        var sampleRows = preview.getAsJsonArray("sampleRows");
        assertThat(sampleRows.size(), is(1));
        assertThat(sampleRows.get(0).getAsJsonArray().get(1).getAsString(), is("APPLE INC"));

        var targets = preview.getAsJsonArray("targets");
        assertThat(targets.size(), greaterThan(0));

        var codes = new ArrayList<String>();
        for (var element : targets)
            codes.add(element.getAsJsonObject().get("target").getAsString());
        assertThat(codes, hasItem("portfolio"));

        for (var element : targets)
        {
            var target = element.getAsJsonObject();
            var fields = target.getAsJsonArray("fields");
            assertThat(fields.size(), greaterThan(0));

            var field = fields.get(0).getAsJsonObject();
            assertThat(field.get("code").getAsString().isBlank(), is(false));
            assertThat(field.get("name").getAsString().isBlank(), is(false));
            assertThat(field.has("optional"), is(true));
            assertThat(field.has("formats"), is(true));
        }
    }

    /** The one target's fields, by field code. */
    private static JsonObject fieldsOf(JsonObject preview, String target)
    {
        var byCode = new JsonObject();
        for (var element : preview.getAsJsonArray("targets"))
        {
            var candidate = element.getAsJsonObject();
            if (!target.equals(candidate.get("target").getAsString()))
                continue;
            for (var field : candidate.getAsJsonArray("fields"))
                byCode.add(field.getAsJsonObject().get("code").getAsString(), field);
        }
        return byCode;
    }

    /** The one target's guesses, as "column index -> field code" (absent when null). */
    private static JsonObject guessesOf(JsonObject preview, String target)
    {
        var byColumn = new JsonObject();
        for (var element : preview.getAsJsonArray("targets"))
        {
            var candidate = element.getAsJsonObject();
            if (!target.equals(candidate.get("target").getAsString()))
                continue;
            for (var guess : candidate.getAsJsonArray("guesses"))
                byColumn.add(String.valueOf(guess.getAsJsonObject().get("column").getAsInt()), guess);
        }
        return byColumn;
    }

    @Test
    public void testEveryTargetReportsTheMappingTheApplicationWouldAutoDetect()
    {
        // the header uses one of the alias names ("Close") rather than the field's
        // own localized display name, which is precisely what a client cannot
        // reproduce and the application's own matcher can
        var d = String.valueOf(TextUtil.getListSeparatorChar());
        var csv = Messages.CSVColumn_Date + d + "Close" + d + "Something Else\n" //
                        + "2026-08-24" + d + "100.00" + d + "x\n";

        var preview = ImportsHandler.previewCsv(fixture(), "quotes.csv", csv.getBytes(StandardCharsets.UTF_8));

        var guesses = guessesOf(preview, "investment-vehicle-price");
        assertThat(preview.toString(), guesses.getAsJsonObject("0").get("field").getAsString(), is("date"));
        assertThat(preview.toString(), guesses.getAsJsonObject("1").get("field").getAsString(), is("quote"));

        // a column nothing matched is reported, with a null field, rather than omitted
        assertThat(guesses.getAsJsonObject("2").get("field").isJsonNull(), is(true));
        assertThat(guesses.getAsJsonObject("2").get("format").isJsonNull(), is(true));
    }

    @Test
    public void testAGuessedFormatComesFromTheColumnsOwnValues()
    {
        var d = String.valueOf(TextUtil.getListSeparatorChar());
        var csv = Messages.CSVColumn_Date + d + "Close\n" //
                        + "2026-08-24" + d + "100.00\n";

        var preview = ImportsHandler.previewCsv(fixture(), "quotes.csv", csv.getBytes(StandardCharsets.UTF_8));

        var guesses = guessesOf(preview, "investment-vehicle-price");
        assertThat(guesses.getAsJsonObject("0").get("format").getAsString(), is("yyyy-MM-dd"));
        assertThat(guesses.getAsJsonObject("1").get("format").getAsString(), is("0,000.00"));
    }

    @Test
    public void testTheGuessesAreComputedPerTargetNotOnlyForTheFirstOne()
    {
        // "Close" is a name of investment-vehicle-price's quote field and of no
        // field of account-transaction, so the two targets must disagree - which
        // they cannot if auto-detection only ever ran against the first extractor
        var d = String.valueOf(TextUtil.getListSeparatorChar());
        var csv = Messages.CSVColumn_Date + d + "Close\n" //
                        + "2026-08-24" + d + "100.00\n";

        var preview = ImportsHandler.previewCsv(fixture(), "quotes.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(guessesOf(preview, "investment-vehicle-price").getAsJsonObject("1").get("field").getAsString(),
                        is("quote"));
        assertThat(guessesOf(preview, "account-transaction").getAsJsonObject("1").get("field").isJsonNull(),
                        is(true));
    }

    @Test
    public void testEachFieldSaysWhatKindOfValueItHolds()
    {
        var preview = ImportsHandler.previewCsv(fixture(), "depot.csv",
                        SECURITIES_CSV.getBytes(StandardCharsets.UTF_8));

        var fields = fieldsOf(preview, "portfolio-transaction");
        assertThat(fields.getAsJsonObject("date").get("type").getAsString(), is("date"));
        assertThat(fields.getAsJsonObject("value").get("type").getAsString(), is("amount"));
        assertThat(fields.getAsJsonObject("type").get("type").getAsString(), is("enum"));
        assertThat(fields.getAsJsonObject("isin").get("type").getAsString(), is("isin"));
        assertThat(fields.getAsJsonObject("name").get("type").getAsString(), is("text"));
    }

    @Test
    public void testAFormatIsReportedWithItsOwnLabelNotOnlyItsCode()
    {
        var preview = ImportsHandler.previewCsv(fixture(), "depot.csv",
                        SECURITIES_CSV.getBytes(StandardCharsets.UTF_8));

        var formats = fieldsOf(preview, "portfolio-transaction").getAsJsonObject("value")
                        .getAsJsonArray("formats");
        assertThat(formats.size(), is(2));

        var codes = new ArrayList<String>();
        for (var element : formats)
        {
            var format = element.getAsJsonObject();
            codes.add(format.get("code").getAsString());
            // a code like "0.000,00" is a pattern; the label is what a person reads
            assertThat(format.get("label").getAsString().isBlank(), is(false));
        }
        assertThat(codes, hasItem("0.000,00"));
        assertThat(codes, hasItem("0,000.00"));
    }

    @Test
    public void testAnEnumFieldReportsTheConstantsAColumnCanBeMappedTo()
    {
        // an enum field has no available formats at all, so without these a client
        // has no way to discover what the "KEY=text;" format it must build may name
        var preview = ImportsHandler.previewCsv(fixture(), "depot.csv",
                        SECURITIES_CSV.getBytes(StandardCharsets.UTF_8));

        var type = fieldsOf(preview, "portfolio-transaction").getAsJsonObject("type");
        assertThat(type.getAsJsonArray("formats").size(), is(0));

        var keys = new ArrayList<String>();
        for (var element : type.getAsJsonArray("values"))
        {
            var value = element.getAsJsonObject();
            keys.add(value.get("key").getAsString());
            assertThat(value.get("label").getAsString().isBlank(), is(false));
        }
        assertThat(keys, hasItem("BUY"));
        assertThat(keys, hasItem("SELL"));
    }

    @Test
    public void testAnEnumFormatBuiltFromThoseKeysRoundTripsThroughAnInlineMapping()
    {
        // what a client does with `values`: it writes the file's own words against
        // the keys and sends that back as the column's `format`
        var client = fixture();

        var mapping = """
                        {
                          "label": "Spanish Words",
                          "target": "portfolio",
                          "delimiter": ",",
                          "encoding": "UTF-8",
                          "skipLines": 0,
                          "isFirstLineHeader": true,
                          "columns": [
                            {"label": "date", "field": "date"},
                            {"label": "type", "field": "type", "format": "BUY=compra;SELL=venta"},
                            {"label": "name", "field": "name"},
                            {"label": "shares", "field": "shares"},
                            {"label": "value", "field": "value"}
                          ]
                        }
                        """;

        var csv = "date,type,name,shares,value\n2026-08-24,compra,Apple Inc.,1,100.00\n";

        var preview = ImportsHandler.extract(client, "/tmp/test.portfolio", "buy.csv",
                        csv.getBytes(StandardCharsets.UTF_8), null, mapping, false);

        assertThat(preview.toString(), preview.getAsJsonArray("errors").size(), is(0));
        var transactionItem = preview.getAsJsonArray("items").asList().stream() //
                        .map(JsonElement::getAsJsonObject) //
                        .filter(item -> item.has("amount")) //
                        .findFirst().orElseThrow();
        assertThat(transactionItem.getAsJsonObject("amount").get("value").getAsInt(), is(100));
    }

    @Test
    public void testCsvPreviewReadsAFileWhoseRowsAreShorterThanItsHeader()
    {
        // A row that omits its trailing empty columns is shorter than the header,
        // which is what most exports produce for an empty last field. Auto-detection
        // samples each column's first non-empty value, and that read was unguarded,
        // so the whole preview died with an ArrayIndexOutOfBoundsException reported
        // as a bare 500.
        var client = fixture();

        // the header names two columns auto-detection recognizes (whatever this
        // JVM's locale calls them), so it does sample their values; the preview
        // reads with the importer's own default delimiter, which is locale-derived
        var d = String.valueOf(TextUtil.getListSeparatorChar());
        var csv = Messages.CSVColumn_Date + d + Messages.CSVColumn_Value + "\n" //
                        + "2026-08-24\n" //
                        + "2026-08-25" + d + "100.00\n";

        var preview = ImportsHandler.previewCsv(client, "depot.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(preview.getAsJsonArray("columns").size(), is(2));
        assertThat(preview.getAsJsonArray("sampleRows").size(), is(2));
    }

    /** This project's own documented CSV shape: nine columns, comma separated. */
    private static final String COMMA_CSV = "date,type,name,isin,shares,value,fees,taxes,note\n"
                    + "2026-08-24,BUY,Apple Inc.,US0378331005,1,100.00,1.00,0.00,first\n";

    private static List<String> labelsOf(JsonObject preview)
    {
        var labels = new ArrayList<String>();
        for (var element : preview.getAsJsonArray("columns"))
            labels.add(element.getAsJsonObject().get("label").getAsString());
        return labels;
    }

    @Test
    public void testACommaSeparatedFileIsSplitOnCommasWhenTheDelimiterIsNamed()
    {
        // The importer's default delimiter is the server JVM locale's list
        // separator, so this file previews as nine columns on an English-locale
        // server and as one on a German or Spanish one - and the client cannot
        // know which it is talking to. Both delimiters are therefore named
        // explicitly here rather than left to the machine running the test.
        var client = fixture();

        var previewed = ImportsHandler.previewCsv(client, "depot.csv",
                        COMMA_CSV.getBytes(StandardCharsets.UTF_8), ",", null, null, null);

        assertThat(previewed.toString(), previewed.getAsJsonArray("columns").size(), is(9));
        assertThat(labelsOf(previewed),
                        contains("date", "type", "name", "isin", "shares", "value", "fees", "taxes", "note"));

        var sampleRows = previewed.getAsJsonArray("sampleRows");
        assertThat(sampleRows.size(), is(1));
        assertThat(sampleRows.get(0).getAsJsonArray().size(), is(9));
        assertThat(sampleRows.get(0).getAsJsonArray().get(2).getAsString(), is("Apple Inc."));
    }

    @Test
    public void testTheWrongDelimiterIsWhatOneUnsplitColumnLooksLike()
    {
        // the failure this parameter exists to end, asserted rather than
        // described: the whole header line as a single column label, and
        // therefore a guess per target that has nothing to match
        var client = fixture();

        var previewed = ImportsHandler.previewCsv(client, "depot.csv",
                        COMMA_CSV.getBytes(StandardCharsets.UTF_8), ";", null, null, null);

        assertThat(previewed.getAsJsonArray("columns").size(), is(1));
        assertThat(labelsOf(previewed), contains("date,type,name,isin,shares,value,fees,taxes,note"));
        assertThat(guessesOf(previewed, "portfolio-transaction").getAsJsonObject("0").get("field").isJsonNull(),
                        is(true));
    }

    @Test
    public void testTheAutoDetectResolvesRealFieldsOnceTheSplitIsRight()
    {
        // the point of the parameter: the application's own matcher runs on the
        // columns the caller asked for. The header uses the localized names the
        // matcher knows, so the assertion holds on any JVM locale, while the
        // comma delimiter is the one this server would not have chosen.
        var d = ",";
        var csv = Messages.CSVColumn_Date + d + "Close" + d + "Something Else\n" //
                        + "2026-08-24" + d + "100.00" + d + "x\n";

        var previewed = ImportsHandler.previewCsv(fixture(), "quotes.csv", csv.getBytes(StandardCharsets.UTF_8),
                        ",", null, null, null);

        var guesses = guessesOf(previewed, "investment-vehicle-price");
        assertThat(previewed.toString(), guesses.getAsJsonObject("0").get("field").getAsString(), is("date"));
        assertThat(previewed.toString(), guesses.getAsJsonObject("1").get("field").getAsString(), is("quote"));
        assertThat(guesses.getAsJsonObject("2").get("field").isJsonNull(), is(true));
    }

    @Test
    public void testADelimiterMayBeNamedInsteadOfWritten()
    {
        // a tab has to be written %09 in a query string and is invisible in
        // anything that prints it afterwards, so it has a name; the other two
        // take one for symmetry
        var client = fixture();

        var byName = ImportsHandler.previewCsv(client, "depot.csv", COMMA_CSV.getBytes(StandardCharsets.UTF_8),
                        "comma", null, null, null);
        assertThat(byName.getAsJsonArray("columns").size(), is(9));

        var tabbed = "date\tvalue\n2026-08-24\t100.00\n";
        for (var spelling : List.of("tab", "\t"))
        {
            var previewed = ImportsHandler.previewCsv(client, "depot.csv",
                            tabbed.getBytes(StandardCharsets.UTF_8), spelling, null, null, null);
            assertThat(spelling, previewed.getAsJsonArray("columns").size(), is(2));
            assertThat(spelling, labelsOf(previewed), contains("date", "value"));
        }
    }

    @Test
    public void testADelimiterTheImporterDoesNotSupportIsRefusedRatherThanReplaced()
    {
        // CSVConfig#fromJSON silently substitutes a semicolon for anything it
        // does not recognize. Doing that here would recreate the very bug this
        // endpoint's delimiter parameter exists to fix, invisibly.
        try
        {
            ImportsHandler.previewCsv(fixture(), "depot.csv", COMMA_CSV.getBytes(StandardCharsets.UTF_8),
                            "|", null, null, null);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("delimiter"));
            assertThat(e.getErrors().get(0).code(), is("invalid-value"));
        }
    }

    @Test
    public void testTheFileIsReadInTheNamedEncoding()
    {
        var client = fixture();

        // "Año" is one byte per character in ISO-8859-1 and is not valid
        // UTF-8, so the two readings differ visibly rather than by accident.
        // Written as an escape so this assertion does not depend on the
        // encoding the compiler reads this source file in.
        var word = "A\u00F1o";
        var csv = ("date," + word + "\n2026-08-24,2026\n").getBytes(StandardCharsets.ISO_8859_1);

        var latin = ImportsHandler.previewCsv(client, "depot.csv", csv, ",", "ISO-8859-1", null, null);
        assertThat(labelsOf(latin), contains("date", word));

        var utf8 = ImportsHandler.previewCsv(client, "depot.csv", csv, ",", "UTF-8", null, null);
        assertThat(labelsOf(utf8).get(1), is(not(word)));
    }

    @Test
    public void testAnEncodingThisJvmDoesNotProvideIsRefused()
    {
        // reading in the wrong charset does not fail, it produces mojibake, so a
        // silent fall back to the server's own default would be invisible - the
        // same reason an inline mapping's encoding is refused rather than replaced
        try
        {
            ImportsHandler.previewCsv(fixture(), "depot.csv", COMMA_CSV.getBytes(StandardCharsets.UTF_8),
                            null, "NOT-A-CHARSET", null, null);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("encoding"));
            assertThat(e.getErrors().get(0).code(), is("invalid-value"));
            assertThat(e.getErrors().get(0).message(), containsString("NOT-A-CHARSET"));
        }
    }

    @Test
    public void testTheLinesBeforeTheHeaderAreSkipped()
    {
        var client = fixture();

        var csv = "# exported by something\n# 2026-08-24\n" + COMMA_CSV;

        var previewed = ImportsHandler.previewCsv(client, "depot.csv", csv.getBytes(StandardCharsets.UTF_8),
                        ",", null, "2", null);

        assertThat(previewed.toString(), labelsOf(previewed).get(0), is("date"));
        assertThat(previewed.getAsJsonArray("columns").size(), is(9));
        assertThat(previewed.getAsJsonArray("sampleRows").size(), is(1));
    }

    @Test
    public void testASkipLinesThatIsNotAWholeNumberOrIsNegativeIsRefused()
    {
        for (var value : List.of("two", "-1"))
        {
            try
            {
                ImportsHandler.previewCsv(fixture(), "depot.csv", COMMA_CSV.getBytes(StandardCharsets.UTF_8),
                                ",", null, value, null);
                Assert.fail("expected a validation error for skipLines=" + value);
            }
            catch (ApiException e)
            {
                assertThat(value, e.getStatus(), is(422));
                assertThat(value, e.getErrors().get(0).field(), is("skipLines"));
            }
        }
    }

    @Test
    public void testAFileWithNoHeaderRowReportsItsFirstLineAsData()
    {
        var client = fixture();

        var previewed = ImportsHandler.previewCsv(client, "depot.csv",
                        COMMA_CSV.getBytes(StandardCharsets.UTF_8), ",", null, null, "false");

        // the header line is now a data row, and the columns are labeled
        // positionally rather than by a name the matcher could recognize
        assertThat(previewed.getAsJsonArray("columns").size(), is(9));
        assertThat(labelsOf(previewed), not(hasItem("date")));
        assertThat(previewed.getAsJsonArray("sampleRows").size(), is(2));
        assertThat(previewed.getAsJsonArray("sampleRows").get(0).getAsJsonArray().get(0).getAsString(),
                        is("date"));
    }

    @Test
    public void testAnIsFirstLineHeaderThatIsNeitherTrueNorFalseIsRefused()
    {
        try
        {
            ImportsHandler.previewCsv(fixture(), "depot.csv", COMMA_CSV.getBytes(StandardCharsets.UTF_8),
                            ",", null, null, "maybe");
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("isFirstLineHeader"));
            assertThat(e.getErrors().get(0).code(), is("invalid-value"));
        }
    }

    @Test
    public void testEveryBadParseSettingIsReportedAtOnce()
    {
        // the convention the rest of this API follows: a caller that got three
        // of them wrong hears all three, not one per round trip
        try
        {
            ImportsHandler.previewCsv(fixture(), "depot.csv", COMMA_CSV.getBytes(StandardCharsets.UTF_8),
                            "|", "NOT-A-CHARSET", "-1", "maybe");
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            var fields = e.getErrors().stream().map(ApiException.FieldError::field).toList();
            assertThat(fields, contains("delimiter", "encoding", "skipLines", "isFirstLineHeader"));
        }
    }

    @Test
    public void testNamingNoneOfThemLeavesTheImportersOwnDefaultsInPlace()
    {
        // an existing caller sees no change: a file written with this JVM's own
        // list separator previews exactly as it did before the four parameters
        // existed, and the two overloads agree
        var client = fixture();

        var d = String.valueOf(TextUtil.getListSeparatorChar());
        var csv = Messages.CSVColumn_Date + d + Messages.CSVColumn_Value + "\n" //
                        + "2026-08-24" + d + "100.00\n";

        var previewed = ImportsHandler.previewCsv(client, "depot.csv", csv.getBytes(StandardCharsets.UTF_8),
                        null, null, null, null);

        assertThat(previewed.getAsJsonArray("columns").size(), is(2));
        assertThat(labelsOf(previewed), contains(Messages.CSVColumn_Date, Messages.CSVColumn_Value));
        assertThat(previewed.getAsJsonArray("sampleRows").size(), is(1));
        assertThat(previewed,
                        is(ImportsHandler.previewCsv(client, "depot.csv", csv.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void testABlankParseSettingIsTreatedAsUnnamed()
    {
        // a client that renders empty inputs sends "" rather than omitting the
        // parameter; that must mean "no opinion", not "an unusable value"
        var client = fixture();

        var previewed = ImportsHandler.previewCsv(client, "depot.csv",
                        COMMA_CSV.getBytes(StandardCharsets.UTF_8), ",", "", "", "");

        assertThat(previewed.getAsJsonArray("columns").size(), is(9));
    }

    @Test
    public void testCsvPreviewRejectsANonCsvFilename()
    {
        var client = fixture();

        try
        {
            ImportsHandler.previewCsv(client, "statement.pdf", "a,b,c".getBytes(StandardCharsets.UTF_8));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("filename"));
        }
    }

    @Test
    public void testACsvUploadWithNeitherConfigNorMappingIsRejected()
    {
        var client = fixture();

        try
        {
            ImportsHandler.extract(client, "/tmp/test.portfolio", "statement.csv",
                            "a,b,c".getBytes(StandardCharsets.UTF_8), null, null, false);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("config"));
            assertThat(e.getErrors().get(0).code(), is("required"));
        }
    }

    @Test
    public void testACsvUploadWithBothConfigAndMappingIsRejected()
    {
        var client = fixture();

        try
        {
            ImportsHandler.extract(client, "/tmp/test.portfolio", "statement.csv",
                            "a,b,c".getBytes(StandardCharsets.UTF_8), "SECURITIES", "{}", false);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("mapping"));
            assertThat(e.getErrors().get(0).code(), is("conflicting-value"));
        }
    }

    @Test
    public void testACsvUploadWithAnInvalidMappingIsRejected()
    {
        var client = fixture();

        try
        {
            ImportsHandler.extract(client, "/tmp/test.portfolio", "statement.csv",
                            "a,b,c".getBytes(StandardCharsets.UTF_8), null, "not json", false);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("mapping"));
        }
    }

    @Test
    public void testAMappingNamingAnEncodingThisJvmDoesNotHaveIsRejected()
    {
        // CSVConfig#fromJSON used to swallow the charset failure and fall back to
        // the platform default, so a client naming an encoding the server does not
        // have silently got the server's own - and reading a file with the wrong
        // charset does not fail, it produces mojibake, which nothing downstream
        // reports either.
        var client = fixture();

        var mapping = """
                        {
                          "label": "Bad Encoding",
                          "target": "portfolio",
                          "delimiter": ";",
                          "encoding": "NO-SUCH-CHARSET-42",
                          "skipLines": 0,
                          "isFirstLineHeader": true,
                          "columns": [
                            {"label": "Bezeichnung", "field": "name"}
                          ]
                        }
                        """;

        try
        {
            ImportsHandler.extract(client, "/tmp/test.portfolio", "depot.csv",
                            SECURITIES_CSV.getBytes(StandardCharsets.UTF_8), null, mapping, false);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("mapping.encoding"));
            assertThat(e.getErrors().get(0).code(), is("invalid-value"));
            assertThat(e.getErrors().get(0).message(), containsString("NO-SUCH-CHARSET-42"));
        }
    }

    @Test
    public void testACsvUploadWithAnInlineMappingExtractsItemsAndCanBeSavedForReuse()
    {
        var client = fixture();

        var mapping = """
                        {
                          "label": "My Depot Mapping",
                          "target": "portfolio",
                          "delimiter": ";",
                          "encoding": "UTF-8",
                          "skipLines": 0,
                          "isFirstLineHeader": true,
                          "columns": [
                            {"label": "Stück/Nom.", "field": "shares"},
                            {"label": "Bezeichnung", "field": "name"},
                            {"label": "WKN", "field": "wkn"},
                            {"label": "Typ"},
                            {"label": "Währung", "field": "currency"},
                            {"label": "Akt. Kurs", "field": "quote"}
                          ]
                        }
                        """;

        var preview = ImportsHandler.extract(client, "/tmp/test.portfolio", "depot.csv",
                        SECURITIES_CSV.getBytes(StandardCharsets.UTF_8), null, mapping, true);

        assertThat(preview.get("id").getAsString(), notNullValue());
        assertThat(preview.has("items"), is(true));

        // saved: now visible by label, both to the config list and to a later
        // extraction naming it with `config` rather than `mapping`
        var labels = new ArrayList<String>();
        for (var element : ImportsHandler.configs())
            labels.add(element.getAsJsonObject().get("label").getAsString());
        assertThat(labels, hasItem("My Depot Mapping"));

        var reused = ImportsHandler.extract(client, "/tmp/test.portfolio", "depot2.csv",
                        SECURITIES_CSV.getBytes(StandardCharsets.UTF_8), "My Depot Mapping");
        assertThat(reused.has("items"), is(true));
    }

    @Test
    public void testATypeColumnMatchesTheEnumConstantNameRegardlessOfServerLocale()
    {
        // EnumMapFormat#parseObject only matched a value against the *localized* display
        // label (PortfolioTransaction.Type.BUY.toString(), "Compra" on a Spanish JVM) - a
        // REST client (or an LLM asked to build a CSV from a plain-language prompt, this
        // app's own documented shape) has no way to know the server's runtime locale, so
        // "BUY" could only ever import successfully on an English-locale server. Reported
        // live: a Spanish-locale daemon rejected every row of a "BUY" CSV with "No ha sido
        // posible procesar 'BUY' con el patrón {Depósito=Depósito, ..., Compra=Compra, ...}".
        var previousLocale = Locale.getDefault();
        Locale.setDefault(new Locale("es", "ES"));
        try
        {
            var client = fixture();

            var mapping = """
                            {
                              "label": "Enum By Constant Name",
                              "target": "portfolio",
                              "delimiter": ",",
                              "encoding": "UTF-8",
                              "skipLines": 0,
                              "isFirstLineHeader": true,
                              "columns": [
                                {"label": "date", "field": "date"},
                                {"label": "type", "field": "type"},
                                {"label": "name", "field": "name"},
                                {"label": "shares", "field": "shares"},
                                {"label": "value", "field": "value"}
                              ]
                            }
                            """;

            var csv = "date,type,name,shares,value\n2026-08-24,BUY,Apple Inc.,1,100.00\n";

            var preview = ImportsHandler.extract(client, "/tmp/test.portfolio", "buy.csv",
                            csv.getBytes(StandardCharsets.UTF_8), null, mapping, false);

            assertThat(preview.toString(), preview.getAsJsonArray("errors").size(), is(0));
        }
        finally
        {
            Locale.setDefault(previousLocale);
        }
    }

    @Test
    public void testAnInlineMappingWithNoExplicitFormatOnAnAmountOrDateFieldStillExtracts()
    {
        // CSVConfig#writeTo only sets a column's format from an explicit "format" in the
        // mapping; when none is given (the common case - this app's own documented CSV
        // shape, docs/table-to-csv-prompt.txt in the bursae repo, never sends one) it falls
        // back to Field#guessFormat. That fallback used to call guessFormat only for its
        // side effect and discard the FieldFormat it returned, leaving the column's format
        // null - and CSVExtractor later dereferences it unconditionally
        // (field2column.get(name).getFormat().getFormat()) to parse every date and amount
        // value, so this crashed with a NullPointerException on the correct target too, not
        // only a mismatched one.
        var client = fixture();

        var mapping = """
                        {
                          "label": "No Explicit Formats",
                          "target": "portfolio",
                          "delimiter": ",",
                          "encoding": "UTF-8",
                          "skipLines": 0,
                          "isFirstLineHeader": true,
                          "columns": [
                            {"label": "date", "field": "date"},
                            {"label": "type", "field": "type"},
                            {"label": "name", "field": "name"},
                            {"label": "shares", "field": "shares"},
                            {"label": "value", "field": "value"}
                          ]
                        }
                        """;

        var csv = "date,type,name,shares,value\n2026-08-24,BUY,Apple Inc.,1,100.00\n";

        var preview = ImportsHandler.extract(client, "/tmp/test.portfolio", "buy.csv",
                        csv.getBytes(StandardCharsets.UTF_8), null, mapping, false);

        // Two items, not one, is correct here and unrelated to this test's own concern:
        // "Apple Inc." does not exist yet, so the transaction item comes with a second
        // "create this instrument" item alongside it (see
        // testApplyingBooksTheItemsAndCreatesTheInstrument). What this test actually
        // checks is that the transaction item's own date/amount were read at all,
        // rather than the whole extraction failing to parse "2026-08-24" against a
        // locale-guessed format no sample value was ever given to reject.
        assertThat(preview.toString(), preview.getAsJsonArray("errors").size(), is(0));
        var transactionItem = preview.getAsJsonArray("items").asList().stream()
                        .map(JsonElement::getAsJsonObject)
                        .filter(item -> item.has("date"))
                        .findFirst().orElseThrow();
        //
        // The amount asserted here used to be 10000, which was this very machine's
        // locale reading the file wrong: "100.00" parsed with the comma convention
        // drops the dot as a grouping separator and books 10000.00. See
        // testAnAmountIsReadWithTheFilesOwnDecimalConventionNotTheServersLocale.
        assertThat(transactionItem.get("date").getAsString(), is("2026-08-24T00:00"));
        assertThat(transactionItem.getAsJsonObject("amount").get("value").getAsInt(), is(100));
    }

    @Test
    public void testAnAmountIsReadWithTheFilesOwnDecimalConventionNotTheServersLocale()
    {
        // AmountField#guessFormat ignored the sample value entirely and returned the
        // format for the JVM's default locale, so a column with no explicit "format"
        // was parsed with the server's decimal convention rather than the file's. Both
        // readings parse - AmountFormat discards the separator it was not told about as
        // a grouping separator - so a dot-decimal "100.00" read by a comma-locale daemon
        // booked 10000.00 with no error anywhere. Asserting both conventions in one test
        // is what makes it a regression test on any JVM locale: before the fix exactly
        // one of the two was off by a factor of a thousand, whichever one this runs under.
        assertThat(amountOf(extractWithoutFormats(",", "100.00")), is(100L));
        assertThat(amountOf(extractWithoutFormats(";", "100,00")), is(100L));
    }

    /** Extracts one purchase whose amount is written with the given separator. */
    private static JsonObject extractWithoutFormats(String delimiter, String amount)
    {
        var mapping = """
                        {
                          "label": "No Explicit Formats",
                          "target": "portfolio",
                          "delimiter": "%s",
                          "encoding": "UTF-8",
                          "skipLines": 0,
                          "isFirstLineHeader": true,
                          "columns": [
                            {"label": "date", "field": "date"},
                            {"label": "type", "field": "type"},
                            {"label": "name", "field": "name"},
                            {"label": "shares", "field": "shares"},
                            {"label": "value", "field": "value"}
                          ]
                        }
                        """.formatted(delimiter);

        var csv = String.join(delimiter, "date", "type", "name", "shares", "value") + "\n"
                        + String.join(delimiter, "2026-08-24", "BUY", "Apple Inc.", "1", amount) + "\n";

        return ImportsHandler.extract(fixture(), "/tmp/test.portfolio", "buy.csv",
                        csv.getBytes(StandardCharsets.UTF_8), null, mapping, false);
    }

    /** The amount of the one item that carries one, as the decimal the API reports. */
    private static long amountOf(JsonObject preview)
    {
        assertThat(preview.toString(), preview.getAsJsonArray("errors").size(), is(0));
        return preview.getAsJsonArray("items").asList().stream() //
                        .map(JsonElement::getAsJsonObject) //
                        .filter(item -> item.has("amount")) //
                        .findFirst().orElseThrow() //
                        .getAsJsonObject("amount").get("value").getAsLong();
    }

    @Test
    public void testATypeValueOnlyValidForAnotherTargetIsAPerRowErrorNotAServerError()
    {
        // "BUY" is a name.abuchen.portfolio.model.PortfolioTransaction.Type value, not one of
        // AccountTransaction.Type's - a client naming the wrong target for its data (or a
        // frontend defaulting to the first target the server lists) used to throw a
        // NullPointerException out of CSVConfig#writeTo (it called Field#guessFormat for its
        // side effect and discarded the FieldFormat it returned, leaving every column whose
        // format was not given explicitly - which is every EnumField, and any AmountField/
        // DateField without an explicit "format" - with no format at all) and surfaced as an
        // uncaught 500. With that fixed, CSVImporter's own per-row handling (BaseCSVExtractor)
        // takes over correctly: an unparseable value is one line's error, not a fatal one.
        var client = fixture();

        var mapping = """
                        {
                          "label": "Wrong Target",
                          "target": "account-transaction",
                          "delimiter": ",",
                          "encoding": "UTF-8",
                          "skipLines": 0,
                          "isFirstLineHeader": true,
                          "columns": [
                            {"label": "date", "field": "date"},
                            {"label": "type", "field": "type"},
                            {"label": "value", "field": "value"}
                          ]
                        }
                        """;

        var csv = "date,type,value\n2026-08-24,BUY,100.00\n";

        var preview = ImportsHandler.extract(client, "/tmp/test.portfolio", "statement.csv",
                        csv.getBytes(StandardCharsets.UTF_8), null, mapping, false);

        assertThat(preview.getAsJsonArray("items").size(), is(0));
        assertThat(JsonParser.parseString(preview.toString()).getAsJsonObject().has("errors"), is(true));
    }

    private static final String NOTE_MAPPING = """
                    {
                      "label": "With Notes",
                      "target": "portfolio-transaction",
                      "delimiter": ",",
                      "encoding": "UTF-8",
                      "skipLines": 0,
                      "isFirstLineHeader": true,
                      "columns": [
                        {"label": "date", "field": "date"},
                        {"label": "type", "field": "type"},
                        {"label": "name", "field": "name"},
                        {"label": "shares", "field": "shares"},
                        {"label": "value", "field": "value"},
                        {"label": "note", "field": "note"}
                      ]
                    }
                    """;

    private static final String NOTE_CSV = "date,type,name,shares,value,note\n"
                    + "2026-08-24,BUY,Apple Inc.,1,100.00,order 4711\n";

    /** Books the note-carrying CSV and answers the note the transaction ended up with. */
    private static String noteAfterApplying(Boolean importNotesFromSource)
    {
        var client = fixture();

        var preview = ImportsHandler.extract(client, "/tmp/test.portfolio", "buy.csv",
                        NOTE_CSV.getBytes(StandardCharsets.UTF_8), null, NOTE_MAPPING, false);
        var session = ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString());

        var body = new JsonObject();
        body.add("items", indices(preview));
        var accounts = new JsonObject();
        accounts.addProperty("USD", account(client).getUUID());
        body.add("accounts", accounts);
        body.addProperty("portfolio", client.getPortfolios().get(0).getUUID());
        if (importNotesFromSource != null)
            body.addProperty("importNotesFromSource", importNotesFromSource);

        ImportsHandler.apply(client, session, body);

        return client.getPortfolios().get(0).getTransactions().get(0).getNote();
    }

    @Test
    public void testTheStatementsOwnNoteIsKeptByDefault()
    {
        assertThat(noteAfterApplying(null), is("order 4711"));
        assertThat(noteAfterApplying(Boolean.TRUE), is("order 4711"));
    }

    @Test
    public void testTheStatementsOwnNoteCanBeDropped()
    {
        assertThat(noteAfterApplying(Boolean.FALSE), is(nullValue()));
    }

    private static final String PRICE_MAPPING = """
                    {
                      "label": "Quotes",
                      "target": "investment-vehicle-price",
                      "delimiter": ",",
                      "encoding": "UTF-8",
                      "skipLines": 0,
                      "isFirstLineHeader": true,
                      "columns": [
                        {"label": "date", "field": "date"},
                        {"label": "close", "field": "quote"}
                      ]
                    }
                    """;

    private static final String PRICES_CSV = "date,close\n2026-08-24,100.00\n2026-08-25,101.50\n";

    private static JsonObject extractPrices(Client client, String csv)
    {
        return ImportsHandler.extract(client, "/tmp/test.portfolio", "quotes.csv",
                        csv.getBytes(StandardCharsets.UTF_8), null, PRICE_MAPPING, false);
    }

    private static JsonObject priceApplyBody(JsonObject preview, String securityUuid)
    {
        var body = new JsonObject();
        body.add("items", indices(preview));
        if (securityUuid != null)
            body.addProperty("security", securityUuid);
        return body;
    }

    @Test
    public void testASecurityPriceImportMergesIntoTheNamedInstrument()
    {
        // Before this, the investment-vehicle-price target was broken end to end
        // over REST: its extractor returns a nameless, currency-less placeholder
        // security that exists only to carry the prices, and applying it through
        // InsertAction added that placeholder to the file as an instrument. The
        // desktop wizard special-cases the target and merges into an instrument
        // the user picks; there was nowhere to name one here.
        var client = fixture();
        var instrument = new SecurityBuilder().addTo(client);
        var securitiesBefore = client.getSecurities().size();

        var preview = extractPrices(client, PRICES_CSV);
        var session = ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString());

        var result = ImportsHandler.apply(client, session, priceApplyBody(preview, instrument.getUUID()));

        assertThat(result.entity().getAsJsonObject().get("pricesAdded").getAsInt(), is(2));
        assertThat(client.getSecurities().size(), is(securitiesBefore));

        var dates = instrument.getPrices().stream().map(p -> p.getDate().toString()).toList();
        assertThat(dates, hasItem("2026-08-24"));
        assertThat(dates, hasItem("2026-08-25"));
    }

    @Test
    public void testASecurityPriceImportWithoutAnInstrumentIsRefused()
    {
        var client = fixture();
        new SecurityBuilder().addTo(client);

        var preview = extractPrices(client, PRICES_CSV);
        var session = ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString());

        try
        {
            ImportsHandler.apply(client, session, priceApplyBody(preview, null));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            assertThat(e.getErrors().get(0).field(), is("security"));
            assertThat(e.getErrors().get(0).code(), is("required"));
        }

        // and, since nothing is applied, the placeholder is not in the file either
        assertThat(client.getSecurities().size(), is(1));
    }

    @Test
    public void testAnUnknownInstrumentOnAPriceImportIsRefused()
    {
        var client = fixture();
        var preview = extractPrices(client, PRICES_CSV);
        var session = ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString());

        try
        {
            ImportsHandler.apply(client, session, priceApplyBody(preview, "no-such-uuid"));
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getErrors().get(0).field(), is("security"));
            assertThat(e.getErrors().get(0).code(), is("unknown-reference"));
        }
    }

    @Test
    public void testNamingAnInstrumentOnAnOrdinaryImportIsRefused()
    {
        // every other extraction's items name their own instrument, so a single
        // override would be meaningless rather than merely ignored
        var client = fixture();
        var instrument = new SecurityBuilder().addTo(client);

        var preview = extract(client, "flex.xml", FLEX_STATEMENT);
        var session = ImportSessions.get("/tmp/test.portfolio", preview.get("id").getAsString());

        var body = priceApplyBody(preview, instrument.getUUID());
        var accounts = new JsonObject();
        accounts.addProperty("USD", account(client).getUUID());
        body.add("accounts", accounts);

        try
        {
            ImportsHandler.apply(client, session, body);
            Assert.fail("expected a validation error");
        }
        catch (ApiException e)
        {
            assertThat(e.getErrors().get(0).field(), is("security"));
            assertThat(e.getErrors().get(0).code(), is("not-applicable"));
        }
    }

    @Test
    public void testReapplyingAnOverlappingPriceRangeChangesNothing()
    {
        var client = fixture();
        var instrument = new SecurityBuilder().addTo(client);

        var first = extractPrices(client, PRICES_CSV);
        ImportsHandler.apply(client, ImportSessions.get("/tmp/test.portfolio", first.get("id").getAsString()),
                        priceApplyBody(first, instrument.getUUID()));
        var afterFirst = instrument.getPrices().size();

        var second = extractPrices(client, PRICES_CSV);
        var result = ImportsHandler.apply(client,
                        ImportSessions.get("/tmp/test.portfolio", second.get("id").getAsString()),
                        priceApplyBody(second, instrument.getUUID()));

        assertThat(result.entity().getAsJsonObject().get("pricesAdded").getAsInt(), is(0));
        assertThat(instrument.getPrices().size(), is(afterFirst));
    }

    @Test
    public void testAnUnsavedInlineMappingIsNotListedAfterwards()
    {
        var client = fixture();

        var mapping = """
                        {
                          "label": "Throwaway Mapping",
                          "target": "portfolio",
                          "delimiter": ";",
                          "encoding": "UTF-8",
                          "skipLines": 0,
                          "isFirstLineHeader": true,
                          "columns": [
                            {"label": "Stück/Nom.", "field": "shares"},
                            {"label": "Bezeichnung", "field": "name"},
                            {"label": "WKN", "field": "wkn"},
                            {"label": "Typ"},
                            {"label": "Währung", "field": "currency"},
                            {"label": "Akt. Kurs", "field": "quote"}
                          ]
                        }
                        """;

        ImportsHandler.extract(client, "/tmp/test.portfolio", "depot.csv",
                        SECURITIES_CSV.getBytes(StandardCharsets.UTF_8), null, mapping, false);

        var labels = new ArrayList<String>();
        for (var element : ImportsHandler.configs())
            labels.add(element.getAsJsonObject().get("label").getAsString());
        assertThat(labels, org.hamcrest.Matchers.not(hasItem("Throwaway Mapping")));
    }
}
