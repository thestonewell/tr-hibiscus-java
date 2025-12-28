package de.hibiscus.tr.export;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.jdom2.Element;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.hibiscus.tr.model.TransactionEvent;

class HibiscusExporterTest {

    @TempDir
    Path tempDir;

    private HibiscusExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new HibiscusExporter(tempDir, false, false, false);
    }

    @Test
    void testCreateExporter() {
        assertNotNull(exporter);
    }

    @Test
    void testExportEmptyTransactions() throws Exception {
        List<TransactionEvent> events = Arrays.asList();

        // Should not throw exception
        assertDoesNotThrow(() -> exporter.exportTransactions(events));
    }

    @Test
    void testExportTransactionsWithoutAmount() throws Exception {
        TransactionEvent event = new TransactionEvent();
        event.setId("test-id");
        event.setTitle("Test Transaction");
        event.setTimestamp(Instant.now().toString());
        event.setEventType("TEST");
        // No amount set

        List<TransactionEvent> events = Arrays.asList(event);

        // Should not throw exception and should not create XML (no valid transactions)
        assertDoesNotThrow(() -> exporter.exportTransactions(events));
    }

    @Test
    void testExportTransactionWithAmount() throws Exception {
        TransactionEvent event = new TransactionEvent();
        event.setId("test-id-with-amount");
        event.setTitle("Test Transaction with Amount");
        event.setTimestamp("2024-01-01T12:00:00Z");
        event.setEventType("CREDIT");

        TransactionEvent.Amount amount = new TransactionEvent.Amount();
        amount.setValue(100.50);
        amount.setCurrency("EUR");
        event.setAmount(amount);

        List<TransactionEvent> events = Arrays.asList(event);

        // Should not throw exception
        assertDoesNotThrow(() -> exporter.exportTransactions(events));

        // Check if XML file was created (note: without proper details, transaction might be filtered out)
        // This is more of a smoke test to ensure no exceptions are thrown
    }

    private TransactionEvent createTransactionEventFromFile(String filename) throws Exception {
        Path filePath = Path.of(filename);
        String jsonContent = java.nio.file.Files.readString(filePath, Charset.forName("UTF-8"));
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(jsonContent);
        return objectMapper.treeToValue(jsonNode, TransactionEvent.class);
    }

    /**
     * Test transaction export for a received deposit.
     *
     * In addition to defaults check for: * sender Name * sender IBAN * purpose
     * (Verwendungszweck)
     */
    @Test
    void testExportDeposit() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/deposit.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is 500
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("500.0", betragElement.getText());

        // Check empfaenger_konto is DE12 3456 7890 1234 5678 90
        Element empfaengerKontoElement = xmlElement.getChild("empfaenger_konto");
        assertNotNull(empfaengerKontoElement);
        assertEquals("DE12 3456 7890 1234 5678 90", empfaengerKontoElement.getText());

        // Check empfaenger_name is Max Mustermann
        Element empfaengerNameElement = xmlElement.getChild("empfaenger_name");
        assertNotNull(empfaengerNameElement);
        assertEquals("Max Mustermann", empfaengerNameElement.getText());

        // Check zweck is Umbuchung
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("Umbuchung", zweckElement.getText());

        // Check art is Überweisung
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Überweisung", artElement.getText());
    }

    /**
     * Test transaction export for a sent withdrawal.
     *
     * In addition to defaults check for: * sender Name * sender IBAN * purpose
     * (Verwendungszweck)
     */
    @Test
    void testExportWithdrawal() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/withdrawal.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is -500
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("-500.0", betragElement.getText());

        // Check empfaenger_konto is DE12 3456 7890 1234 5678 90
        Element empfaengerKontoElement = xmlElement.getChild("empfaenger_konto");
        assertNotNull(empfaengerKontoElement);
        assertEquals("DE12 3456 7890 1234 5678 90", empfaengerKontoElement.getText());

        // Check empfaenger_name is Max Mustermann
        Element empfaengerNameElement = xmlElement.getChild("empfaenger_name");
        assertNotNull(empfaengerNameElement);
        assertEquals("Max Mustermann", empfaengerNameElement.getText());

        // Check zweck is Umbuchung
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("Umbuchung", zweckElement.getText());

        // Check art is Überweisung
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Überweisung", artElement.getText());
    }

    /**
     * Test transaction export for a card payment.
     *
     */
    @Test
    void testExportCardPayment() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/card-payment.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is -123.45
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("-123.45", betragElement.getText());

        // Check empfaenger_name is Shell
        Element empfaengerNameElement = xmlElement.getChild("empfaenger_name");
        assertNotNull(empfaengerNameElement);
        assertEquals("Shell", empfaengerNameElement.getText());

        // Check zweck is Shell
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("Shell", zweckElement.getText());

        // Check art is Kartenzahlung
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Kartenzahlung", artElement.getText());
    }

    /**
     * Test transaction export for a interest payment.
     *
     */
    @Test
    void testExportInterest() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/interest.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is 25.74
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("25.74", betragElement.getText());

        // Check zweck is Zinsen 2 % p.a.
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("Zinsen 2 % p.a.", zweckElement.getText());

        // Check art is Zinsen
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Zinsen", artElement.getText());
    }

    /**
     * Test transaction export for a savings plan.
     *
     */
    @Test
    void testExportSavingsPlan() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/savings-plan.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is -50
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("-50.0", betragElement.getText());

        // Check zweck
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("Snowflake (A) Sparplan", zweckElement.getText());

        // Check art is Sparplan
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Sparplan", artElement.getText());
    }

    /**
     * Test transaction export for a Saveback.
     *
     */
    @Test
    void testExportSaveback() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/saveback.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is zero, since saveback is neutral to the balance
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("0.0", betragElement.getText());

        // Check zweck is Allianz but with the amount of 15 EUR
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("Allianz Saveback -15,00 €", zweckElement.getText());

        // Check art is Saveback
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Saveback", artElement.getText());
    }

    /**
     * Test transaction export for a Round Up.
     *
     */
    @Test
    void testExportRoundUp() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/round-up.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is -16.96
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("-16.96", betragElement.getText());

        // Check zweck is Allianz
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("Allianz Round up", zweckElement.getText());

        // Check art is Round up
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Round up", artElement.getText());
    }

    /**
     * Test transaction export for a Dividend
     *
     */
    @Test
    void testExportDividend() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/dividend.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is 3.47
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("3.47", betragElement.getText());

        // Check zweck is Realty Income Bardividende
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("Realty Income Bardividende", zweckElement.getText());

        // Check art is Bardividende
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Bardividende", artElement.getText());
    }

    /**
     * Test transaction export for a buy order
     *
     */
    @Test
    void testExportOrderBuy() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/order-buy.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is -3792.82
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("-3792.82", betragElement.getText());

        // Check zweck is NVIDIA Kauforder
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("NVIDIA Kauforder", zweckElement.getText());

        // Check art is Kauforder
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Kauforder", artElement.getText());
    }

    /**
     * Test transaction export for a sell order
     *
     */
    @Test
    void testExportOrderSell() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/order-sell.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is 3597.36
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("3597.36", betragElement.getText());

        // Check zweck is NVIDIA Verkaufsorder
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("NVIDIA Verkaufsorder", zweckElement.getText());

        // Check art is Verkaufsorder
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Verkaufsorder", artElement.getText());
    }

    /**
     * Test transaction export for a tax adjustment
     *
     */
    @Test
    void testExportTaxAdjustment() throws Exception {
        TransactionEvent event = createTransactionEventFromFile("src/test/data/tax-adjustment.json");

        Element xmlElement = exporter.createTransactionElement(event, 0);

        assertNotNull(xmlElement);

        // Check betrag is 1.65
        Element betragElement = xmlElement.getChild("betrag");
        assertNotNull(betragElement);
        assertEquals("1.65", betragElement.getText());

        // Check zweck is Steuerkorrektur
        Element zweckElement = xmlElement.getChild("zweck");
        assertNotNull(zweckElement);
        assertEquals("Steuerkorrektur", zweckElement.getText());

        // Check art is Steuerkorrektur
        Element artElement = xmlElement.getChild("art");
        assertNotNull(artElement);
        assertEquals("Steuerkorrektur", artElement.getText());
    }

}
