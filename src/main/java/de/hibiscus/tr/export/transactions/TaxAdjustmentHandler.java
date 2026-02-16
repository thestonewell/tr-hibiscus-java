package de.hibiscus.tr.export.transactions;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles tax adjustment transactions
 */
public final class TaxAdjustmentHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        return event.getEventType() == null && "Steuerkorrektur".equals(event.getTitle());
    }

    /**
     * Extract tax adjustment with title and amount
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        double betrag = event.getAmount().getValue();
        return new TransactionData(null, null, event.getTitle(), "Steuerkorrektur", betrag, null);
    }
}
