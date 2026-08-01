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

    /* package */ static String creationSummary(String fileLabel, String type, String accountName)
    {
        return MessageFormat.format(Messages.MsgApiTransactionCreated, type, accountName, fileLabel);
    }
}
