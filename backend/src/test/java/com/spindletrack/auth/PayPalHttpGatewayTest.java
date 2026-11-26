package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class PayPalHttpGatewayTest {

    @Test
    void createOrderFetchesOAuthTokenAndCreatesPhpCaptureOrder() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api-m.sandbox.paypal.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://api-m.sandbox.paypal.com/v1/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"tok-123\",\"expires_in\":32400}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andRespond(withSuccess(
                        "{\"id\":\"PP-ORD-1\",\"status\":\"CREATED\",\"links\":[{\"rel\":\"approve\",\"href\":\"https://www.sandbox.paypal.com/checkoutnow?token=PP-ORD-1\"}]}",
                        MediaType.APPLICATION_JSON));

        PayPalHttpGateway gateway = new PayPalHttpGateway(
                "client-id-1", "client-secret-1",
                "http://localhost:5173/paypal/return", "http://localhost:5173/paypal/cancel",
                builder.build());

        PayPalGateway.CreatedOrder created = gateway.createOrder(new BigDecimal("360.00"), "order-10", "O-000010");
        assertEquals("PP-ORD-1", created.id());
        assertEquals("https://www.sandbox.paypal.com/checkoutnow?token=PP-ORD-1", created.approvalUrl());
        assertEquals(new BigDecimal("360.00"), created.amount());
        assertEquals("PHP", created.currency());
        server.verify();
    }

    @Test
    void captureOrderReusesCachedTokenAndExtractsCapturedAmountAndId() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api-m.sandbox.paypal.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://api-m.sandbox.paypal.com/v1/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"tok-abc\",\"expires_in\":32400}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/PP-ORD-2/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-abc"))
                .andRespond(withSuccess(
                        "{\"id\":\"PP-ORD-2\",\"status\":\"COMPLETED\",\"purchase_units\":[{\"payments\":{\"captures\":[{\"id\":\"CAP-2\",\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\"PHP\",\"value\":\"360.00\"}}]}}]}",
                        MediaType.APPLICATION_JSON));

        PayPalHttpGateway gateway = new PayPalHttpGateway(
                "client-id-1", "client-secret-1",
                "http://localhost:5173/paypal/return", "http://localhost:5173/paypal/cancel",
                builder.build());

        PayPalGateway.CapturedOrder captured = gateway.captureOrder("PP-ORD-2");
        assertEquals("PP-ORD-2", captured.id());
        assertEquals("COMPLETED", captured.status());
        assertEquals(new BigDecimal("360.00"), captured.amount());
        assertEquals("PHP", captured.currency());
        assertEquals("CAP-2", captured.captureId());
        server.verify();
    }

    @Test
    void missingCredentialsReturn503AndGatewayErrorReturns502() {
        PayPalHttpGateway unconfigured = new PayPalHttpGateway(
                "", "", "http://localhost:5173/paypal/return", "http://localhost:5173/paypal/cancel",
                RestClient.builder().baseUrl("https://api-m.sandbox.paypal.com").build());
        ResponseStatusException noCreds = assertThrows(ResponseStatusException.class,
                () -> unconfigured.createOrder(new BigDecimal("100.00"), "order-1", "O-000001"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, noCreds.getStatusCode());

        RestClient.Builder builder = RestClient.builder().baseUrl("https://api-m.sandbox.paypal.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api-m.sandbox.paypal.com/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"tok-1\",\"expires_in\":32400}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders"))
                .andRespond(withBadRequest());

        PayPalHttpGateway failing = new PayPalHttpGateway(
                "client-id-1", "client-secret-1",
                "http://localhost:5173/paypal/return", "http://localhost:5173/paypal/cancel",
                builder.build());
        ResponseStatusException badGateway = assertThrows(ResponseStatusException.class,
                () -> failing.createOrder(new BigDecimal("100.00"), "order-1", "O-000001"));
        assertEquals(HttpStatus.BAD_GATEWAY, badGateway.getStatusCode());
    }
}
