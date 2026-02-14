package de.hibiscus.tr.cli;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.hibiscus.tr.api.TradeRepublicApi;
import de.hibiscus.tr.auth.LoginManager;
import de.hibiscus.tr.export.HibiscusExporter;
import de.hibiscus.tr.model.TradeRepublicError;
import de.hibiscus.tr.model.TransactionEvent;
import de.hibiscus.tr.timeline.TimelineProcessor;
import de.hibiscus.tr.util.VersionInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command line interface for Trade Republic to Hibiscus export
 */
@Command(
    name = "tr-hibiscus",
    mixinStandardHelpOptions = true,
    versionProvider = HibiscusExportCli.VersionProvider.class,
    description = "Export Trade Republic transaction data to Hibiscus banking software format"
)
public class HibiscusExportCli implements Callable<Integer> {
    
    private static final Logger logger = LoggerFactory.getLogger(HibiscusExportCli.class);
    
    @Parameters(index = "0", description = "Output directory for exported files")
    private Path outputPath;
    
    @Option(names = {"-n", "--phone-no"}, description = "TradeRepublic phone number (international format)", required = true)
    private String phoneNo;
    
    @Option(names = {"-p", "--pin"}, description = "TradeRepublic pin", required = true)
    private String pin;
    
    
    @Option(names = {"--last-days"}, description = "Number of last days to include (use 0 for all days)", defaultValue = "0")
    private int lastDays;
    
    @Option(names = {"--include-pending"}, description = "Include pending transactions")
    private boolean includePending = false;
    
    @Option(names = {"--save-details"}, description = "Save each transaction as JSON file")
    private boolean saveDetails = false;
    
    @Option(names = {"-v", "--verbose"}, description = "Enable verbose logging")
    private boolean verbose = false;
    
    @Option(names = {"--debug"}, description = "Enable debug logging")
    private boolean debug = false;
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new HibiscusExportCli()).execute(args);
        System.exit(exitCode);
    }
    
    @Override
    public Integer call() throws Exception {
        setupLogging();
        
        logger.info("Starting Trade Republic to Hibiscus export");
        logger.info("Output path: {}", outputPath);
        logger.info("Include pending: {}", includePending);
        logger.info("Last days: {}", lastDays);
        
        try {
            // Calculate timestamp for filtering
            long sinceTimestamp = calculateSinceTimestamp();
            
            // Login to Trade Republic
            LoginManager loginManager = new LoginManager();
            TradeRepublicApi api = loginManager.login(phoneNo, pin);
            
            try {
                // Process timeline and get transactions
                TimelineProcessor processor = new TimelineProcessor(api, sinceTimestamp, includePending);
                List<TransactionEvent> events = processor.processTimeline();
                
                logger.info("Processing completed: {}", processor.getStatistics());
                
                // Export to Hibiscus format
                HibiscusExporter exporter = new HibiscusExporter(outputPath, includePending, saveDetails, debug);
                exporter.exportTransactions(events);
                
                logger.info("Export completed successfully");
                System.out.println("Export completed successfully");
                
                return 0;
                
            } finally {
                api.close();
            }
            
        } catch (TradeRepublicError e) {
            logger.error("Trade Republic error: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            System.err.println("Unexpected error: " + e.getMessage());
            return 1;
        }
    }
    
    /**
     * Calculate timestamp for filtering transactions
     */
    private long calculateSinceTimestamp() {
        if (lastDays <= 0) {
            logger.info("Last days <= 0, including all transactions");
            return 0; // Include all transactions
        }
        
        LocalDateTime since = LocalDateTime.now().minusDays(lastDays);
        long timestamp = since.atZone(ZoneId.systemDefault()).toEpochSecond();
        logger.info("Filtering transactions since {} ({} days ago, timestamp: {})", 
                   since, lastDays, timestamp);
        return timestamp;
    }
    
    /**
     * Setup logging based on command line options
     */
    private void setupLogging() {
        // Note: In a real implementation, you would configure the logging framework
        // (Logback) programmatically here based on the verbose/debug flags
        
        if (debug) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            System.setProperty("org.slf4j.simpleLogger.log.de.hibiscus.tr", "debug");
        } else if (verbose) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
            System.setProperty("org.slf4j.simpleLogger.log.de.hibiscus.tr", "info");
        } else {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
            System.setProperty("org.slf4j.simpleLogger.log.de.hibiscus.tr", "info");
        }
    }

    /**
     * Version provider for picocli to dynamically load version from properties
     */
    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] { VersionInfo.getVersion() };
        }
    }
}