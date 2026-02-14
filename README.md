# Trade Republic to Hibiscus Exporter (Java)

This Java application exports transaction data from Trade Republic to Hibiscus banking software XML format. It is a Java port of the Python `hibiscusPrepare` scripts.

Es kann als Basis für ein Hibiscus-Plugin genutzt werden. Freiwillige vor.

## Features

- Export Trade Republic transactions to Hibiscus-compatible XML format
- Enhanced transaction details extraction for specialized event types:
  - **Card payments**: counter party, for international also exchange rate and foreign amount
  - **Buy order**: assest info, ISIN, shares, taxes, fees, totals
  - **Sell order**: assest info, ISIN, shares, taxes, fees, totals, performance, profit/loss amount
  - **Dividends**: assest info, ISIN, shares, dividend per share, taxes, totals
  - **Savings Plans**: payment method, asset details, ISIN, transaction amounts, fees, frequency
  - **Savebacks**: asset info, ISIN, shares purchased, fees
  - **Round Up**: asset info, ISIN, shares purchased, fees
  - **Interest Payouts**: Average balance, annual rate, gross/net amounts, tax deductions
  - **Withdrawals**: counter party name and IBAN, purpose
  - **Deposits**: counter party name and IBAN, purpose
  - **Tax adjustments**: tax amount
- Filter transactions by date range using `--last-days` option
- Include or exclude pending transactions with `--include-pending` flag
- Track processed transactions to avoid duplicates (incremental exports)
- Save individual transaction details as JSON files for debugging
- Secure web login authentication (same as app.traderepublic.com)
- Comprehensive filtering statistics and transaction status reporting
- Chronological sorting of transactions (oldest first)
- Parallel processing of transaction details for better performance
- Rolling log files with configurable log levels (verbose, debug)

## Open items

- re-enable **Legacy Transactions**: Order type, asset details, ISIN, shares, prices, fees
- export that documents are available
- actually download the pdfs (and ideally rename them from pbxyz.pdf to include details like asset information in the filename)

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Trade Republic account with valid credentials

## Building

```bash
# Clone or navigate to the project directory
cd tr-hibiscus-java

# Build the project
mvn clean package

# This creates a JAR file with all dependencies:
# target/tr-hibiscus-export-1.1.1.jar
```

## Usage

### Basic Export

```bash
java -jar target/tr-hibiscus-export-1.1.1.jar -n +49123456789 -p 1234 /path/to/output
```

### Command Line Options

```bash
java -jar target/tr-hibiscus-export-1.1.1.jar -n <phoneNo> -p <pin> [OPTIONS] OUTPUT_DIRECTORY

Required Parameters:
  -n, --phone-no=<phoneNo>     TradeRepublic phone number (international format)
  -p, --pin=<pin>              TradeRepublic pin

Options:
      --last-days=<lastDays>   Number of last days to include (use 0 for all days)
                               Default: 0
      --include-pending        Include pending transactions
      --save-details           Save each transaction as JSON file
  -v, --verbose                Enable verbose logging
      --debug                  Enable debug logging
  -h, --help                   Show this help message and exit
  -V, --version                Print version information and exit
```

### Examples

```bash
# Export all transactions
java -jar target/tr-hibiscus-export-1.1.1.jar -n +49123456789 -p 1234 /home/user/hibiscus-export

# Export transactions from last 30 days including pending ones
java -jar target/tr-hibiscus-export-1.1.1.jar -n +49123456789 -p 1234 --last-days 30 --include-pending /home/user/hibiscus-export

# Export with save transaction details
java -jar target/tr-hibiscus-export-1.1.1.jar -n +49123456789 -p 1234 --save-details /home/user/hibiscus-export

# Export with verbose logging
java -jar target/tr-hibiscus-export-1.1.1.jar -n +49123456789 -p 1234 --verbose /home/user/hibiscus-export
```

## Authentication

The application uses secure web login authentication:

- Uses the same login method as app.traderepublic.com
- Requires 4-digit code from TradeRepublic app or SMS
- Keeps you logged in on your primary device
- No device reset required

Credentials must be provided via command line parameters for security reasons.

## Output Files

The application creates the following files in the output directory:

- `hibiscus-YYYY-MM-DDTHH.MM.SS.xml` - Main export file for Hibiscus import
- `tr2hibiscus.json` - History file to track processed transactions
- `_<transaction-id>` - Individual transaction JSON files (if `--save-details` is used)
- `debug/transaction_<transaction-id>.json` - Debug files (when `--debug` flag is used)
- `debug/all_transactions_summary.json` - Summary of all transactions (when `--debug` flag is used)

## Importing to Hibiscus

1. Run the export to generate the XML file
2. Open Hibiscus banking software
3. Go to File → Import
4. Select the generated XML file
5. Follow the import wizard

## Project Structure

```
src/main/java/de/hibiscus/tr/
├── api/           # Trade Republic API client
├── auth/          # Authentication and login
├── cli/           # Command line interface
├── export/        # Hibiscus XML export functionality
├── model/         # Data models and exceptions
└── timeline/      # Timeline processing
```

## Dependencies

- **picocli** - Command line interface
- **OkHttp** - HTTP client for REST API calls
- **Java-WebSocket** - WebSocket client for real-time data
- **Jackson** - JSON processing
- **JDOM2** - XML generation
- **SLF4J + Logback** - Logging

## Implementation Status

✅ **Fully Implemented Features**:

1. **Authentication**: Web login authentication is fully implemented
2. **WebSocket Protocol**: Full Trade Republic WebSocket protocol with subscription management
3. **Timeline Processing**: Paginated data retrieval with parallel detail fetching
4. **XML Export**: Complete Hibiscus-compatible XML generation
5. **Error Handling**: Comprehensive error handling and logging

🔧 **Known Limitations**:

- Large transaction histories may take time to process due to API rate limits
- Debug mode generates many JSON files which can consume disk space

## Development

### Building and Testing

```bash
# Clean build with tests
mvn clean package

# Run tests only
mvn test

# Run specific test
mvn test -Dtest=HibiscusExporterTest

# Run development version
mvn exec:java -Dexec.mainClass="de.hibiscus.tr.cli.HibiscusExportCli" -Dexec.args="-n +49123456789 -p 1234 /path/to/output"

# Run with debug logging
mvn exec:java -Dexec.mainClass="de.hibiscus.tr.cli.HibiscusExportCli" -Dexec.args="-n +49123456789 -p 1234 --debug /path/to/output"
```

### Adding Features

The modular structure allows easy extension:

- Add new export formats in the `export` package
- Extend authentication methods in the `auth` package
- Add new CLI commands in the `cli` package

## License

This project is licensed under the MIT License.

It is based on https://github.com/pytr-org/pytr

It is a fork of https://github.com/littleyoda/tr-hibiscus-java
