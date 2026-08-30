package name.abuchen.portfolio.datatransfer.csv;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;

import name.abuchen.portfolio.datatransfer.csv.CSVImporter.AmountField;
import name.abuchen.portfolio.util.TextUtil;

/**
 * The decimal convention an amount column is guessed with. It used to be the
 * reader's own locale regardless of the data, which silently multiplies or
 * divides a value by a thousand whenever the file disagrees with the machine:
 * "100.00" read with the comma convention has its dot discarded as a grouping
 * separator and becomes 10000, and both readings parse, so nothing reports it.
 */
@SuppressWarnings("nls")
public class AmountFieldTest
{
    private static final String COMMA = "0.000,00";
    private static final String DOT = "0,000.00";

    private static String guess(String value)
    {
        return new AmountField("value", "Value").guessFormat(null, value).getCode();
    }

    /** What the value alone cannot settle still falls back to the locale. */
    private static String locale()
    {
        return TextUtil.DECIMAL_SEPARATOR == '.' ? DOT : COMMA;
    }

    @Test
    public void testTheDecimalSeparatorIsReadFromTheValue()
    {
        assertThat(guess("100.00"), is(DOT));
        assertThat(guess("100,00"), is(COMMA));
        assertThat(guess("1.2345"), is(DOT));
        assertThat(guess("1,5"), is(COMMA));
    }

    @Test
    public void testBothSeparatorsPresentMakeTheLastOneTheDecimalSeparator()
    {
        assertThat(guess("1.234,56"), is(COMMA));
        assertThat(guess("1,234.56"), is(DOT));
        assertThat(guess("1.234.567,89"), is(COMMA));
        assertThat(guess("1,234,567.89"), is(DOT));
    }

    @Test
    public void testASeparatorRepeatedCanOnlyGroup()
    {
        assertThat(guess("1.234.567"), is(COMMA));
        assertThat(guess("1,234,567"), is(DOT));
    }

    @Test
    public void testThreeDigitsAfterASeparatorAreOnlyAGroupWhereAGroupCouldStart()
    {
        // "1234.567" is not a group boundary any convention produces
        assertThat(guess("1234.567"), is(DOT));
        assertThat(guess("1234,567"), is(COMMA));
        assertThat(guess(".123"), is(DOT));

        // "1,234" is genuinely either 1234 or 1.234 - undecidable from the value
        assertThat(guess("1,234"), is(locale()));
        assertThat(guess("1.234"), is(locale()));
        assertThat(guess("123,456"), is(locale()));
    }

    @Test
    public void testSignsCurrencySymbolsAndApostrophesAreIgnored()
    {
        assertThat(guess("-50.00"), is(DOT));
        assertThat(guess("EUR 42,42"), is(COMMA));
        assertThat(guess("  1 234.56  "), is(DOT));
        assertThat(guess("1'234.56"), is(DOT));
        assertThat(guess("2.12e-6"), is(DOT));
    }

    @Test
    public void testAValueWithNoSeparatorAtAllFallsBackToTheLocale()
    {
        assertThat(guess(null), is(locale()));
        assertThat(guess("1234"), is(locale()));
        assertThat(guess(""), is(locale()));
    }
}
