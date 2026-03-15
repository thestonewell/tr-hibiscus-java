package de.hibiscus.tr.export.transactions;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.hibiscus.tr.model.TransactionEvent;

/**
 * Singleton factory for managing transaction handlers and extraction logic.
 * Provides centralized handler chain evaluation with fallback to default handler.
 */
public final class TransactionHandlerFactory {

    private static final Logger logger = LoggerFactory.getLogger(TransactionHandlerFactory.class);
    private static final TransactionHandlerFactory INSTANCE = new TransactionHandlerFactory();

    private final List<TransactionHandler> handlers;

    private TransactionHandlerFactory() {
        this.handlers = initializeHandlers();
    }

    public static TransactionHandlerFactory getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize transaction handlers in priority order.
     * Handlers are evaluated sequentially - first match wins.
     * Order matters: more specific handlers (e.g., DepositHandler checking subtitle)
     * must come before generic handlers.
     */
    private List<TransactionHandler> initializeHandlers() {
        List<TransactionHandler> list = new ArrayList<>();
        // Specific transfer types (check subtitle)
        list.add(new DepositHandler());
        list.add(new WithdrawalHandler());
        // Other specific transaction types
        list.add(new CardPaymentHandler());
        list.add(new InterestHandler());
        list.add(new SavingsPlanHandler());
        list.add(new SavebackHandler());
        list.add(new RoundUpHandler());
        list.add(new DividendHandler());
        list.add(new OrderHandler("Kauforder"));
        list.add(new OrderHandler("Verkaufsorder"));
        list.add(new TaxAdjustmentHandler());
        list.add(new JuniorP2PTransferHandler());
        list.add(new JuniorChildOrderFundingHandler());
        return list;
    }

    /**
     * Extract transaction data using handler chain with fallback to default handler.
     *
     * @param event The transaction event
     * @return TransactionData extracted by first matching handler or default handler
     */
    public TransactionHandler.TransactionData extractTransactionData(TransactionEvent event) {
        // Try each handler in order
        for (TransactionHandler handler : handlers) {
            if (handler.canHandle(event)) {
                return handler.extractData(event);
            }
        }

        // No handler found - log and use default fallback
        logger.warn("No specific handler found for event: {}, using default handler", event.getId());
        return new DefaultTransactionHandler().extractData(event);
    }
}
