package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;

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
