package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Default fallback handler for unhandled transaction types.
 * This handler always returns true for canHandle and provides basic extraction logic.
 */
public final class DefaultTransactionHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        // Acts as fallback - always returns true
        return true;
    }

    /**
     * Extract default transaction data by trying common JSON paths
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        double betrag = event.getAmount().getValue();

        String empfaengerKonto = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Absender", "data", "IBAN", "detail", "text"));
        if (empfaengerKonto == null) {
            empfaengerKonto = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Empfänger", "data", "IBAN", "detail", "text"));
        }

        String empfaengerName = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Absender", "data", "Name", "detail", "text"));
        if (empfaengerName == null) {
            empfaengerName = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Händler", "detail", "text"));
        }
        if (empfaengerName == null) {
            empfaengerName = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Empfänger", "data", "Name", "detail", "text"));
        }

        String zweck = event.getTitle() + (event.getSubtitle() != null ? " " + event.getSubtitle() : "");
        return new TransactionData(empfaengerKonto, empfaengerName, zweck, art, betrag, null);
    }
}
