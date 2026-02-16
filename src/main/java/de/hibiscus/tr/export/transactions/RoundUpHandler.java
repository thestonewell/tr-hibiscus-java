package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles round-up investment transactions with asset details
 */
public final class RoundUpHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        return event.getEventType() == null && "Round up".equals(art);
    }

    /**
     * Extract round-up details and build comment with asset and share information
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        double betrag = event.getAmount().getValue();
        String zweck = event.getTitle() + " Round up";
        String comment = buildComment(event);
        return new TransactionData(null, null, zweck, art, betrag, comment);
    }

    /**
     * Build comment with asset, ISIN, shares, and fees
     */
    private String buildComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        String asset = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Asset", "detail", "text"));
        if (asset != null) comment.append("Asset: ").append(asset).append("\n");

        String isin = JsonDetailExtractor.getISIN(event);
        if (isin != null) comment.append("ISIN: ").append(isin).append("\n");

        String transaction = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Transaktion", "detail", "text"));
        if (transaction != null) comment.append("Aktien: ").append(transaction).append("\n");

        String gebuehr = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Gebühr", "detail", "text"));
        if (gebuehr != null) comment.append("Gebühr: ").append(gebuehr).append("\n");

        String gesamtsumme = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Gesamt", "detail", "text"));
        if (gesamtsumme != null) comment.append("Summe: ").append(gesamtsumme).append("\n");

        return comment.toString();
    }
}
