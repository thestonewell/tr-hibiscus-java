package de.hibiscus.tr.export.transactions;

import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Handles buy and sell order transactions with asset and performance details
 */
public final class OrderHandler implements TransactionHandler {
    private final String orderType;

    public OrderHandler(String orderType) {
        this.orderType = orderType;
    }

    @Override
    public boolean canHandle(TransactionEvent event) {
        return event.getEventType() == null && orderType.equals(event.getSubtitle());
    }

    /**
     * Extract order details and build comment with asset, shares, fees, and performance
     */
    @Override
    public TransactionData extractData(TransactionEvent event) {
        double betrag = event.getAmount().getValue();
        String zweck = event.getTitle() + " " + orderType;
        String comment = buildComment(event);
        return new TransactionData(null, null, zweck, orderType, betrag, comment);
    }

    /**
     * Build comment with portfolio, asset, ISIN, shares, fees, and performance data
     */
    private String buildComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        String portfolio = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Portfolio", "detail", "text"));
        if (portfolio != null) comment.append("Portfolio: ").append(portfolio).append("\n");

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

        JsonNode performanceSection = JsonDetailExtractor.findSection(event, "Performance");
        if (performanceSection != null && performanceSection.has("data") && performanceSection.get("data").isArray()) {
            JsonNode performanceData = performanceSection.get("data");
            String rendite = JsonDetailExtractor.extractFromDataArray(performanceData, "Rendite");
            if (rendite != null) comment.append("Rendite: ").append(rendite).append("\n");

            String gewinn = JsonDetailExtractor.extractFromDataArray(performanceData, "Gewinn");
            if (gewinn != null) comment.append("Gewinn: ").append(gewinn).append("\n");
        }

        return comment.toString();
    }
}
