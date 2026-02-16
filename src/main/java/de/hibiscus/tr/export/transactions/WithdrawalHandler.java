package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles outgoing withdrawal transactions with recipient details
 */
public final class WithdrawalHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        return event.getEventType() == null && "Überweisung".equals(art) && "Gesendet".equals(event.getSubtitle());
    }

    /**
     * Extract recipient IBAN, name, and note text
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        double betrag = event.getAmount().getValue();
        String empfaengerKonto = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Empfänger", "data", "IBAN", "detail", "text"));
        String empfaengerName = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Empfänger", "data", "Empfänger", "detail", "text"));
        String zweck = JsonDetailExtractor.getNoteText(event);
        return new TransactionData(empfaengerKonto, empfaengerName, zweck, art, betrag, null);
    }
}
