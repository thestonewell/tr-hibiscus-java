# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java application that exports transaction data from Trade Republic to Hibiscus banking software XML format. It uses WebSocket protocol to communicate with Trade Republic's API, processes timeline data with parallel detail fetching, and generates Hibiscus-compatible XML exports.

## Workflow
- Start complex tasks in Plan mode
- Get plan approval before implementation
- Break large changes into reviewable chunks

## Build and Run Commands

### Building

```bash
# Clean build with tests
mvn clean package

# Build without tests
mvn clean package -DskipTests

# Run tests only
mvn test

# Run specific test
mvn test -Dtest=HibiscusExporterTest

# Run specific test method
mvn test -Dtest=HibiscusExporterTest#testExportTransactionWithAmount
```

### Running

```bash
# Run the built JAR
java -jar target/tr-hibiscus-export-${project.version}.jar -n <phone> -p <pin> <output-dir>

# Run from Maven (development)
mvn exec:java -Dexec.mainClass="de.hibiscus.tr.cli.HibiscusExportCli" -Dexec.args="-n <phone> -p <pin> <output-dir>"

# Run with debug logging
java -jar target/tr-hibiscus-export-${project.version}.jar -n <phone> -p <pin> --debug <output-dir>
```

## Architecture

### Package Structure

- **api**: WebSocket client for Trade Republic API (`TradeRepublicApi`)
  - Handles WebSocket connection with request/response correlation
  - Manages subscription lifecycle with auto-incrementing IDs
  - Uses CompletableFuture for async operations

- **auth**: Authentication and login (`LoginManager`)
  - Implements web-based login flow (same as app.traderepublic.com)
  - Manages session tokens and device IDs
  - Handles 2FA code verification

- **cli**: Command-line interface (`HibiscusExportCli`)
  - Entry point with picocli for argument parsing
  - Orchestrates login → timeline processing → export flow

- **export**: Hibiscus XML generation (`HibiscusExporter`)
  - Converts TransactionEvent objects to Hibiscus XML format
  - Maintains history file (tr2hibiscus.json) to track processed transactions
  - Filters transactions by status (EXECUTED, PENDING, CANCELED, etc.)
  - Extracts detailed information from different transaction types

- **model**: Data classes
  - `TransactionEvent`: Main transaction model with nested Amount class
  - `TradeRepublicError`: Custom exception for API errors
  - Uses Jackson annotations for JSON serialization

- **timeline**: Timeline processing (`TimelineProcessor`)
  - Fetches paginated timeline data from Trade Republic
  - Parallel processing of transaction details for performance
  - Filters by timestamp and pending status

- **util**: Utility classes
  - `VersionInfo`: Loads application version from build-time filtered properties

### Key Architecture Patterns

1. **WebSocket Protocol**: API client maintains persistent WebSocket connection with subscription management and request correlation using CompletableFutures
2. **Async Processing**: Timeline processor uses parallel streams to fetch transaction details concurrently
3. **Incremental Exports**: History tracking prevents duplicate exports across runs
4. **Transaction Filtering**: Multi-stage filtering (amount check, status validation, card verification removal, duplicate detection)
5. **XML Generation**: Uses JDOM2 for building Hibiscus-compatible XML documents

## Testing Conventions

- Use JUnit Jupiter (`@Test`, `@BeforeEach`)
- Use `@TempDir` for temporary file operations
- Test file naming: `*Test.java` (e.g., `HibiscusExporterTest.java`)
- Test data location: `src/test/data/` with JSON samples
- Test structure: Create test objects, execute operation, verify with assertions

Example test setup:

```java
@TempDir
Path tempDir;

@BeforeEach
void setUp() {
    exporter = new HibiscusExporter(tempDir, false, false, false);
}
```

## Data Flow

1. **Login** (LoginManager → TradeRepublicApi): Authenticate via web login → obtain session token → connect WebSocket with cookies
2. **Timeline Fetch** (TimelineProcessor): Subscribe to timeline → receive paginated events → fetch details for each transaction in parallel
3. **Export** (HibiscusExporter): Filter valid transactions → sort chronologically → generate XML → save history

## Important Implementation Details

### Transaction Processing

- Transactions without amounts are automatically filtered out
- Status must be one of: PENDING, EXECUTED, CANCELED, CREATED
- Card verification transactions (status.action == "cardVerification") are filtered out
- Transactions are sorted chronologically (oldest first) before export
- The `details` field is a JsonNode containing nested transaction-specific data

### Data Extraction Helpers

The `HibiscusExporter` class provides helper methods for extracting data from the nested JSON transaction details structure:

#### `getDetailValue(TransactionEvent event, List<String> path)`

**Purpose**: Navigate through deeply nested JSON structures and extract string values using a simple path-based approach.

**How it works**:
1. Automatically starts navigation from `details.sections` if sections exist
2. Handles arrays by searching for items with matching `title` fields
3. Navigates through regular object properties using the provided path keys
4. Returns the final text value as a string, or `null` if not found

**Path structure**: Each element in the path list represents either:
- A `title` field value to search for in an array (e.g., "Übersicht", "Betrag")
- An object property name to navigate into (e.g., "data", "detail", "text")

**Example usage**:
```java
// Extract merchant name from card payment
String merchant = getDetailValue(event,
    Arrays.asList("Übersicht", "data", "Händler", "detail", "text"));

// Extract foreign currency amount
String betrag = getDetailValue(event,
    Arrays.asList("Übersicht", "data", "Betrag", "detail", "text"));

// Extract exchange rate
String wechselkurs = getDetailValue(event,
    Arrays.asList("Übersicht", "data", "Wechselkurs", "detail", "text"));
```

**JSON structure example**:
```json
{
  "details": {
    "sections": [
      {
        "title": "Übersicht",          // Found by searching for "Übersicht"
        "data": [                       // Navigate into "data" property
          {
            "title": "Betrag",          // Found by searching for "Betrag"
            "detail": {                 // Navigate into "detail" property
              "text": "12.132,00 ₹"    // Extract "text" property value
            }
          }
        ]
      }
    ]
  }
}
```

**When to use**: Prefer `getDetailValue` for extracting simple string values from the transaction details. It's the most concise approach and consistent with existing code patterns in the exporter.

**Alternative helpers**:
- `getOverviewDataByTitle(event, title)`: Returns the full JsonNode for a data item in the "Übersicht" section (use when you need to access multiple fields from the same item)
- `findInSections(event, sectionTitle)`: Returns the full JsonNode for a section (use when processing complex section structures)

### Supported Transaction Types

Card payments, buy/sell orders, dividends, savings plans, savebacks, round-ups, interest payouts, withdrawals, deposits, tax adjustments. Each type has specialized detail extraction logic in the exporter.

### Output Files

- `hibiscus-YYYY-MM-DDTHH.MM.SS.xml`: Main export file
- `tr2hibiscus.json`: History tracking file
- `_<transaction-id>`: Individual JSON files (when --save-details flag used)
- `debug/`: Debug files (when --debug flag used)

## Dependencies

Key libraries:

- **picocli**: CLI framework with annotation-based argument parsing
- **OkHttp**: HTTP client (managed via BOM)
- **Jackson**: JSON processing with JavaTimeModule for date handling
- **JDOM2**: XML document creation and manipulation
- **SLF4J/Logback**: Logging (configured via src/main/resources/logback.xml)
- **JUnit Jupiter**: Testing framework
- **Bouncy Castle**: Cryptographic operations

## Build Configuration

Uses maven-shade-plugin to create an uber JAR with all dependencies. Main class: `de.hibiscus.tr.cli.HibiscusExportCli`. The POM filters out signature files (_.SF, _.DSA, \*.RSA) to avoid conflicts in the shaded JAR.

### Version Management

Version is defined once in `pom.xml` (`<version>` tag) and automatically propagated throughout the application:

1. **Maven Resource Filtering**: During build, `${project.version}` in `src/main/resources/version.properties` is replaced with the actual version
2. **VersionInfo Utility**: Loads the filtered version at runtime from the properties file
3. **CLI Version Display**: `HibiscusExportCli` uses a `VersionProvider` to dynamically provide the version to picocli's `--version` flag

To update the version: Simply change the `<version>` tag in `pom.xml` and rebuild. The version will automatically update in the JAR filename and CLI output.
