package com.spindletrack.auth;

public interface PayMongoGateway {
    record CreatedLink(String id, String url, long amountCentavos, String currency, String referenceNumber) {}
    record VerifiedPayment(String id, String status, long amountCentavos, String orderIdReference) {}

    CreatedLink createPaymentLink(long amountCentavos, String orderId, String orderCode);
    VerifiedPayment findPaidPayment(String linkId);
    void archivePaymentLink(String linkId);
}
