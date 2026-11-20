package com.spindletrack.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PayMongoHttpGateway implements PayMongoGateway {
    private static final String BASE = "https://api.paymongo.com/v1";
    private final String secretKey;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public PayMongoHttpGateway(@Value("${paymongo.secret-key:}") String secretKey) {
        this(secretKey, HttpClient.newHttpClient(), new ObjectMapper());
    }

    PayMongoHttpGateway(String secretKey, HttpClient client, ObjectMapper mapper) {
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public CreatedLink createPaymentLink(long amountCentavos, String orderId, String orderCode) {
        requireTestKey();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", amountCentavos);
        request.put("currency", "PHP");
        request.put("description", "SpindleTrack order " + orderCode);
        request.put("remarks", "SpindleTrack order payment");
        request.put("metadata", Map.of("orderId", orderId, "orderCode", orderCode));
        Map<String, Object> data = object(call("POST", "/payment_links", request).get("data"));
        String id = text(data.get("id"));
        String url = text(data.get("url"));
        long amount = number(data.get("amount"));
        String currency = text(data.get("currency"));
        String reference = text(data.get("reference_number"));
        if (id.isBlank() || url.isBlank() || amount != amountCentavos || !"PHP".equals(currency))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayMongo returned an invalid payment link.");
        return new CreatedLink(id, url, amount, currency, reference);
    }

    @Override
    public VerifiedPayment findPaidPayment(String linkId) {
        requireTestKey();
        Map<String, Object> linkData = object(call("GET", "/payment_links/" + safePath(linkId), null).get("data"));
        long linkAmount = number(linkData.get("amount"));
        String currency = text(linkData.get("currency"));
        String linkOrderId = text(object(linkData.get("metadata")).get("orderId"));
        if (linkAmount < 0 || !"PHP".equals(currency) || linkOrderId.isBlank()) return null;
        Map<String, Object> result = call("GET", "/payment_links/" + safePath(linkId) + "/payments", null);
        Object raw = result.get("data");
        if (!(raw instanceof List<?> rows)) return null;
        for (Object row : rows) {
            Map<String, Object> payment = object(row);
            Map<String, Object> attributes = object(payment.get("attributes"));
            if (attributes.isEmpty()) attributes = payment;
            String status = text(attributes.get("status"));
            if (!"paid".equalsIgnoreCase(status) && !"succeeded".equalsIgnoreCase(status)) continue;
            long paidAmount = number(attributes.get("amount"));
            if (paidAmount != linkAmount) continue;
            return new VerifiedPayment(text(payment.get("id")), status, paidAmount, linkOrderId);
        }
        return null;
    }

    @Override
    public void archivePaymentLink(String linkId) {
        requireTestKey();
        call("PATCH", "/payment_links/" + safePath(linkId), Map.of("archive", true));
    }

    private Map<String, Object> call(String method, String path, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json");
            if ("GET".equals(method)) request.GET();
            else request.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = mapper.readValue(response.body(), new TypeReference<>() {});
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayMongo could not complete the payment request.");
            return parsed;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayMongo could not complete the payment request.");
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PayMongo could not complete the payment request.");
        }
    }

    private void requireTestKey() {
        if (!secretKey.startsWith("sk_test_"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PayMongo test mode is not configured. Set PAYMONGO_SECRET_KEY to a test secret key.");
    }
    private static String safePath(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{5,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid PayMongo payment link reference.");
        return value;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
    private static String text(Object value) { return value instanceof String text ? text : ""; }
    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException exception) { return -1L; }
    }
}
