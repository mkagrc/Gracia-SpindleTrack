package com.spindletrack.auth;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops/mine")
@PreAuthorize("hasRole('OWNER')")
public class OwnerShopController {
    private final OwnerShopService shops;
    public OwnerShopController(OwnerShopService shops) { this.shops = shops; }
    @GetMapping
    public OwnerShopService.ShopProfile get(@AuthenticationPrincipal FirebasePrincipal principal) {
        return shops.getMine(principal.shopId());
    }
    @PutMapping
    public OwnerShopService.ShopProfile update(@Valid @RequestBody OwnerShopService.UpdateRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return shops.updateMine(principal.shopId(), request);
    }
}
