package de.hibiscus.tr.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.hibiscus.tr.api.TradeRepublicApi;
import de.hibiscus.tr.model.TradeRepublicError;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Handles authentication and login for Trade Republic
 */
public class LoginManager {

    private static final Logger logger = LoggerFactory.getLogger(LoginManager.class);

    private static final String API_HOST = "https://api.traderepublic.com";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CookieJar cookieJar;

    public LoginManager() {

        // Add cookie jar for session management
        this.cookieJar = new CookieJar() {
            private final java.util.Map<String, java.util.List<Cookie>> cookieStore = new java.util.HashMap<>();

            @Override
            public void saveFromResponse(HttpUrl url, java.util.List<Cookie> cookies) {
                cookieStore.put(url.host(), cookies);
            }

            @Override
            public java.util.List<Cookie> loadForRequest(HttpUrl url) {
                java.util.List<Cookie> cookies = cookieStore.get(url.host());
                return cookies != null ? cookies : new java.util.ArrayList<>();
            }
        };

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .cookieJar(this.cookieJar)
                .build();

        this.objectMapper = new ObjectMapper();
    }

    /**
     * Login to Trade Republic (Web Login only)
     */
    public TradeRepublicApi login(String phoneNo, String pin) throws TradeRepublicError {

        logger.info("Starting Trade Republic login process");

        // Get credentials from parameters
        Credentials credentials = getCredentials(phoneNo, pin);

        TradeRepublicApi api = new TradeRepublicApi();
        api.setWebLogin(true);

        try {
            performWebLogin(api, credentials);
            // After successful web login, connect to WebSocket with cookies
            String cookieHeader = getCookieHeader();
            api.connect(cookieHeader).get();

            logger.info("Login successful");
            return api;

        } catch (Exception e) {
            throw new TradeRepublicError("Login failed", e);
        }
    }

    /**
     * Get credentials from parameters (mandatory)
     */
    private Credentials getCredentials(String phoneNo, String pin) throws TradeRepublicError {

        // Credentials must be provided as parameters
        if (phoneNo == null || pin == null) {
            throw new TradeRepublicError("Phone number and PIN must be provided as command line parameters. Use -n and -p options.");
        }

        logger.info("Using credentials provided via command line parameters");

        // Validate phone number format
        if (!phoneNo.matches("^\\+[1-9]\\d{1,14}$")) {
            throw new TradeRepublicError("Invalid phone number format. Use international format like +4912345678");
        }

        // Validate PIN format (4 digits)
        if (!pin.matches("^\\d{4}$")) {
            throw new TradeRepublicError("Invalid PIN format. PIN must be exactly 4 digits");
        }

        return new Credentials(phoneNo, pin);
    }

    /**
     * Perform web login (default method)
     */
    private void performWebLogin(TradeRepublicApi api, Credentials credentials) throws TradeRepublicError {
        logger.info("Using web login method");

        try {
            // Step 1: Initiate web login
            RequestBody loginBody = RequestBody.create(
                    objectMapper.writeValueAsString(
                            new LoginRequest(credentials.getPhoneNo(), credentials.getPin())
                    ),
                    MediaType.get("application/json")
            );

            Request loginRequest = new Request.Builder()
                    .url(API_HOST + "/api/v1/auth/web/login")
                    .post(loginBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "TradeRepublic/Android 30/App Version 1.1.5534")
                    .build();

            Response response = httpClient.newCall(loginRequest).execute();
            String responseBody = response.body().string();

            logger.debug("Login response code: {}", response.code());
            logger.debug("Login response body: {}", responseBody);

            if (!response.isSuccessful()) {
                String errorMessage = buildErrorMessage("Web login failed", response.code(), responseBody);
                throw new TradeRepublicError(errorMessage);
            }

            JsonNode responseJson = objectMapper.readTree(responseBody);
            if (!responseJson.has("processId")) {
                throw new TradeRepublicError("No processId in login response: " + responseBody);
            }
            String processId = responseJson.get("processId").asText();

            logger.info("Login initiated, waiting for 4-digit code...");
            System.out.println("Please enter the 4-digit code from your TradeRepublic app or SMS:");

            Scanner scanner = new Scanner(System.in);
            String code = scanner.nextLine().trim();

            // Step 2: Complete login with code
            Request codeRequest = new Request.Builder()
                    .url(API_HOST + "/api/v1/auth/web/login/" + processId + "/" + code)
                    .post(RequestBody.create("", MediaType.get("application/json")))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "TradeRepublic/Android 30/App Version 1.1.5534")
                    .build();

            Response codeResponse = httpClient.newCall(codeRequest).execute();
            String codeResponseBody = codeResponse.body().string();

            logger.debug("Code verification response code: {}", codeResponse.code());
            logger.debug("Code verification response body: {}", codeResponseBody);

            if (!codeResponse.isSuccessful()) {
                String errorMessage = buildErrorMessage("Code verification failed", codeResponse.code(), codeResponseBody);
                throw new TradeRepublicError(errorMessage);
            }

            // Check if response is successful (Python version doesn't return sessionToken, just HTTP 200)
            if (codeResponseBody.isEmpty() || codeResponseBody.trim().equals("")) {
                // Success case - cookies are stored automatically by OkHttp
                logger.info("Web login successful - session established via cookies");
            } else {
                // Try to parse response for potential error information
                try {
                    JsonNode codeResponseJson = objectMapper.readTree(codeResponseBody);
                    if (codeResponseJson.has("error")) {
                        throw new TradeRepublicError("Code verification error: " + codeResponseJson.get("error"));
                    }
                    logger.info("Web login successful");
                } catch (Exception e) {
                    logger.info("Web login successful - response: " + codeResponseBody);
                }
            }

            // Cookies are automatically stored by OkHttp cookie jar
        } catch (Exception e) {
            throw new TradeRepublicError("Web login failed", e);
        }
    }

    /**
     * Extract cookies as header string for WebSocket
     */
    private String getCookieHeader() {
        try {
            HttpUrl url = HttpUrl.parse(API_HOST);
            if (url != null) {
                java.util.List<Cookie> cookies = cookieJar.loadForRequest(url);
                if (!cookies.isEmpty()) {
                    StringBuilder cookieHeader = new StringBuilder();
                    for (Cookie cookie : cookies) {
                        // Only include cookies for traderepublic.com domain
                        if (cookie.domain().endsWith("traderepublic.com")) {
                            if (cookieHeader.length() > 0) {
                                cookieHeader.append("; ");
                            }
                            cookieHeader.append(cookie.name()).append("=").append(cookie.value());
                        }
                    }
                    String result = cookieHeader.toString();
                    if (!result.isEmpty()) {
                        logger.debug("Extracted cookies for WebSocket: {}", result);
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting cookies", e);
        }
        return null;
    }

    /**
     * Build enhanced error message from Trade Republic API response
     */
    private String buildErrorMessage(String baseMessage, int statusCode, String responseBody) {
        StringBuilder errorBuilder = new StringBuilder();
        errorBuilder.append(baseMessage).append(" (").append(statusCode).append(")");

        try {
            // Try to parse JSON error response
            JsonNode responseJson = objectMapper.readTree(responseBody);

            if (responseJson.has("errors") && responseJson.get("errors").isArray()) {
                JsonNode errors = responseJson.get("errors");

                for (JsonNode error : errors) {
                    if (error.has("errorCode")) {
                        String errorCode = error.get("errorCode").asText();
                        errorBuilder.append(" - ").append(errorCode);

                        // Add user-friendly message for known error codes
                        switch (errorCode) {
                            case "TOO_MANY_REQUESTS":
                                errorBuilder.append(": Too many login attempts");

                                // Check for nextAttemptTimestamp
                                if (error.has("meta") && error.get("meta").has("nextAttemptTimestamp")) {
                                    String timestampStr = error.get("meta").get("nextAttemptTimestamp").asText();
                                    String nextAttemptTime = formatIsoTimestamp(timestampStr);
                                    errorBuilder.append(". Please try again after ").append(nextAttemptTime);
                                }
                                break;

                            case "VALIDATION_CODE_INVALID":
                                errorBuilder.append(": Invalid verification code. Please check the 4-digit code from your TradeRepublic app");
                                break;

                            case "VALIDATION_CODE_EXPIRED":
                                errorBuilder.append(": Verification code has expired. Please request a new code");
                                break;

                            case "LOGIN_ATTEMPTS_EXCEEDED":
                                errorBuilder.append(": Maximum login attempts exceeded");

                                if (error.has("meta") && error.get("meta").has("nextAttemptTimestamp")) {
                                    String timestampStr = error.get("meta").get("nextAttemptTimestamp").asText();
                                    String nextAttemptTime = formatIsoTimestamp(timestampStr);
                                    errorBuilder.append(". Account locked until ").append(nextAttemptTime);
                                }
                                break;

                            default:
                                // For unknown error codes, include the raw code
                                break;
                        }
                    }
                }
            }

        } catch (Exception e) {
            // If JSON parsing fails, fall back to basic error message
            logger.debug("Could not parse error response JSON: {}", responseBody, e);
            errorBuilder.append(" - ").append(responseBody);
        }

        return errorBuilder.toString();
    }

    /**
     * Format ISO 8601 timestamp to user-friendly string with local timezone
     */
    private String formatIsoTimestamp(String isoTimestamp) {
        try {
            // Parse ISO 8601 timestamp (e.g., "2025-08-02T11:51:48.725543233Z")
            Instant instant = Instant.parse(isoTimestamp);

            // Format to local timezone with German format
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z")
                    .withZone(ZoneId.systemDefault());
            return formatter.format(instant);
        } catch (Exception e) {
            // Fallback to raw timestamp if parsing fails
            logger.debug("Could not parse ISO timestamp: {}", isoTimestamp, e);
            return isoTimestamp;
        }
    }

    // Request/Response DTOs for Web Login
    private static class LoginRequest {

        public String phoneNumber;
        public String pin;

        public LoginRequest(String phoneNumber, String pin) {
            this.phoneNumber = phoneNumber;
            this.pin = pin;
        }
    }

    /**
     * Simple credentials holder
     */
    private static class Credentials {

        private final String phoneNo;
        private final String pin;

        public Credentials(String phoneNo, String pin) {
            this.phoneNo = phoneNo;
            this.pin = pin;
        }

        public String getPhoneNo() {
            return phoneNo;
        }

        public String getPin() {
            return pin;
        }
    }
}
