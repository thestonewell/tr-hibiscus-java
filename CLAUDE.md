# CLAUDE.md

Java app that exports Trade Republic transactions to Hibiscus XML format via WebSocket API with parallel processing.

## Workflow
- Use Plan mode for complex tasks
- Get approval before implementing
- Break changes into reviewable chunks

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

- **api**: WebSocket client (`TradeRepublicApi`) - connection management, request/response correlation via CompletableFutures
- **auth**: Web-based login (`LoginManager`) - session tokens, device IDs, 2FA verification
- **cli**: Entry point (`HibiscusExportCli`) - picocli argument parsing, orchestrates login → timeline → export
- **export**: Handler-based XML generation
  - `HibiscusExporter`: Main orchestrator - history tracking (tr2hibiscus.json), status filtering, handler chain delegation
  - **transactions** subpackage: Handler implementations and utilities
    - `TransactionHandler`: Interface with `canHandle(event, art)` and `extractData(event, art, betrag)` → TransactionData
    - `TransactionHandlerFactory`: Singleton managing handler chain (sequential evaluation, first match wins)
    - `JsonDetailExtractor`: Path-based JSON navigation utility shared across handlers
    - Handler implementations: Deposit, Withdrawal, CardPayment, Order (buy/sell), Dividend, Interest, SavingsPlan, Saveback, RoundUp, TaxAdjustment, JuniorP2PTransfer, JuniorChildOrderFunding, Default
- **model**: `TransactionEvent`, `TradeRepublicError` with Jackson annotations
- **timeline**: Paginated fetch with parallel detail processing (`TimelineProcessor`)
- **util**: `VersionInfo` - loads version from filtered properties

### Key Patterns

1. **WebSocket Protocol**: Persistent connection with subscription management via CompletableFutures
2. **Async Processing**: Parallel streams for concurrent transaction detail fetching
3. **Handler Chain**: Sequential evaluation until first match, priority-ordered (specific before generic)
4. **Incremental Exports**: History tracking prevents duplicates
5. **Multi-Stage Filtering**: Amount check → status validation → card verification removal → duplicate detection
6. **XML Generation**: JDOM2 for Hibiscus-compatible output

### Handler Architecture

**Core Components**:
- `TransactionHandler` ([TransactionHandler.java](src/main/java/de/hibiscus/tr/export/transactions/TransactionHandler.java)): `canHandle()` + `extractData()` → TransactionData record
- `JsonDetailExtractor` ([JsonDetailExtractor.java](src/main/java/de/hibiscus/tr/export/transactions/JsonDetailExtractor.java)): Shared path-based JSON navigation utility
- `TransactionHandlerFactory` ([TransactionHandlerFactory.java](src/main/java/de/hibiscus/tr/export/transactions/TransactionHandlerFactory.java)): Singleton managing handler chain evaluation
- Handlers: Package-private final classes per transaction type with embedded comment building logic (CardPaymentHandler, OrderHandler, DividendHandler, etc.)

**Flow**: HibiscusExporter initializes factory → sequential handler evaluation → first match via `canHandle()` → extract via JsonDetailExtractor → build optional comment → return TransactionData → convert to XML

**Benefits**: Modular, testable, extensible - new types need only a new handler class

## Testing

JUnit Jupiter with `@TempDir` for file operations. Tests in `*Test.java`, data in `src/test/data/`.

## Data Flow

1. **Login**: Web auth → session token → WebSocket connection
2. **Timeline Fetch**: Subscribe → paginated events → parallel detail fetching
3. **Export**: Filter → sort → handler chain (`canHandle()` → `extractData()` via JsonDetailExtractor) → XML generation → history save

## Important Implementation Details

### Transaction Processing

- Transactions without amounts are automatically filtered out
- Status must be one of: PENDING, EXECUTED, CANCELED, CREATED
- Card verification transactions (eventType == "CARD_VERIFICATION") are filtered out
- Transactions are sorted chronologically (oldest first) before export
- The `details` field is a JsonNode containing nested transaction-specific data

### Data Extraction

**`JsonDetailExtractor`** - path-based JSON navigation from `details.sections`:
- `getDetailValue(event, path)`: Navigate via title matches (arrays) or properties (objects). Example: `["Übersicht", "data", "Händler", "detail", "text"]` extracts merchant name
- `findSection()`, `extractFromDataArray()`, `getISIN()`, `getNoteText()`, `getTransactionDetailFromOverview()`, `extractNestedTransactionDetail()`

### Transaction Types

**Handler Priority** (see `TransactionHandlerFactory.initializeHandlers()`):
Deposit → Withdrawal → CardPayment → Interest → SavingsPlan → Saveback → RoundUp → Dividend → Order (Buy/Sell) → TaxAdjustment → JuniorP2PTransfer → JuniorChildOrderFunding

**EventType matching**: Handlers accept both null eventType (older API) and explicit eventType strings (newer API):
- `DepositHandler`: `BANK_TRANSACTION_INCOMING`
- `WithdrawalHandler`: `BANK_TRANSACTION_OUTGOING`
- `CardPaymentHandler`: `CARD_TRANSACTION`
- `JuniorP2PTransferHandler`: `JUNIOR_P2P_TRANSFER` (incoming deposit from junior account)
- `JuniorChildOrderFundingHandler`: `JUNIOR_CHILD_ORDER_FUNDING` (outgoing transfer to fund junior order)

**Adding New Types**:
1. Create new final class implementing `TransactionHandler` in `transactions` package
2. Implement `canHandle()` for detection and `extractData()` for data extraction
3. Add optional private `buildComment()` method for transaction-specific comment generation
4. Register handler in `TransactionHandlerFactory.initializeHandlers()` at appropriate priority position

### Output Files

- `hibiscus-YYYY-MM-DDTHH.MM.SS.xml`: Main export file
- `tr2hibiscus.json`: History tracking file
- `_<transaction-id>`: Individual JSON files (when --save-details flag used)
- `debug/`: Debug files (when --debug flag used)

## Dependencies

picocli (CLI), OkHttp (HTTP), Jackson (JSON), JDOM2 (XML), SLF4J/Logback (logging), JUnit Jupiter (tests), Bouncy Castle (crypto)

## Build

maven-shade-plugin creates uber JAR. Main: `de.hibiscus.tr.cli.HibiscusExportCli`. Signature files filtered (*.SF, *.DSA, *.RSA).

**Version**: Set in `pom.xml` `<version>`, auto-propagated via Maven resource filtering to `version.properties` → `VersionInfo` → CLI `--version`
