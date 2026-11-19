package com.spindletrack.auth;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops/services")
public class ShopServiceController {
    private final ShopServiceCatalog services;
    public ShopServiceController(ShopServiceCatalog services) { this.services = services; }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','STAFF')")
    public List<ShopServiceCatalog.ShopService> list(@AuthenticationPrincipal FirebasePrincipal principal) {
        return services.list(principal.shopId(), principal.role());
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ShopServiceCatalog.ShopService create(@Valid @RequestBody ShopServiceCatalog.ServiceRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return services.create(principal.shopId(), request);
    }

    @PutMapping("/{serviceId}")
    @PreAuthorize("hasRole('OWNER')")
    public ShopServiceCatalog.ShopService update(@PathVariable String serviceId,
            @Valid @RequestBody ShopServiceCatalog.ServiceRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return services.update(principal.shopId(), serviceId, request);
    }

    @PostMapping("/{serviceId}/deactivate")
    @PreAuthorize("hasRole('OWNER')")
    public ShopServiceCatalog.ShopService deactivate(@PathVariable String serviceId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return services.setActive(principal.shopId(), serviceId, false);
    }

    @PostMapping("/{serviceId}/activate")
    @PreAuthorize("hasRole('OWNER')")
    public ShopServiceCatalog.ShopService activate(@PathVariable String serviceId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return services.setActive(principal.shopId(), serviceId, true);
    }
}
