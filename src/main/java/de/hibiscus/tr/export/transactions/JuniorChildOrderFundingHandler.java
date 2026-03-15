package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles outgoing withdrawal to a junior account to fund the an order in the
 * junior accounts.
 */
public final class JuniorChildOrderFundingHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        return "JUNIOR_CHILD_ORDER_FUNDING".equals(event.getEventType())
                && "Überweisen".equals(art);
    }

    /**
     * Extract recipient name, and note text
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        String art = "Überweisung";
        double betrag = event.getAmount().getValue();
        StringBuilder empfaengerName = new StringBuilder("Kinderdepot ");
        empfaengerName.append(JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Empfänger", "detail", "text")));

        StringBuilder zweck = new StringBuilder("Vermögenswert: ");
        zweck.append(JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Vermögenswert", "detail", "text")));
        return new TransactionData("", empfaengerName.toString(), zweck.toString(), art, betrag, null);
    }
}
