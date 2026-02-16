package de.hibiscus.tr.export.transactions;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handler for transaction-specific data extraction
 */
public interface TransactionHandler {

    /**
     * Check if this handler can process the event
     */
    boolean canHandle(TransactionEvent event);

    /**
     * Extract transaction data
     */
    TransactionData extractData(TransactionEvent event);

    /**
     * Transaction data container
     */
    record TransactionData(String empfaengerKonto, String empfaengerName, String zweck,
                          String art, double betrag, String comment) {}
}
