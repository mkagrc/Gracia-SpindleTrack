package com.spindletrack.auth;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops/mine")
public class OwnerOperationsController {
    private final OwnerOperationsService operations;
    public OwnerOperationsController(OwnerOperationsService operations) { this.operations = operations; }

    @GetMapping("/unclaimed")
    @PreAuthorize("hasAnyRole('OWNER','STAFF')")
    public List<OwnerOperationsService.UnclaimedOrder> unclaimed(@AuthenticationPrincipal FirebasePrincipal principal) {
        return operations.unclaimed(principal);
    }

    @GetMapping("/ready-orders")
    @PreAuthorize("hasAnyRole('OWNER','STAFF')")
    public OwnerOperationsService.ReadyAndClaimedResult readyOrders(@AuthenticationPrincipal FirebasePrincipal principal) {
        return operations.readyOrders(principal);
    }

    @GetMapping("/reports")
    @PreAuthorize("hasRole('OWNER')")
    public OwnerOperationsService.OwnerReport reports(@AuthenticationPrincipal FirebasePrincipal principal) {
        return operations.report(principal);
    }
}
