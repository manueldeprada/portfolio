package name.abuchen.portfolio.rest.internal;

import java.text.MessageFormat;
import java.util.List;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.rest.Messages;

/**
 * Records the transactions the REST API books, as a human-readable entry in the
 * application log - the same contract {@link InstrumentChangeLog} keeps for
 * instrument edits, and for the same reason: a desktop user must be able to see
 * in the Error Log view what the API did to their file, especially now that
 * what it does is add money movements rather than rename things.
 */
public final class TransactionChangeLog
{
    private TransactionChangeLog()
    {
    }

    public static void recordCreation(String fileLabel, String type, String accountName, String amount)
    {
        PortfolioLog.info(creationSummary(fileLabel, type, accountName), List.of(amount));
    }

    /**
     * A deletion takes both legs of a cross entry with it, so the entry names the
     * type and owner it removed: after the fact there is nothing left to look up.
     */
    public static void recordDeletion(String fileLabel, String type, String ownerName, String uuid)
    {
        PortfolioLog.info(deletionSummary(fileLabel, type, ownerName), List.of(uuid));
    }

    /**
     * An import books many transactions at once, so the entry counts them rather
     * than listing them: the point of the log line is that the API changed the
     * file, and by how much.
     */
    public static void recordImport(String fileLabel, int imported, int instrumentsCreated)
    {
        PortfolioLog.info(MessageFormat.format(Messages.MsgApiImportApplied, imported, instrumentsCreated, fileLabel),
                        List.of());
    }

    /* package */ static String creationSummary(String fileLabel, String type, String accountName)
    {
        return MessageFormat.format(Messages.MsgApiTransactionCreated, type, accountName, fileLabel);
    }

    /* package */ static String deletionSummary(String fileLabel, String type, String ownerName)
    {
        return MessageFormat.format(Messages.MsgApiTransactionDeleted, type, ownerName, fileLabel);
    }
}
