package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles card payment transactions with merchant and exchange rate details
 */
public final class CardPaymentHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        return (event.getEventType() == null || "CARD_TRANSACTION".equals(event.getEventType()))
                && "Kartenzahlung".equals(art);
    }

    /**
     * Extract merchant name and build comment with exchange rate details
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        double betrag = event.getAmount().getValue();
        String empfaengerName = JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Händler", "detail", "text"));
        String zweck = event.getTitle();
        String comment = buildComment(event);
        return new TransactionData(null, empfaengerName, zweck, art, betrag, comment);
    }

    /**
     * Build comment with amount, exchange rate, and total
     */
    private String buildComment(TransactionEvent event) {
        String betrag = JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Betrag", "detail", "text"));
        if (betrag == null)
            return "";

        StringBuilder comment = new StringBuilder();
        comment.append("Betrag: ").append(betrag).append("\n");

        String wechselkurs = JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Wechselkurs", "detail", "text"));
        if (wechselkurs != null) {
            comment.append("Wechselkurs: ").append(wechselkurs).append("\n");
        }

        String gesamt = JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Gesamt", "detail", "text"));
        if (gesamt != null) {
            comment.append("Gesamt: ").append(gesamt).append("\n");
        }

        return comment.toString();
    }
}
