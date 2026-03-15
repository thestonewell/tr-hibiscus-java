package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles incoming deposit transactions from a junior account with sender details
 */
public final class JuniorP2PTransferHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        return "JUNIOR_P2P_TRANSFER".equals(event.getEventType())
                && "Überweisen".equals(art);
    }

    /**
     * Extract sender name, and note text
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        String art = "Überweisung";
        double betrag = event.getAmount().getValue();
        String empfaengerName = JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Absender", "detail", "text"));
        String zweck = JsonDetailExtractor.getNoteText(event);
        return new TransactionData("", empfaengerName, zweck, art, betrag, null);
    }
}
