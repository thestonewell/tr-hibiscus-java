package de.hibiscus.tr.api;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.hibiscus.tr.model.TradeRepublicError;

/**
 * Trade Republic API client
 */
public class TradeRepublicApi {

    private static final Logger logger = LoggerFactory.getLogger(TradeRepublicApi.class);

    private static final String WS_URL = "wss://api.traderepublic.com";

    private final ObjectMapper objectMapper;

    private WebSocketClient webSocketClient;
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong subscriptionIdCounter = new AtomicLong(1);

    private boolean webLogin = true;
    private String deviceId;
    private String sessionToken;

    public TradeRepublicApi() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Connect to Trade Republic WebSocket
     */
    public CompletableFuture<Void> connect() throws TradeRepublicError {
        return connect(null);
    }

    /**
     * Connect to Trade Republic WebSocket with optional cookies for web login
     */
    public CompletableFuture<Void> connect(String cookieHeader) throws TradeRepublicError {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            URI wsUri = URI.create(WS_URL);

            // Prepare headers for web login
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            if (webLogin && cookieHeader != null && !cookieHeader.isEmpty()) {
                headers.put("Cookie", cookieHeader);
                logger.debug("Using cookies for WebSocket: {}", cookieHeader);
            }

            webSocketClient = new WebSocketClient(wsUri, headers) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    logger.info("WebSocket connected, sending connection message");
                    try {
                        sendConnectionMessage();
                        future.complete(null);
                    } catch (Exception e) {
                        future.completeExceptionally(new TradeRepublicError("Failed to send connection message", e));
                    }
                }

                @Override
                public void onMessage(String message) {
                    handleWebSocketMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    logger.info("WebSocket closed: {} - {}", code, reason);
                }

                @Override
                public void onError(Exception ex) {
                    logger.error("WebSocket error", ex);
                    if (!future.isDone()) {
                        future.completeExceptionally(new TradeRepublicError("WebSocket connection failed", ex));
                    }
                }
            };

            webSocketClient.connect();

        } catch (Exception e) {
            throw new TradeRepublicError("Failed to connect to WebSocket", e);
        }

        return future;
    }

    /**
     * Send initial connection message
     */
    private void sendConnectionMessage() throws Exception {
        Map<String, Object> connectionMessage;
        int connectId;

        if (webLogin) {
            connectionMessage = Map.of(
                    "locale", "de",
                    "platformId", "webtrading",
                    "platformVersion", "chrome - 94.0.4606",
                    "clientId", "app.traderepublic.com",
                    "clientVersion", "5582"
            );
            connectId = 31;
        } else {
            connectionMessage = Map.of("locale", "de");
            connectId = 21;
        }

        String message = "connect " + connectId + " " + objectMapper.writeValueAsString(connectionMessage);
        webSocketClient.send(message);
        logger.debug("Sent connection message: {}", message);
    }

    /**
     * Handle incoming WebSocket messages
     */
    private void handleWebSocketMessage(String message) {
        try {
            logger.debug("Received WebSocket message: {}", message);

            // Handle connection confirmation
            if ("connected".equals(message.trim())) {
                logger.info("WebSocket connection confirmed");
                return;
            }

            // Parse subscription response format: "subscriptionId code [payload]"
            String[] parts = message.split(" ", 3);
            if (parts.length < 2) {
                logger.warn("Invalid message format: {}", message);
                return;
            }

            String subscriptionId = parts[0];
            String code = parts[1];
            String payloadStr = parts.length > 2 ? parts[2] : "";

            // Handle different response codes
            switch (code) {
                case "A": // Data response
                    JsonNode payload = objectMapper.readTree(payloadStr);
                    logger.info("Received data for subscription {}: {}", subscriptionId, payload.toString());
                    CompletableFuture<JsonNode> future = pendingRequests.remove(subscriptionId);
                    if (future != null) {
                        // Create response object similar to Python version
                        Map<String, Object> response = Map.of(
                                "subscription_id", subscriptionId,
                                "data", payload
                        );
                        future.complete(objectMapper.valueToTree(response));
                    }
                    break;

                case "C": // Connection/completion
                    logger.info("Subscription {} completed with no data", subscriptionId);
                    CompletableFuture<JsonNode> completionFuture = pendingRequests.remove(subscriptionId);
                    if (completionFuture != null) {
                        // For "C" messages, complete with empty data
                        Map<String, Object> response = Map.of(
                                "subscription_id", subscriptionId,
                                "data", objectMapper.createArrayNode()
                        );
                        completionFuture.complete(objectMapper.valueToTree(response));
                    }
                    break;

                case "E": // Error
                    logger.error("Subscription {} error: {}", subscriptionId, payloadStr);
                    CompletableFuture<JsonNode> errorFuture = pendingRequests.remove(subscriptionId);
                    if (errorFuture != null) {
                        errorFuture.completeExceptionally(new TradeRepublicError("Subscription error: " + payloadStr));
                    }
                    break;

                default:
                    logger.warn("Unknown message code {}: {}", code, message);
            }

        } catch (Exception e) {
            logger.error("Error handling WebSocket message: {}", message, e);
        }
    }

    /**
     * Send subscription request
     */
    public CompletableFuture<JsonNode> subscribe(String type, Map<String, Object> parameters) {
        String subscriptionId = String.valueOf(subscriptionIdCounter.getAndIncrement());

        Map<String, Object> subscription = new java.util.HashMap<>();
        subscription.put("type", type);

        if (parameters != null) {
            subscription.putAll(parameters);
        }

        // Add session token for app login
        if (!webLogin && sessionToken != null) {
            subscription.put("token", sessionToken);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(subscriptionId, future);

        try {
            String payloadJson = objectMapper.writeValueAsString(subscription);
            String message = "sub " + subscriptionId + " " + payloadJson;
            webSocketClient.send(message);
            logger.debug("Sent subscription: {}", message);
        } catch (Exception e) {
            pendingRequests.remove(subscriptionId);
            future.completeExceptionally(new TradeRepublicError("Failed to send subscription", e));
        }

        return future;
    }

    /**
     * Get timeline transactions
     */
    public CompletableFuture<JsonNode> getTimelineTransactions(long timestamp) {
        Map<String, Object> params = new java.util.HashMap<>();
        if (timestamp > 0) {
            params.put("after", timestamp);
        }
        return subscribe("timelineTransactions", params);
    }

    /**
     * Get timeline transactions with cursor
     */
    public CompletableFuture<JsonNode> getTimelineTransactions(String cursor) {
        Map<String, Object> params = new java.util.HashMap<>();
        if (cursor != null && !cursor.isEmpty()) {
            params.put("after", cursor);
        }
        return subscribe("timelineTransactions", params);
    }

    /**
     * Get timeline activity log
     */
    public CompletableFuture<JsonNode> getTimelineActivityLog(long timestamp) {
        Map<String, Object> params = new java.util.HashMap<>();
        if (timestamp > 0) {
            params.put("after", timestamp);
        }
        return subscribe("timelineActivityLog", params);
    }

    /**
     * Get timeline activity log with cursor
     */
    public CompletableFuture<JsonNode> getTimelineActivityLog(String cursor) {
        Map<String, Object> params = new java.util.HashMap<>();
        if (cursor != null && !cursor.isEmpty()) {
            params.put("after", cursor);
        }
        return subscribe("timelineActivityLog", params);
    }

    /**
     * Get timeline detail
     */
    public CompletableFuture<JsonNode> getTimelineDetail(String eventId) {
        Map<String, Object> params = Map.of("id", eventId);
        return subscribe("timelineDetailV2", params);
    }

    /**
     * Close WebSocket connection
     */
    public void close() {
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    /**
     * Check if using web login
     */
    public boolean isWebLogin() {
        return webLogin;
    }

    /**
     * Set web login mode
     */
    public void setWebLogin(boolean webLogin) {
        this.webLogin = webLogin;
    }

    /**
     * Get device ID
     */
    public String getDeviceId() {
        return deviceId;
    }

    /**
     * Set device ID
     */
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    /**
     * Get session token
     */
    public String getSessionToken() {
        return sessionToken;
    }

    /**
     * Set session token
     */
    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }
}
