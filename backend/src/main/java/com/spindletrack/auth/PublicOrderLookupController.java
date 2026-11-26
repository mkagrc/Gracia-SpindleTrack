package com.spindletrack.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/orders")
public class PublicOrderLookupController {
    private final PublicOrderLookupService lookups;
    private final PublicClientAddressResolver clientAddresses;

    public PublicOrderLookupController(PublicOrderLookupService lookups, PublicClientAddressResolver clientAddresses) {
        this.lookups = lookups;
        this.clientAddresses = clientAddresses;
    }

    @GetMapping("/lookup")
    public PublicOrderLookupService.WalkInOrder lookup(
            @RequestParam(required = false) String orderCode,
            @RequestParam(required = false) String mobile,
            HttpServletRequest request) {
        return lookups.lookup(orderCode, mobile, clientAddresses.resolve(request));
    }

    @PostMapping("/paypal-order")
    public PublicOrderLookupService.PublicPayPalOrderResponse createPayPalOrder(
            @RequestBody(required = false) PublicOrderLookupService.PublicPayPalOrderRequest body,
            HttpServletRequest request) {
        return lookups.createPublicPayPalOrder(body, clientAddresses.resolve(request));
    }

    @PostMapping("/paypal-capture")
    public PublicOrderLookupService.PublicPayPalCaptureResponse capturePayPalOrder(
            @RequestBody(required = false) PublicOrderLookupService.PublicPayPalCaptureRequest body,
            HttpServletRequest request) {
        return lookups.capturePublicPayPalOrder(body, clientAddresses.resolve(request));
    }
}
