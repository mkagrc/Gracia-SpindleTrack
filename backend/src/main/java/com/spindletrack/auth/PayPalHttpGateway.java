package com.spindletrack.auth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * PayPal Orders v2 REST client (Sandbox by default) using Spring RestClient.
 * Never logs credentials or access tokens.
 */
@Component
public class PayPalHttpGateway implements PayPalGateway {

    private static final Pattern SAFE_PAYPAL_ID = Pattern.compile("[A-Za-z0-9_-]{5,100}");
    private static final String DEFAULT_BASE_URL = "https://api-m.sandbox.paypal.com";

    private final String clientId;
    private final String clientSecret;
    private final String returnUrl;
    private final String cancelUrl;
    private final RestClient restClient;

    private volatile CachedToken cachedToken;

    private record CachedToken(String value, Instant expiresAt) {
        boolean isValid() {
            return value != null && !value.isBlank() && Instant.now().isBefore(expiresAt);
        }
    }

    @Autowired
    public PayPalHttpGateway(
            @Value("${paypal.client-id:}") String clientId,
            @Value("${paypal.client-secret:}") String clientSecret,
            @Value("${paypal.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${paypal.return-url:http://localhost:5173/paypal/return}") String returnUrl,
            @Value("${paypal.cancel-url:http://localhost:5173/paypal/cancel}") String cancelUrl) {
        this(
                clientId,
                clientSecret,
                returnUrl,
                cancelUrl,
                RestClient.builder()
                        .baseUrl(normalizeBaseUrl(baseUrl))
                        .build());
    }

    PayPalHttpGateway(
            String clientId,
            String clientSecret,
            String returnUrl,
            String cancelUrl,
            RestClient restClient) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.returnUrl = returnUrl == null || returnUrl.isBlank() ? "http://localhost:5173/paypal/return" : returnUrl.trim();
        this.cancelUrl = cancelUrl == null || cancelUrl.isBlank() ? "http://localhost:5173/paypal/cancel" : cancelUrl.trim();
        this.restClient = restClient;
    }

    @Override
    public CreatedOrder createOrder(BigDecimal amount, String orderId, String orderCode) {
        requireCredentials();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PayPal orders require an amount greater than zero.");
        }
        String formattedAmount = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String orderReturnUrl = withOrderId(returnUrl, orderId);
        String orderCancelUrl = withOrderId(cancelUrl, orderId);

        Map<String, Object> amountMap = Map.of(
                "currency_code", "PHP",
                "value", formattedAmount);
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("reference_id", orderId);
        unit.put("custom_id", orderId);
        unit.put("description", "SpindleTrack order " + (orderCode == null ? orderId : orderCode));
        unit.put("amount", amountMap);

        Map<String, Object> appContext = new LinkedHashMap<>();
        appContext.put("brand_name", "SpindleTrack");
        appContext.put("user_action", "PAY_NOW");
        appContext.put("return_url", orderReturnUrl);
        appContext.put("cancel_url", orderCancelUrl);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intent", "CAPTURE");
        body.put("purchase_units", List.of(unit));
        body.put("application_context", appContext);

        String token = accessToken();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/v2/checkout/orders")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal returned an empty order response.");
            }
            String paypalOrderId = text(response.get("id"));
            String approvalUrl = extractApprovalUrl(response.get("links"));
            if (paypalOrderId.isBlank() || approvalUrl.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal returned an invalid order or approval URL.");
            }
            return new CreatedOrder(paypalOrderId, approvalUrl, new BigDecimal(formattedAmount), "PHP");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal rejected the create-order request.");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal could not create the payment order.");
        }
    }

    @Override
    public CapturedOrder captureOrder(String paypalOrderId) {
        requireCredentials();
        String safeId = safeOrderId(paypalOrderId);
        String token = accessToken();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/v2/checkout/orders/{id}/capture", safeId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal returned an empty capture response.");
            }
            String status = text(response.get("status"));
            Map<String, Object> capture = firstCapture(response.get("purchase_units"));
            String captureId = text(capture.get("id"));
            String captureStatus = text(capture.get("status"));
            Map<String, Object> amountObj = asMap(capture.get("amount"));
            String currency = text(amountObj.get("currency_code"));
            BigDecimal amount = parseAmount(amountObj.get("value"));
            String effectiveStatus = !captureStatus.isBlank() ? captureStatus : status;
            return new CapturedOrder(
                    text(response.get("id")).isBlank() ? safeId : text(response.get("id")),
                    effectiveStatus,
                    amount,
                    currency,
                    captureId);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal could not capture the payment order.");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal capture failed.");
        }
    }

    private synchronized String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.isValid()) {
            return current.value();
        }
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/v1/oauth2/token")
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            String token = response == null ? "" : text(response.get("access_token"));
            if (token.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal authentication failed.");
            }
            long expiresIn = 300L;
            Object rawExpires = response.get("expires_in");
            if (rawExpires instanceof Number num && num.longValue() > 60L) {
                expiresIn = num.longValue() - 60L;
            }
            cachedToken = new CachedToken(token, Instant.now().plusSeconds(expiresIn));
            return token;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayPal authentication failed.");
        }
    }

    private void requireCredentials() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PayPal Sandbox is not configured. Set PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET.");
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return DEFAULT_BASE_URL;
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String withOrderId(String baseReturnOrCancelUrl, String orderId) {
        return UriComponentsBuilder.fromUriString(baseReturnOrCancelUrl)
                .replaceQueryParam("orderId", orderId)
                .build(true)
                .toUriString();
    }

    private static String safeOrderId(String value) {
        if (value == null || !SAFE_PAYPAL_ID.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid PayPal order reference.");
        }
        return value;
    }

    private static String extractApprovalUrl(Object linksRaw) {
        if (!(linksRaw instanceof List<?> links)) return "";
        String fallback = "";
        for (Object item : links) {
            Map<String, Object> link = asMap(item);
            String rel = text(link.get("rel"));
            String href = text(link.get("href"));
            if ("approve".equalsIgnoreCase(rel) && !href.isBlank()) return href;
            if ("payer-action".equalsIgnoreCase(rel) && !href.isBlank()) fallback = href;
        }
        return fallback;
    }

    private static Map<String, Object> firstCapture(Object purchaseUnitsRaw) {
        if (!(purchaseUnitsRaw instanceof List<?> units) || units.isEmpty()) return Map.of();
        Map<String, Object> unit = asMap(units.get(0));
        Map<String, Object> payments = asMap(unit.get("payments"));
        Object capturesRaw = payments.get("captures");
        if (!(capturesRaw instanceof List<?> captures) || captures.isEmpty()) return Map.of();
        return asMap(captures.get(0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String s ? s.trim() : "";
    }

    private static BigDecimal parseAmount(Object raw) {
        if (raw == null) return null;
        try {
            return new BigDecimal(String.valueOf(raw).trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
