package de.hibiscus.tr.export;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import de.hibiscus.tr.export.transactions.JsonDetailExtractor;
import de.hibiscus.tr.export.transactions.TransactionHandler;
import de.hibiscus.tr.export.transactions.TransactionHandlerFactory;
import de.hibiscus.tr.model.TradeRepublicError;
import de.hibiscus.tr.model.TransactionEvent;

/**
 * Exports Trade Republic transactions to Hibiscus XML format
 */
public class HibiscusExporter {

    private static final Logger logger = LoggerFactory.getLogger(HibiscusExporter.class);

    private final Path outputPath;
    private final Path historyFile;
    private final boolean includePending;
    private final boolean saveTransactions;
    private final boolean debugMode;
    private final ObjectMapper objectMapper;

    private final Set<String> knownTransactions = new HashSet<>();

    // Transaction status constants
    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "EXECUTED", "CANCELED", "CREATED");

    // Filtering statistics
    private int totalEvents = 0;
    private int eventsWithoutAmount = 0;
    private int alreadyKnownEvents = 0;
    private int canceledEvents = 0;
    private int pendingEventsSkipped = 0;
    private int unknownStatusEvents = 0;
    private int cardVerificationEventsFiltered = 0;
    private int validEventsExported = 0;

    public HibiscusExporter(Path outputPath, boolean includePending, boolean saveTransactions, boolean debugMode) {
        this.outputPath = outputPath;
        this.historyFile = outputPath.resolve("tr2hibiscus.json");
        this.includePending = includePending;
        this.saveTransactions = saveTransactions;
        this.debugMode = debugMode;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        try {
            Files.createDirectories(outputPath);
            loadHistory();
        } catch (IOException e) {
            logger.warn("Could not create output directory or load history", e);
        }
    }

    /**
     * Export transactions to Hibiscus XML format
     */
    public void exportTransactions(List<TransactionEvent> events) throws TradeRepublicError {
        logger.info("Exporting {} transactions to Hibiscus XML format", events.size());

        List<TransactionEvent> validEvents = filterEvents(events);

        if (validEvents.isEmpty()) {
            logger.info("No new transactions to export");
            return;
        }

        // Sort transactions chronologically (oldest first)
        sortTransactionsChronologically(validEvents);

        try {
            Document xmlDoc = createHibiscusXml(validEvents);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH.mm.ss"));
            Path xmlFile = outputPath.resolve("hibiscus-" + timestamp + ".xml");

            XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());
            try (FileWriter writer = new FileWriter(xmlFile.toFile())) {
                outputter.output(xmlDoc, writer);
            }

            saveHistory();

            logger.info("Exported {} transactions to: {}", validEvents.size(), xmlFile);
            System.out.println("File " + xmlFile + " ready for import to hibiscus");

            // Print filtering statistics
            printFilteringStatistics();

            // Save debug files if debug mode is enabled
            if (debugMode) {
                saveDebugFiles(events);
            }

        } catch (Exception e) {
            throw new TradeRepublicError("XML export failed", e);
        }
    }

    /**
     * Sort transactions chronologically (oldest first)
     */
    private void sortTransactionsChronologically(List<TransactionEvent> events) {
        events.sort((event1, event2) -> {
            try {
                java.time.Instant time1 = event1.getTimestampAsInstant();
                java.time.Instant time2 = event2.getTimestampAsInstant();
                return time1.compareTo(time2);
            } catch (Exception e) {
                logger.warn("Could not compare timestamps for events {} and {}: {}",
                        event1.getId(), event2.getId(), e.getMessage());
                // Fallback: compare by ID if timestamp parsing fails
                return event1.getId().compareTo(event2.getId());
            }
        });

        logger.info("Sorted {} transactions chronologically", events.size());
    }

    /**
     * Filter events based on status and history
     */
    private List<TransactionEvent> filterEvents(List<TransactionEvent> events) {
        List<TransactionEvent> validEvents = new ArrayList<>();

        // Reset statistics
        totalEvents = events.size();
        eventsWithoutAmount = 0;
        alreadyKnownEvents = 0;
        canceledEvents = 0;
        pendingEventsSkipped = 0;
        unknownStatusEvents = 0;
        cardVerificationEventsFiltered = 0;
        validEventsExported = 0;

        for (TransactionEvent event : events) {
            // Filter out card verification events (no financial relevance for Hibiscus)
            if ("card_successful_verification".equals(event.getEventType())) {
                logger.debug("Filtering out card verification event: {}", event.getId());
                cardVerificationEventsFiltered++;
                continue;
            }

            if (!event.hasAmount()) {
                eventsWithoutAmount++;
                continue;
            }

            // Check if already processed
            if (knownTransactions.contains(event.getId())) {
                logger.debug("Already seen transaction: {}", event.getId());
                alreadyKnownEvents++;
                continue;
            }

            // Get status from details
            String status = getTransactionStatus(event);

            if (!VALID_STATUSES.contains(status)) {
                logger.error("Unknown status {} for transaction: {}", status, event.getId());
                unknownStatusEvents++;
                saveDebugFile(event);
                continue;
            }

            if ("CANCELED".equals(status)) {
                canceledEvents++;
                continue;
            }

            if ("PENDING".equals(status) && !includePending) {
                logger.debug("Skipping pending transaction: {}", event.getId());
                pendingEventsSkipped++;
                continue;
            }

            // Mark as known if not pending
            if (!"PENDING".equals(status)) {
                knownTransactions.add(event.getId());
            }

            validEvents.add(event);
            validEventsExported++;

            // Save individual transaction if requested
            if (saveTransactions) {
                saveTransactionFile(event);
            }
        }

        return validEvents;
    }

    /**
     * Create Hibiscus XML document
     */
    private Document createHibiscusXml(List<TransactionEvent> events) {
        Document doc = new Document();
        Element root = new Element("objects");
        doc.setRootElement(root);

        int objectId = 0;
        for (TransactionEvent event : events) {
            Element objectElement = createTransactionElement(event, objectId++);
            root.addContent(objectElement);
        }

        return doc;
    }

    /**
     * Create XML element for a single transaction
     */
    Element createTransactionElement(TransactionEvent event, int objectId) {
        Element object = new Element("object");
        object.setAttribute("type", "de.willuhn.jameica.hbci.server.UmsatzImpl");
        object.setAttribute("id", String.valueOf(objectId));

        // Date fields
        String dateStr = formatDateForHibiscus(event.getTimestamp());
        object.addContent(createElement("datum", "java.sql.Date", dateStr));
        object.addContent(createElement("valuta", "java.sql.Date", dateStr));

        // Extract transaction data using factory
        TransactionHandlerFactory factory = TransactionHandlerFactory.getInstance();
        TransactionHandler.TransactionData data = factory.extractTransactionData(event);

        // Add transaction fields
        object.addContent(createElement("empfaenger_konto", "java.lang.String", data.empfaengerKonto() != null ? data.empfaengerKonto() : ""));
        object.addContent(createElement("empfaenger_name", "java.lang.String", data.empfaengerName() != null ? data.empfaengerName() : ""));
        object.addContent(createElement("zweck", "java.lang.String", data.zweck() != null ? data.zweck() : ""));
        object.addContent(createElement("art", "java.lang.String", data.art() != null ? data.art() : ""));
        object.addContent(createElement("betrag", "java.lang.Double", String.valueOf(data.betrag())));
        
        if (data.comment() != null && !data.comment().isEmpty()) {
            object.addContent(createElement("kommentar", "java.lang.String", data.comment()));
        }

        // Empty fields required by Hibiscus
        object.addContent(createElement("primanota", "java.lang.String", ""));
        object.addContent(createElement("customerref", "java.lang.String", ""));
        object.addContent(createElement("checksum", "java.math.BigDecimal", ""));
        object.addContent(createElement("konto_id", "java.lang.Integer", ""));
        object.addContent(createElement("addkey", "java.lang.String", ""));
        object.addContent(createElement("txid", "java.lang.String", ""));
        object.addContent(createElement("saldo", "java.lang.Double", ""));
        object.addContent(createElement("gvcode", "java.lang.String", ""));
        object.addContent(createElement("empfaenger_blz", "java.lang.String", ""));

        // Mark as pending if needed
        String status = getTransactionStatus(event);
        if ("PENDING".equals(status)) {
            object.addContent(createElement("flags", "java.lang.Integer", "2"));
        }

        return object;
    }

    /**
     * Create XML element with type and content
     */
    private Element createElement(String name, String type, String content) {
        Element element = new Element(name);
        element.setAttribute("type", type);
        if (content != null && !content.isEmpty()) {
            element.setText(content);
        }
        return element;
    }

    /**
     * Format date for Hibiscus (dd.MM.yyyy HH:mm:ss)
     */
    private String formatDateForHibiscus(String timestamp) {
        if (timestamp != null && timestamp.length() >= 19) {
            // Convert from ISO format to Hibiscus format
            String date = timestamp.substring(8, 10) + "."
                    + timestamp.substring(5, 7) + "."
                    + timestamp.substring(0, 4) + " "
                    + timestamp.substring(11, 19);
            return date;
        }
        return "";
    }

    /**
     * Get transaction status from event or details
     */
    private String getTransactionStatus(TransactionEvent event) {
        // First try to get status directly from event
        if (event.getStatus() != null && !event.getStatus().isEmpty()) {
            return event.getStatus();
        }

        // Fallback: try to get from details structure
        String status = JsonDetailExtractor.getDetailValue(event, Arrays.asList("Übersicht", "data", "Status", "detail", "functionalStyle"));
        return status != null ? status : "UNKNOWN";
    }
















    /**
     * Load transaction history
     */
    private void loadHistory() {
        logger.info("Using history file: {}", historyFile);

        if (Files.exists(historyFile)) {
            try {
                JsonNode historyNode = objectMapper.readTree(historyFile.toFile());
                if (historyNode.has("known_transactions")) {
                    JsonNode knownArray = historyNode.get("known_transactions");
                    if (knownArray.isArray()) {
                        for (JsonNode id : knownArray) {
                            knownTransactions.add(id.asText());
                        }
                    }
                }
                logger.info("Loaded {} known transactions from history", knownTransactions.size());
            } catch (IOException e) {
                logger.warn("Could not load history file", e);
            }
        }
    }

    /**
     * Save transaction history
     */
    private void saveHistory() {
        try {
            Map<String, Object> history = Map.of("known_transactions", new ArrayList<>(knownTransactions));
            objectMapper.writeValue(historyFile.toFile(), history);
            logger.debug("Saved history with {} transactions", knownTransactions.size());
        } catch (IOException e) {
            logger.error("Could not save history file", e);
        }
    }

    /**
     * Save debug file for problematic transaction
     */
    private void saveDebugFile(TransactionEvent event) {
        try {
            String filename = "debug-" + event.getId().replace(":", ".") + ".json";
            Path debugFile = outputPath.resolve(filename);
            objectMapper.writeValue(debugFile.toFile(), event);
            logger.info("Saved debug file: {}", debugFile);
        } catch (IOException e) {
            logger.error("Could not save debug file for transaction: {}", event.getId(), e);
        }
    }

    /**
     * Save individual transaction file
     */
    private void saveTransactionFile(TransactionEvent event) {
        try {
            String filename = "_" + event.getId();
            Path transactionFile = outputPath.resolve(filename);
            objectMapper.writeValue(transactionFile.toFile(), event);
            logger.debug("Saved transaction file: {}", transactionFile);
        } catch (IOException e) {
            logger.error("Could not save transaction file: {}", event.getId(), e);
        }
    }

    /**
     * Save all transactions in original JSON format for debugging
     */
    private void saveDebugFiles(List<TransactionEvent> allEvents) {
        try {
            // Create debug directory
            Path debugDir = outputPath.resolve("debug");
            Files.createDirectories(debugDir);

            // Sort all events chronologically for debug output
            List<TransactionEvent> sortedEvents = new ArrayList<>(allEvents);
            sortedEvents.sort((event1, event2) -> {
                try {
                    java.time.Instant time1 = event1.getTimestampAsInstant();
                    java.time.Instant time2 = event2.getTimestampAsInstant();
                    return time1.compareTo(time2);
                } catch (Exception e) {
                    return event1.getId().compareTo(event2.getId());
                }
            });

            // Save all events as individual JSON files
            for (TransactionEvent event : sortedEvents) {
                try {
                    String safeId = event.getId().replaceAll("[^a-zA-Z0-9\\-_]", "_");
                    String filename = "transaction_" + safeId + ".json";
                    Path debugFile = debugDir.resolve(filename);

                    // Create a pretty-printed JSON
                    ObjectMapper prettyMapper = new ObjectMapper();
                    prettyMapper.registerModule(new JavaTimeModule());
                    prettyMapper.writerWithDefaultPrettyPrinter().writeValue(debugFile.toFile(), event);

                } catch (Exception e) {
                    logger.warn("Could not save debug file for transaction {}: {}", event.getId(), e.getMessage());
                }
            }

            // Save summary file with all events (sorted)
            Path summaryFile = debugDir.resolve("all_transactions_summary.json");
            Map<String, Object> summary = new HashMap<>();
            summary.put("totalEvents", sortedEvents.size());
            summary.put("exportTimestamp", LocalDateTime.now().toString());
            summary.put("transactions", sortedEvents);

            ObjectMapper prettyMapper = new ObjectMapper();
            prettyMapper.registerModule(new JavaTimeModule());
            prettyMapper.writerWithDefaultPrettyPrinter().writeValue(summaryFile.toFile(), summary);

            logger.info("Debug files saved to: {}", debugDir);
            System.out.println("\nDEBUG: All " + sortedEvents.size() + " transactions saved as JSON files in: " + debugDir);
            System.out.println("DEBUG: Summary file: " + summaryFile);
            System.out.println("DEBUG: Transactions sorted chronologically (oldest first)");

        } catch (IOException e) {
            logger.error("Could not save debug files", e);
        }
    }

    /**
     * Print detailed filtering statistics
     */
    private void printFilteringStatistics() {
        System.out.println("\n=== EXPORT STATISTICS ===");
        System.out.println("Total events found: " + totalEvents);
        System.out.println("Valid transactions exported: " + validEventsExported);

        System.out.println("\n--- Filtered out events ---");
        System.out.println("Events without amount (documents, notifications, etc.): " + eventsWithoutAmount);
        System.out.println("Card verification events (filtered out): " + cardVerificationEventsFiltered);
        System.out.println("Already known transactions (from previous exports): " + alreadyKnownEvents);
        System.out.println("Canceled transactions: " + canceledEvents);
        if (!includePending) {
            System.out.println("Pending transactions (use --include-pending to include): " + pendingEventsSkipped);
        }
        System.out.println("Unknown status transactions: " + unknownStatusEvents);

        int totalFiltered = eventsWithoutAmount + cardVerificationEventsFiltered + alreadyKnownEvents + canceledEvents + pendingEventsSkipped + unknownStatusEvents;
        System.out.println("\nTotal filtered out: " + totalFiltered);
        System.out.println("Export success rate: " + validEventsExported + "/" + totalEvents + " ("
                + String.format("%.1f", (validEventsExported * 100.0 / totalEvents)) + "%)");
        System.out.println("=========================");
    }
}
