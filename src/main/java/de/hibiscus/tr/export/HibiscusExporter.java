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

        // Account information and recipient name based on event characteristics
        String empfaengerKonto = null; // Gegenkonto IBAN (optional)
        String empfaengerName = null; // Gegenkonto Name (optional)
        String zweck = null; // Verwendungszweck 1 (optional)
        double betrag = event.getAmount().getValue();
        String art = getTransactionType(event);
        String comment = null; // comment (optional)

        if (event.getEventType() == null && "Überweisung".equals(art) && "Fertig".equals(event.getSubtitle())) {
            // Deposit case
            empfaengerKonto = getDetailValue(event, Arrays.asList("Absender", "data", "IBAN", "detail", "text"));
            empfaengerName = getDetailValue(event, Arrays.asList("Absender", "data", "Absender", "detail", "text"));
            zweck = getNoteText(event);
        } else if (event.getEventType() == null && "Überweisung".equals(art) && "Gesendet".equals(event.getSubtitle())) {
            // withdrawal case
            empfaengerKonto = getDetailValue(event, Arrays.asList("Empfänger", "data", "IBAN", "detail", "text"));
            empfaengerName = getDetailValue(event, Arrays.asList("Empfänger", "data", "Empfänger", "detail", "text"));
            zweck = getNoteText(event);
        } else if (event.getEventType() == null && "Kartenzahlung".equals(art)) {
            // card payment case
            empfaengerName = getDetailValue(event, Arrays.asList("Übersicht", "data", "Händler", "detail", "text"));
            zweck = event.getTitle();
            comment = buildCardPaymentComment(event);
        } else if (event.getEventType() == null && "Zinsen".equals(event.getTitle())) {
            // interest case
            zweck = event.getTitle() + " " + event.getSubtitle();
            art = "Zinsen";
            comment = buildInterestComment(event);
        } else if (event.getEventType() == null && "Sparplan".equals(art)) {
            // savings plan case
            zweck = event.getTitle() + " Sparplan";
            // TODO: add order details: asset, number of shares and share price
            // TODO: add fees
            // TODO: add Gegenkonto if not from Cash
            comment = buildSavingsPlanComment(event);
        } else if (event.getEventType() == null && "Saveback".equals(art)) {
            // Saveback case
            // For saveback execution: set amount to 0 and append saveback info to purpose
            zweck = event.getTitle() + String.format(" Saveback %.2f €", betrag);
            betrag = 0.0;
            // comment with asset, number of shares and share price, and fees
            comment = buildSavebackComment(event);
        } else if (event.getEventType() == null && "Round up".equals(art)) {
            // Round up case
            zweck = event.getTitle() + " Round up";
            // comment with asset, number of shares and share price, and fees
            comment = buildRoundUpComment(event);
        } else if (event.getEventType() == null && "Bardividende".equals(event.getSubtitle())) {
            // Bardividende case
            zweck = event.getTitle() + " Bardividende";
            art = "Bardividende";
            // TODO: add divident details: asset, number of shares and dividend per share
            // TODO: add tax
            comment = buildDividendComment(event);
        } else if (event.getEventType() == null && "Kauforder".equals(event.getSubtitle())) {
            // Kauforder case
            zweck = event.getTitle() + " Kauforder";
            art = "Kauforder";
            // TODO: add portfolio (brokerage, crypto, private equity, ...)
            // comment with order details and fees
            comment = buildOrderComment(event);
        } else if (event.getEventType() == null && "Verkaufsorder".equals(event.getSubtitle())) {
            // Verkaufsorder case
            zweck = event.getTitle() + " Verkaufsorder";
            art = "Verkaufsorder";
            // TODO: add portfolio (brokerage, crypto, private equity, ...)
            // comment with order details and fees
            comment = buildOrderComment(event);
            // TODO: add performance and win/loss amount
        } else if (event.getEventType() == null && "Steuerkorrektur".equals(event.getTitle())) {
            // Steuerkorrektur case
            zweck = event.getTitle();
            art = "Steuerkorrektur";
        } else {
            // Default case for other event types
            empfaengerKonto = getDetailValue(event, Arrays.asList("Absender", "data", "IBAN", "detail", "text"));
            if (empfaengerKonto == null) {
                empfaengerKonto = getDetailValue(event, Arrays.asList("Empfänger", "data", "IBAN", "detail", "text"));
            }

            empfaengerName = getDetailValue(event, Arrays.asList("Absender", "data", "Name", "detail", "text"));
            if (empfaengerName == null) {
                empfaengerName = getDetailValue(event, Arrays.asList("Übersicht", "data", "Händler", "detail", "text"));
            }
            if (empfaengerName == null) {
                empfaengerName = getDetailValue(event, Arrays.asList("Empfänger", "data", "Name", "detail", "text"));
            }
            zweck = event.getTitle() + (event.getSubtitle() != null ? " " + event.getSubtitle() : "");
        }

        object.addContent(createElement("empfaenger_konto", "java.lang.String", empfaengerKonto != null ? empfaengerKonto : ""));
        object.addContent(createElement("empfaenger_name", "java.lang.String", empfaengerName != null ? empfaengerName : ""));
        object.addContent(createElement("zweck", "java.lang.String", zweck != null ? zweck : ""));
        // Transaction type
        object.addContent(createElement("art", "java.lang.String", art != null ? art : ""));
        // Amount
        object.addContent(createElement("betrag", "java.lang.Double", String.valueOf(betrag)));
        // Add comment with additional details
        if (null != comment) {
            object.addContent(createElement("kommentar", "java.lang.String", comment));
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
        String status = getDetailValue(event, Arrays.asList("Übersicht", "data", "Status", "detail", "functionalStyle"));
        return status != null ? status : "UNKNOWN";
    }

    /**
     * Extract transaction type from details sections with title "Übersicht".
     * Use the first element only.
     */
    private String getTransactionType(TransactionEvent event) {
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

    /**
     * Extract note text from transaction details
     */
    private String getNoteText(TransactionEvent event) {
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
     * Extract value from nested details structure
     */
    private String getDetailValue(TransactionEvent event, List<String> path) {
        if (event.getDetails() == null) {
            return null;
        }

        JsonNode current = event.getDetails();

        // Navigate through sections if they exist
        if (current.has("sections")) {
            current = current.get("sections");
        }

        return navigateJsonPath(current, path);
    }

    /**
     * Navigate JSON path recursively
     */
    private String navigateJsonPath(JsonNode node, List<String> path) {
        if (node == null || path.isEmpty()) {
            return null;
        }

        String currentKey = path.get(0);
        List<String> remainingPath = path.subList(1, path.size());

        if (node.isArray()) {
            // Search through array for matching title
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
     * Build detailed comment for dividend/corporate action transactions
     */
    private String buildDividendComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        try {
            // Extract stock information
            String stock = getDetailValue(event, Arrays.asList("Übersicht", "data", "Wertpapier", "detail", "text"));
            if (stock != null) {
                comment.append("Wertpapier: ").append(stock).append("\n");
            }

            // Extract ISIN from header action payload
            String isin = getISINFromHeaderAction(event);
            if (isin != null) {
                comment.append("ISIN: ").append(isin).append("\n");
            }

            // Extract business/transaction details
            JsonNode businessSection = findInSections(event, "Geschäft");
            if (businessSection != null && businessSection.has("data") && businessSection.get("data").isArray()) {
                JsonNode businessData = businessSection.get("data");

                // Extract shares count
                String shares = extractBusinessDetail(businessData, "Aktien");
                if (shares != null) {
                    comment.append("Aktien: ").append(shares).append("\n");
                }

                // Extract dividend per share
                String dividendPerShare = extractBusinessDetail(businessData, "Dividende pro Aktie");
                if (dividendPerShare != null) {
                    comment.append("Dividende pro Aktie: ").append(dividendPerShare).append("\n");
                }

                // Extract tax (replaces withholding tax)
                String tax = extractBusinessDetail(businessData, "Steuer");
                if (tax != null) {
                    comment.append("Steuer: ").append(tax).append("\n");
                }

                // Extract total amount
                String total = extractBusinessDetail(businessData, "Gesamt");
                if (total != null) {
                    comment.append("Gesamt: ").append(total).append("\n");
                }
            }

            // Extract document details if available
            JsonNode documentsSection = findInSections(event, "Dokumente");
            if (documentsSection != null && documentsSection.has("data") && documentsSection.get("data").isArray()) {
                JsonNode documentsData = documentsSection.get("data");

                String documentDate = extractBusinessDetail(documentsData, "Dokumente");
                if (documentDate != null) {
                    comment.append("Dokumentdatum: ").append(documentDate).append("\n");
                }
            }

        } catch (Exception e) {
            logger.warn("Error building dividend comment for event {}: {}", event.getId(), e.getMessage());
        }

        return comment.toString();
    }

    /**
     * Build detailed comment for savings plan execution transactions
     */
    private String buildSavingsPlanComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        try {
            // Extract savings plan status
            String sparplanStatus = getDetailValue(event, Arrays.asList("Übersicht", "data", "Sparplan", "detail", "text"));
            if (sparplanStatus != null) {
                comment.append("Sparplan: ").append(sparplanStatus).append("\n");
            }

            // Extract payment method
            String zahlung = getDetailValue(event, Arrays.asList("Übersicht", "data", "Zahlung", "detail", "text"));
            if (zahlung != null) {
                comment.append("Zahlung: ").append(zahlung).append("\n");
            }

            // Extract asset information
            String asset = getDetailValue(event, Arrays.asList("Übersicht", "data", "Asset", "detail", "text"));
            if (asset != null) {
                comment.append("Asset: ").append(asset).append("\n");
            }

            // Extract ISIN from header action payload
            String isin = getISINFromHeaderAction(event);
            if (isin != null) {
                comment.append("ISIN: ").append(isin).append("\n");
            }

            // Extract transaction details from nested structure
            JsonNode transactionDetail = getTransactionDetailFromOverview(event);
            if (transactionDetail != null) {
                String shares = extractNestedTransactionDetail(transactionDetail, "Aktien");
                if (shares != null) {
                    comment.append("Aktien: ").append(shares).append("\n");
                }

                String sharePrice = extractNestedTransactionDetail(transactionDetail, "Aktienkurs");
                if (sharePrice != null) {
                    comment.append("Aktienkurs: ").append(sharePrice).append("\n");
                }

                String sum = extractNestedTransactionDetail(transactionDetail, "Summe");
                if (sum != null) {
                    comment.append("Transaktionssumme: ").append(sum).append("\n");
                }
            }

            // Extract fee information
            String gebuehr = getDetailValue(event, Arrays.asList("Übersicht", "data", "Gebühr", "detail", "text"));
            if (gebuehr != null) {
                comment.append("Gebühr: ").append(gebuehr).append("\n");
            }

            // Extract total sum
            String gesamtsumme = getDetailValue(event, Arrays.asList("Übersicht", "data", "Summe", "detail", "text"));
            if (gesamtsumme != null) {
                comment.append("Summe: ").append(gesamtsumme).append("\n");
            }

            // Extract savings plan frequency information
            JsonNode sparplanSection = findInSections(event, "Sparplan");
            if (sparplanSection != null && sparplanSection.has("data") && sparplanSection.get("data").isArray()) {
                JsonNode sparplanData = sparplanSection.get("data");
                for (JsonNode item : sparplanData) {
                    if (item.has("detail") && item.get("detail").has("subtitle")) {
                        String frequency = item.get("detail").get("subtitle").asText();
                        if (frequency != null && !frequency.isEmpty()) {
                            comment.append("Häufigkeit: ").append(frequency).append("\n");
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.warn("Error building savings plan comment for event {}: {}", event.getId(), e.getMessage());
        }

        return comment.toString();
    }

    /**
     * Build detailed comment for order transactions
     */
    private String buildOrderComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        try {
            // Extract asset information
            String asset = getDetailValue(event, Arrays.asList("Übersicht", "data", "Asset", "detail", "text"));
            if (asset != null) {
                comment.append("Asset: ").append(asset).append("\n");
            }

            // Extract ISIN from header action payload
            String isin = getISINFromHeaderAction(event);
            if (isin != null) {
                comment.append("ISIN: ").append(isin).append("\n");
            }

            // Extract transaction details from nested structure
            JsonNode transactionDetail = getTransactionDetailFromOverview(event);
            if (transactionDetail != null) {
                String shares = extractNestedTransactionDetail(transactionDetail, "Aktien");
                if (shares != null) {
                    comment.append("Aktien: ").append(shares).append("\n");
                }

                String sharePrice = extractNestedTransactionDetail(transactionDetail, "Aktienkurs");
                if (sharePrice != null) {
                    comment.append("Aktienkurs: ").append(sharePrice).append("\n");
                }

                String sum = extractNestedTransactionDetail(transactionDetail, "Summe");
                if (sum != null) {
                    comment.append("Transaktionssumme: ").append(sum).append("\n");
                }
            }

            // Extract fee information
            String gebuehr = getDetailValue(event, Arrays.asList("Übersicht", "data", "Gebühr", "detail", "text"));
            if (gebuehr != null) {
                comment.append("Gebühr: ").append(gebuehr).append("\n");
            }

            // Extract total sum
            String gesamtsumme = getDetailValue(event, Arrays.asList("Übersicht", "data", "Summe", "detail", "text"));
            if (gesamtsumme != null) {
                comment.append("Summe: ").append(gesamtsumme).append("\n");
            }

        } catch (Exception e) {
            logger.warn("Error building buy order comment for event {}: {}", event.getId(), e.getMessage());
        }

        return comment.toString();
    }

    /**
     * Build detailed comment for round up transactions
     */
    private String buildRoundUpComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        try {
            // Extract asset information
            String asset = getDetailValue(event, Arrays.asList("Übersicht", "data", "Asset", "detail", "text"));
            if (asset != null) {
                comment.append("Asset: ").append(asset).append("\n");
            }

            // Extract ISIN from header action payload
            String isin = getISINFromHeaderAction(event);
            if (isin != null) {
                comment.append("ISIN: ").append(isin).append("\n");
            }

            // Extract transaction information
            String transaction = getDetailValue(event, Arrays.asList("Übersicht", "data", "Transaktion", "detail", "text"));
            if (transaction != null) {
                comment.append("Aktien: ").append(transaction).append("\n");
            }

            // Extract fee information
            String gebuehr = getDetailValue(event, Arrays.asList("Übersicht", "data", "Gebühr", "detail", "text"));
            if (gebuehr != null) {
                comment.append("Gebühr: ").append(gebuehr).append("\n");
            }

            // Extract total sum
            String gesamtsumme = getDetailValue(event, Arrays.asList("Übersicht", "data", "Gesamt", "detail", "text"));
            if (gesamtsumme != null) {
                comment.append("Summe: ").append(gesamtsumme).append("\n");
            }

        } catch (Exception e) {
            logger.warn("Error building buy order comment for event {}: {}", event.getId(), e.getMessage());
        }

        return comment.toString();
    }

    /**
     * Build detailed comment for saveback execution transactions
     */
    private String buildSavebackComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        try {
            // Extract saveback status
            String savebackStatus = getDetailValue(event, Arrays.asList("Übersicht", "data", "Saveback", "detail", "text"));
            if (savebackStatus != null) {
                comment.append("Saveback: ").append(savebackStatus).append("\n");
            }

            // Extract asset information
            String asset = getDetailValue(event, Arrays.asList("Übersicht", "data", "Asset", "detail", "text"));
            if (asset != null) {
                comment.append("Asset: ").append(asset).append("\n");
            }

            // Extract ISIN from header action payload
            String isin = getISINFromHeaderAction(event);
            if (isin != null) {
                comment.append("ISIN: ").append(isin).append("\n");
            }

            // Extract transaction information with shares and price
            JsonNode transactionNode = getOverviewDataByTitle(event, "Transaktion");
            if (transactionNode != null && transactionNode.has("detail")) {
                JsonNode detail = transactionNode.get("detail");
                if (detail.has("displayValue")) {
                    JsonNode displayValue = detail.get("displayValue");
                    if (displayValue.has("prefix") && displayValue.has("text")) {
                        String shares = displayValue.get("prefix").asText().replace(" x ", "").trim();
                        String price = displayValue.get("text").asText();
                        comment.append("Aktien: ").append(shares).append("\n");
                        comment.append("Aktienkurs: ").append(price).append("\n");
                    }
                }
            }

            // Extract fee information
            String gebuehr = getDetailValue(event, Arrays.asList("Übersicht", "data", "Gebühr", "detail", "text"));
            if (gebuehr != null) {
                comment.append("Gebühr: ").append(gebuehr).append("\n");
            }

            // Extract total amount
            String gesamt = getDetailValue(event, Arrays.asList("Übersicht", "data", "Gesamt", "detail", "text"));
            if (gesamt != null) {
                comment.append("Gesamt: ").append(gesamt).append("\n");
            }

            // Extract document information if available
            JsonNode documentsSection = findInSections(event, "Dokumente");
            if (documentsSection != null && documentsSection.has("data") && documentsSection.get("data").isArray()) {
                JsonNode documentsData = documentsSection.get("data");

                String executionDoc = extractBusinessDetail(documentsData, "Abrechnung Ausführung");
                if (executionDoc != null) {
                    comment.append("Abrechnung verfügbar\n");
                }

                String costsDoc = extractBusinessDetail(documentsData, "Kosteninformation");
                if (costsDoc != null) {
                    comment.append("Kosteninformation verfügbar\n");
                }
            }

        } catch (Exception e) {
            logger.warn("Error building saveback comment for event {}: {}", event.getId(), e.getMessage());
        }

        return comment.toString();
    }

    /**
     * Build detailed comment for interest payout transactions
     */
    private String buildInterestComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        try {
            // Extract status
            String status = getDetailValue(event, Arrays.asList("Übersicht", "data", "Zinsen", "detail", "text"));
            if (status != null) {
                comment.append("Zinsen: ").append(status).append("\n");
            }

            // Extract average balance
            String durchschnittssaldo = getDetailValue(event, Arrays.asList("Übersicht", "data", "Durchschnittssaldo", "detail", "text"));
            if (durchschnittssaldo != null) {
                comment.append("Durchschnittssaldo: ").append(durchschnittssaldo).append("\n");
            }

            // Extract interest earned this month
            String angesammelt = getDetailValue(event, Arrays.asList("Übersicht", "data", "Angesammelt", "detail", "text"));
            if (angesammelt != null) {
                comment.append("Angesammelt: ").append(angesammelt).append("\n");
            }

            // Extract tax on interest
            String tax = getDetailValue(event, Arrays.asList("Übersicht", "data", "Steuern", "detail", "text"));
            if (tax != null) {
                comment.append("Steuern: ").append(tax).append("\n");
            }

            // Extract payout after tax
            String payout = getDetailValue(event, Arrays.asList("Übersicht", "data", "Gesamt", "detail", "text"));
            if (payout != null) {
                comment.append("Gesamt: ").append(payout).append("\n");
            }

            // Extract document information if available
            JsonNode documentsSection = findInSections(event, "Dokument");
            if (documentsSection != null && documentsSection.has("data") && documentsSection.get("data").isArray()) {
                JsonNode documentsData = documentsSection.get("data");

                String abrechnungDoc = extractBusinessDetail(documentsData, "Abrechnung");
                if (abrechnungDoc != null) {
                    comment.append("Abrechnung verfügbar\n");
                }
            }

        } catch (Exception e) {
            logger.warn("Error building interest payout comment for event {}: {}", event.getId(), e.getMessage());
        }

        return comment.toString();
    }

    /**
     * Build detailed comment for card payment transactions with exchange rate information.
     * Only generates a comment if the transaction involves foreign currency exchange.
     *
     * @param event The transaction event containing card payment details
     * @return Comment string with exchange rate details, or empty string if no exchange rate data
     */
    private String buildCardPaymentComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        try {
            // Check for foreign currency amount (distinguishes foreign from domestic)
            String betrag = getDetailValue(event, Arrays.asList("Übersicht", "data", "Betrag", "detail", "text"));

            // Only generate comment if Betrag exists (indicates foreign currency transaction)
            if (betrag != null) {
                comment.append("Betrag: ").append(betrag).append("\n");

                // Extract exchange rate using getDetailValue
                String wechselkurs = getDetailValue(event, Arrays.asList("Übersicht", "data", "Wechselkurs", "detail", "text"));
                if (wechselkurs != null) {
                    comment.append("Wechselkurs: ").append(wechselkurs).append("\n");
                }

                // Extract total EUR amount using getDetailValue
                String gesamt = getDetailValue(event, Arrays.asList("Übersicht", "data", "Gesamt", "detail", "text"));
                if (gesamt != null) {
                    comment.append("Gesamt: ").append(gesamt).append("\n");
                }
            }
        } catch (Exception e) {
            logger.warn("Error building card payment comment for event {}: {}", event.getId(), e.getMessage());
        }

        return comment.toString();
    }

    /**
     * Build detailed comment for legacy migrated transactions
     */
    private String buildLegacyMigratedComment(TransactionEvent event) {
        StringBuilder comment = new StringBuilder();

        try {
            // Extract status
            String status = getDetailValue(event, Arrays.asList("Übersicht", "data", "Status", "detail", "text"));
            if (status != null) {
                comment.append("Status: ").append(status).append("\n");
            }

            // Extract order type
            String orderart = getDetailValue(event, Arrays.asList("Übersicht", "data", "Orderart", "detail", "text"));
            if (orderart != null) {
                comment.append("Orderart: ").append(orderart).append("\n");
            }

            // Extract asset
            String asset = getDetailValue(event, Arrays.asList("Übersicht", "data", "Asset", "detail", "text"));
            if (asset != null) {
                comment.append("Asset: ").append(asset).append("\n");
            }

            // Extract ISIN from header action payload
            String isin = getISINFromHeaderAction(event);
            if (isin != null) {
                comment.append("ISIN: ").append(isin).append("\n");
            }

            // Extract transaction details from "Transaktion" section
            JsonNode transactionSection = findInSections(event, "Transaktion");
            if (transactionSection != null && transactionSection.has("data") && transactionSection.get("data").isArray()) {
                JsonNode transactionData = transactionSection.get("data");

                String anteile = extractBusinessDetail(transactionData, "Anteile");
                if (anteile != null) {
                    comment.append("Anteile: ").append(anteile).append("\n");
                }

                String aktienkurs = extractBusinessDetail(transactionData, "Aktienkurs");
                if (aktienkurs != null) {
                    comment.append("Aktienkurs: ").append(aktienkurs).append("\n");
                }

                String gebuehr = extractBusinessDetail(transactionData, "Gebühr");
                if (gebuehr != null) {
                    comment.append("Gebühr: ").append(gebuehr).append("\n");
                }

                String gesamt = extractBusinessDetail(transactionData, "Gesamt");
                if (gesamt != null) {
                    comment.append("Gesamt: ").append(gesamt).append("\n");
                }
            }

            // Extract document information if available
            JsonNode documentsSection = findInSections(event, "Dokumente");
            if (documentsSection != null && documentsSection.has("data") && documentsSection.get("data").isArray()) {
                JsonNode documentsData = documentsSection.get("data");

                // Count different document types
                int abrechnungen = 0;
                int basisInfoBlaetter = 0;
                int kostenInfos = 0;

                for (JsonNode docItem : documentsData) {
                    if (docItem.has("title")) {
                        String title = docItem.get("title").asText();
                        if (title.startsWith("Abrechnung")) {
                            abrechnungen++;
                        } else if (title.startsWith("Basisinformationsblatt")) {
                            basisInfoBlaetter++;
                        } else if (title.startsWith("Kosteninformation")) {
                            kostenInfos++;
                        }
                    }
                }

                if (abrechnungen > 0) {
                    comment.append("Abrechnungen: ").append(abrechnungen).append("\n");
                }
                if (basisInfoBlaetter > 0) {
                    comment.append("Basisinformationsblätter: ").append(basisInfoBlaetter).append("\n");
                }
                if (kostenInfos > 0) {
                    comment.append("Kosteninformationen: ").append(kostenInfos).append("\n");
                }
            }

        } catch (Exception e) {
            logger.warn("Error building legacy migrated comment for event {}: {}", event.getId(), e.getMessage());
        }

        return comment.toString();
    }

    /**
     * Get overview data item by title
     */
    private JsonNode getOverviewDataByTitle(TransactionEvent event, String title) {
        try {
            JsonNode overview = findInSections(event, "Übersicht");
            if (overview != null && overview.has("data") && overview.get("data").isArray()) {
                JsonNode data = overview.get("data");
                for (JsonNode item : data) {
                    if (item.has("title") && title.equals(item.get("title").asText())) {
                        return item;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error getting overview data for title '{}' in event {}: {}", title, event.getId(), e.getMessage());
        }
        return null;
    }

    /**
     * Get transaction detail payload from overview section
     */
    private JsonNode getTransactionDetailFromOverview(TransactionEvent event) {
        try {
            JsonNode overview = findInSections(event, "Übersicht");
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
    private String extractNestedTransactionDetail(JsonNode sections, String title) {
        try {
            if (sections != null && sections.isArray()) {
                for (JsonNode section : sections) {
                    if (section.has("data") && section.get("data").isArray()) {
                        JsonNode data = section.get("data");
                        for (JsonNode item : data) {
                            if (item.has("title") && title.equals(item.get("title").asText())
                                    && item.has("detail") && item.get("detail").has("text")) {
                                return item.get("detail").get("text").asText();
                            }
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
     * Extract ISIN from header section action payload
     */
    private String getISINFromHeaderAction(TransactionEvent event) {
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
            logger.warn("Error extracting ISIN from header action for event {}: {}", event.getId(), e.getMessage());
        }
        return null;
    }

    /**
     * Extract business detail value from data array
     */
    private String extractBusinessDetail(JsonNode dataArray, String title) {
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
     * Find section by title in details
     */
    private JsonNode findInSections(TransactionEvent event, String sectionTitle) {
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
