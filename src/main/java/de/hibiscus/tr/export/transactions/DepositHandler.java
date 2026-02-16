package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles incoming deposit transactions with sender details
 */
public final class DepositHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        return event.getEventType() == null && "Überweisung".equals(art) && "Fertig".equals(event.getSubtitle());
    }

    /**
     * Extract sender IBAN, name, and note text
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        double betrag = event.getAmount().getValue();
        String empfaengerKonto = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Absender", "data", "IBAN", "detail", "text"));
        String empfaengerName = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Absender", "data", "Absender", "detail", "text"));
        String zweck = JsonDetailExtractor.getNoteText(event);
        return new TransactionData(empfaengerKonto, empfaengerName, zweck, art, betrag, null);
    }
}
