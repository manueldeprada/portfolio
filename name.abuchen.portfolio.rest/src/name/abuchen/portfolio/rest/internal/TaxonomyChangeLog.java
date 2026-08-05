package name.abuchen.portfolio.rest.internal;

import java.text.MessageFormat;
import java.util.List;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.rest.Messages;

/**
 * Records the mutations the REST API applies to a taxonomy in the application
 * log, so the desktop user can see in the Error Log view what the API
 * restructured. Same shape and the same generic "REST API" attribution as
 * {@link InstrumentChangeLog}.
 */
public final class TaxonomyChangeLog
{
    private TaxonomyChangeLog()
    {
    }

    /** A change to an existing taxonomy; a null detail means nothing changed. */
    public static void record(String fileLabel, String taxonomyName, String detail)
    {
        if (detail == null)
            return;

        PortfolioLog.info(MessageFormat.format(Messages.MsgApiTaxonomyChanged, taxonomyName, fileLabel),
                        List.of(detail));
    }

    public static void recordCreation(String fileLabel, String taxonomyName)
    {
        PortfolioLog.info(MessageFormat.format(Messages.MsgApiTaxonomyCreated, taxonomyName, fileLabel), List.of());
    }

    public static void recordDeletion(String fileLabel, String taxonomyName)
    {
        PortfolioLog.info(MessageFormat.format(Messages.MsgApiTaxonomyDeleted, taxonomyName, fileLabel), List.of());
    }
}
