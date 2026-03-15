package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles saveback transactions with asset and share details
 */
public final class SavebackHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        return (event.getEventType() == null || "SAVEBACK_AGGREGATE".equals(event.getEventType()))
                && "Saveback".equals(art);
    }

    /**
     * Extract saveback details and build comment with asset, shares, and price
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        String art = JsonDetailExtractor.getTransactionType(event);
        double betrag = event.getAmount().getValue();
        String zweck = event.getTitle() + String.format(" Saveback %.2f €", betrag);
        String comment = buildComment(event);
        return new TransactionData(null, null, zweck, art, 0.0, comment);
    }

    /**
     * Build comment with asset, ISIN, shares, price, and fees
     */
    private String buildComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        String savebackStatus = JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Saveback", "detail", "text"));
        if (savebackStatus != null)
            comment.append("Saveback: ").append(savebackStatus).append("\n");

        String asset = JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Asset", "detail", "text"));
        if (asset != null)
            comment.append("Asset: ").append(asset).append("\n");

        String isin = JsonDetailExtractor.getISIN(event);
        if (isin != null)
            comment.append("ISIN: ").append(isin).append("\n");

        JsonNode overview = JsonDetailExtractor.findSection(event, "Übersicht");
        if (overview != null && overview.has("data") && overview.get("data").isArray()) {
            for (JsonNode item : overview.get("data")) {
                if (item.has("title") && "Transaktion".equals(item.get("title").asText()) && item.has("detail")) {
                    JsonNode detail = item.get("detail");
                    if (detail.has("displayValue")) {
                        JsonNode displayValue = detail.get("displayValue");
                        if (displayValue.has("prefix") && displayValue.has("text")) {
                            String shares = displayValue.get("prefix").asText().replace(" x ", "").trim();
                            String price = displayValue.get("text").asText();
                            comment.append("Aktien: ").append(shares).append("\n");
                            comment.append("Aktienkurs: ").append(price).append("\n");
                        }
                    }
                    break;
                }
            }
        }

        String gebuehr = JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Gebühr", "detail", "text"));
        if (gebuehr != null)
            comment.append("Gebühr: ").append(gebuehr).append("\n");

        String gesamt = JsonDetailExtractor.getDetailValue(event,
                Arrays.asList("Übersicht", "data", "Gesamt", "detail", "text"));
        if (gesamt != null)
            comment.append("Gesamt: ").append(gesamt).append("\n");

        return comment.toString();
    }
}
