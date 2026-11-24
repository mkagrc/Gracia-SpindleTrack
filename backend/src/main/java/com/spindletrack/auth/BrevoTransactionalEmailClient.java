package com.spindletrack.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BrevoTransactionalEmailClient {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Value("${brevo.api-key:}") private String apiKey;
    @Value("${brevo.sender-email:}") private String senderEmail;
    @Value("${brevo.sender-name:SpindleTrack}") private String senderName;

    public BrevoTransactionalEmailClient(ObjectMapper json) { this.json = json; }
    public boolean configured() { return apiKey != null && !apiKey.isBlank() && senderEmail != null && !senderEmail.isBlank(); }

    public String send(String eventId, String recipientEmail, String recipientName, String subject, String html, String text) {
        if (!configured()) throw new IllegalStateException("Brevo is not configured.");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sender", Map.of("name", senderName, "email", senderEmail));
            payload.put("to", List.of(Map.of("email", recipientEmail, "name", recipientName == null ? "Customer" : recipientName)));
            payload.put("subject", subject); payload.put("htmlContent", html); payload.put("textContent", text);
            payload.put("headers", Map.of("X-SpindleTrack-Event-Id", eventId));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .timeout(Duration.ofSeconds(10))
                    .header("api-key", apiKey).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Brevo returned HTTP " + response.statusCode());
            return json.readTree(response.body()).path("messageId").asText("");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Brevo request was interrupted.", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Brevo request failed.", exception);
        }
    }
}
