package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles savings plan execution transactions with payment and frequency details
 */
public final class SavingsPlanHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        return event.getEventType() == null && "Sparplan".equals(art);
    }

    /**
     * Extract savings plan details and build comment with payment, asset, and frequency
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        double betrag = event.getAmount().getValue();
        String zweck = event.getTitle() + " Sparplan";
        String comment = buildComment(event);
        return new TransactionData(null, null, zweck, art, betrag, comment);
    }

    /**
     * Build comment with payment method, asset, ISIN, shares, fees, and frequency
     */
    private String buildComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        String sparplanStatus = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Sparplan", "detail", "text"));
        if (sparplanStatus != null) comment.append("Sparplan: ").append(sparplanStatus).append("\n");

        String zahlung = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Zahlung", "detail", "text"));
        if (zahlung != null) comment.append("Zahlung: ").append(zahlung).append("\n");

        String asset = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Asset", "detail", "text"));
        if (asset != null) comment.append("Asset: ").append(asset).append("\n");

        String isin = JsonDetailExtractor.getISIN(event);
        if (isin != null) comment.append("ISIN: ").append(isin).append("\n");

        JsonNode transactionDetail = JsonDetailExtractor.getTransactionDetailFromOverview(event);
        if (transactionDetail != null) {
            String shares = JsonDetailExtractor.extractNestedTransactionDetail(transactionDetail, "Aktien");
            if (shares != null) comment.append("Aktien: ").append(shares).append("\n");

            String sharePrice = JsonDetailExtractor.extractNestedTransactionDetail(transactionDetail, "Aktienkurs");
            if (sharePrice != null) comment.append("Aktienkurs: ").append(sharePrice).append("\n");

            String sum = JsonDetailExtractor.extractNestedTransactionDetail(transactionDetail, "Summe");
            if (sum != null) comment.append("Transaktionssumme: ").append(sum).append("\n");
        }

        String gebuehr = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Gebühr", "detail", "text"));
        if (gebuehr != null) comment.append("Gebühr: ").append(gebuehr).append("\n");

        String gesamtsumme = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Summe", "detail", "text"));
        if (gesamtsumme != null) comment.append("Summe: ").append(gesamtsumme).append("\n");

        String frequency = JsonDetailExtractor.extractFrequencyFromSparplan(event);
        if (frequency != null) {
            comment.append("Häufigkeit: ").append(frequency).append("\n");
        }

        return comment.toString();
    }
}
