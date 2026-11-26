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
@RequestMapping("/api/shops/add-ons")
public class ShopAddOnController {
    private final ShopAddOnCatalog addOns;
    public ShopAddOnController(ShopAddOnCatalog addOns) { this.addOns = addOns; }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','STAFF')")
    public List<ShopAddOnCatalog.AddOn> list(@AuthenticationPrincipal FirebasePrincipal principal) {
        return addOns.list(principal.shopId(), principal.role());
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ShopAddOnCatalog.AddOn create(@Valid @RequestBody ShopAddOnCatalog.AddOnRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return addOns.create(principal.shopId(), request);
    }

    @PutMapping("/{addOnId}")
    @PreAuthorize("hasRole('OWNER')")
    public ShopAddOnCatalog.AddOn update(@PathVariable String addOnId, @Valid @RequestBody ShopAddOnCatalog.AddOnRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return addOns.update(principal.shopId(), addOnId, request);
    }

    @PostMapping("/{addOnId}/deactivate")
    @PreAuthorize("hasRole('OWNER')")
    public ShopAddOnCatalog.AddOn deactivate(@PathVariable String addOnId, @AuthenticationPrincipal FirebasePrincipal principal) {
        return addOns.setActive(principal.shopId(), addOnId, false);
    }

    @PostMapping("/{addOnId}/activate")
    @PreAuthorize("hasRole('OWNER')")
    public ShopAddOnCatalog.AddOn activate(@PathVariable String addOnId, @AuthenticationPrincipal FirebasePrincipal principal) {
        return addOns.setActive(principal.shopId(), addOnId, true);
    }
}
