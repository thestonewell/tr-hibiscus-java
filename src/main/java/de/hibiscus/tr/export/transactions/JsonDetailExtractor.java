package de.hibiscus.tr.export.transactions;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Utility for extracting values from nested JSON structures.
 * Consolidates all JSON navigation logic for Trade Republic transaction details.
 */
public final class JsonDetailExtractor {

    private static final Logger logger = LoggerFactory.getLogger(JsonDetailExtractor.class);

    private JsonDetailExtractor() {
        // Utility class - prevent instantiation
    }

    /**
     * Extract value from nested details structure using path
     */
    public static String getDetailValue(TransactionEvent event, List<String> path) {
        if (event.getDetails() == null) {
            return null;
        }

        JsonNode current = event.getDetails();
        if (current.has("sections")) {
            current = current.get("sections");
        }

        return navigateJsonPath(current, path);
    }

    /**
     * Navigate JSON path recursively
     */
    private static String navigateJsonPath(JsonNode node, List<String> path) {
        if (node == null || path.isEmpty()) {
            return null;
        }

        String currentKey = path.get(0);
        List<String> remainingPath = path.subList(1, path.size());

        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.has("title") && currentKey.equals(item.get("title").asText())) {
                    return navigateJsonPath(item, remainingPath);
                }
            }
        } else if (node.has(currentKey)) {
            if (remainingPath.isEmpty()) {
                JsonNode result = node.get(currentKey);
                return result.isTextual() ? result.asText() : null;
            } else {
                return navigateJsonPath(node.get(currentKey), remainingPath);
            }
        }

        return null;
    }

    /**
     * Find section by title in details
     */
    public static JsonNode findSection(TransactionEvent event, String sectionTitle) {
        if (event.getDetails() == null || !event.getDetails().has("sections")) {
            return null;
        }

        JsonNode sections = event.getDetails().get("sections");
        if (sections.isArray()) {
            for (JsonNode section : sections) {
                if (section.has("title") && sectionTitle.equals(section.get("title").asText())) {
                    return section;
                }
            }
        }

        return null;
    }

    /**
     * Extract detail value from data array by title
     */
    public static String extractFromDataArray(JsonNode dataArray, String title) {
        if (dataArray != null && dataArray.isArray()) {
            for (JsonNode item : dataArray) {
                if (item.has("title") && title.equals(item.get("title").asText())
                        && item.has("detail") && item.get("detail").has("text")) {
                    return item.get("detail").get("text").asText();
                }
            }
        }
        return null;
    }

    /**
     * Extract ISIN from header section action payload
     */
    public static String getISIN(TransactionEvent event) {
        try {
            if (event.getDetails() != null && event.getDetails().has("sections")) {
                JsonNode sections = event.getDetails().get("sections");
                if (sections.isArray()) {
                    for (JsonNode section : sections) {
                        if (section.has("type") && "header".equals(section.get("type").asText())
                                && section.has("action") && section.get("action").has("payload")) {
                            JsonNode payload = section.get("action").get("payload");
                            if (payload.isTextual()) {
                                return payload.asText();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting ISIN for event {}: {}", event.getId(), e.getMessage());
        }
        return null;
    }

    /**
     * Extract note text from transaction details
     */
    public static String getNoteText(TransactionEvent event) {
        if (event.getDetails() == null || !event.getDetails().has("sections")) {
            return null;
        }

        JsonNode sections = event.getDetails().get("sections");
        if (sections.isArray()) {
            for (JsonNode section : sections) {
                if (section.has("type") && "note".equals(section.get("type").asText())) {
                    JsonNode data = section.get("data");
                    if (data != null && data.has("text")) {
                        return data.get("text").asText();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Get transaction detail payload from overview section
     */
    public static JsonNode getTransactionDetailFromOverview(TransactionEvent event) {
        try {
            JsonNode overview = findSection(event, "Übersicht");
            if (overview != null && overview.has("data") && overview.get("data").isArray()) {
                for (JsonNode item : overview.get("data")) {
                    if (item.has("title") && "Transaktion".equals(item.get("title").asText())
                            && item.has("detail") && item.get("detail").has("action")
                            && item.get("detail").get("action").has("payload")
                            && item.get("detail").get("action").get("payload").has("sections")) {
                        return item.get("detail").get("action").get("payload").get("sections");
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting transaction detail for event {}: {}", event.getId(), e.getMessage());
        }
        return null;
    }

    /**
     * Extract detail from nested transaction payload
     */
    public static String extractNestedTransactionDetail(JsonNode sections, String title) {
        try {
            if (sections != null && sections.isArray()) {
                for (JsonNode section : sections) {
                    if (section.has("data") && section.get("data").isArray()) {
                        String result = extractFromDataArray(section.get("data"), title);
                        if (result != null) {
                            return result;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting nested transaction detail '{}': {}", title, e.getMessage());
        }
        return null;
    }

    /**
     * Extract savings plan frequency from Sparplan section
     */
    public static String extractFrequencyFromSparplan(TransactionEvent event) {
        try {
            JsonNode sparplanSection = findSection(event, "Sparplan");
            if (sparplanSection != null && sparplanSection.has("data") && sparplanSection.get("data").isArray()) {
                for (JsonNode item : sparplanSection.get("data")) {
                    if (item.has("detail") && item.get("detail").has("subtitle")) {
                        String frequency = item.get("detail").get("subtitle").asText();
                        if (frequency != null && !frequency.isEmpty()) {
                            return frequency;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting frequency from Sparplan for event {}: {}", event.getId(), e.getMessage());
        }
        return null;
    }

    /**
     * Extract transaction type from details sections with title "Übersicht".
     * Uses the first element's title as the transaction type.
     */
    public static String getTransactionType(TransactionEvent event) {
        if (event.getDetails() == null || !event.getDetails().has("sections")) {
            return null;
        }

        for (JsonNode section : event.getDetails().get("sections")) {
            if (section.has("title") && "Übersicht".equals(section.get("title").asText())) {
                JsonNode data = section.get("data");
                if (data != null && data.isArray() && data.size() > 0) {
                    JsonNode firstElement = data.get(0);
                    if (firstElement.has("title")) {
                        return firstElement.get("title").asText();
                    }
                }
            }
        }

        return null;
    }
}
