package name.abuchen.portfolio.rest.spi;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;

/**
 * A portfolio file currently open in the application. Implemented by the UI
 * plugin, backed by ClientInput.
 */
public interface OpenFile
{
    /** absolute file path; unique per machine and the identity key */
    String getPath();

    String getLabel();

    Client getClient();

    /**
     * The host's exchange rate factory for this file. The factory registers a
     * listener on the client, so its lifecycle must be owned by whoever owns
     * the file - the REST plugin must never construct (and thereby leak) one
     * per request.
     */
    ExchangeRateProviderFactory getExchangeRateProviderFactory();

    /**
     * A counter that grows with every change to the model of this file. It is
     * what lets a response carry a validator without hashing the model: two
     * requests that see the same counter see the same data. Only equality is
     * meaningful - the increment is not a count of edits, and the value is not
     * comparable across files or across restarts of the application.
     */
    long getChangeCount();

    /** Whether the file has changes the user has not saved yet. */
    boolean isDirty();
}
