package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles interest payout transactions with balance and rate details
 */
public final class InterestHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        return event.getEventType() == null && "Zinsen".equals(event.getTitle());
    }

    /**
     * Extract interest details and build comment with balance and tax information
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        double betrag = event.getAmount().getValue();
        String zweck = event.getTitle() + " " + event.getSubtitle();
        String comment = buildComment(event);
        return new TransactionData(null, null, zweck, "Zinsen", betrag, comment);
    }

    /**
     * Build comment with average balance, accumulated interest, and tax details
     */
    private String buildComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        String status = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Zinsen", "detail", "text"));
        if (status != null) comment.append("Zinsen: ").append(status).append("\n");

        String durchschnittssaldo = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Durchschnittssaldo", "detail", "text"));
        if (durchschnittssaldo != null) comment.append("Durchschnittssaldo: ").append(durchschnittssaldo).append("\n");

        String angesammelt = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Angesammelt", "detail", "text"));
        if (angesammelt != null) comment.append("Angesammelt: ").append(angesammelt).append("\n");

        String tax = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Steuern", "detail", "text"));
        if (tax != null) comment.append("Steuern: ").append(tax).append("\n");

        String payout = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Gesamt", "detail", "text"));
        if (payout != null) comment.append("Gesamt: ").append(payout).append("\n");

        return comment.toString();
    }
}
