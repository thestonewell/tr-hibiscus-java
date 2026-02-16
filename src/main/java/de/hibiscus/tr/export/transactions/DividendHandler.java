package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles dividend payment transactions with share and tax details
 */
public final class DividendHandler implements TransactionHandler {

    @Override
    public boolean canHandle(TransactionEvent event) {
        return event.getEventType() == null && "Bardividende".equals(event.getSubtitle());
    }

    /**
     * Extract dividend details and build comment with share and tax information
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        double betrag = event.getAmount().getValue();
        String zweck = event.getTitle() + " Bardividende";
        String comment = buildComment(event);
        return new TransactionData(null, null, zweck, "Bardividende", betrag, comment);
    }

    /**
     * Build comment with stock, ISIN, shares, dividend per share, and tax details
     */
    private String buildComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        String stock = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Wertpapier", "detail", "text"));
        if (stock != null) comment.append("Wertpapier: ").append(stock).append("\n");

        String isin = JsonDetailExtractor.getISIN(event);
        if (isin != null) comment.append("ISIN: ").append(isin).append("\n");

        JsonNode businessSection = JsonDetailExtractor.findSection(event, "Geschäft");
        if (businessSection != null && businessSection.has("data") && businessSection.get("data").isArray()) {
            JsonNode businessData = businessSection.get("data");
            String shares = JsonDetailExtractor.extractFromDataArray(businessData, "Aktien");
            if (shares != null) comment.append("Aktien: ").append(shares).append("\n");

            String dividendPerShare = JsonDetailExtractor.extractFromDataArray(businessData, "Dividende pro Aktie");
            if (dividendPerShare != null) comment.append("Dividende pro Aktie: ").append(dividendPerShare).append("\n");

            String tax = JsonDetailExtractor.extractFromDataArray(businessData, "Steuer");
            if (tax != null) comment.append("Steuer: ").append(tax).append("\n");

            String total = JsonDetailExtractor.extractFromDataArray(businessData, "Gesamt");
            if (total != null) comment.append("Gesamt: ").append(total).append("\n");
        }

        return comment.toString();
    }
}
