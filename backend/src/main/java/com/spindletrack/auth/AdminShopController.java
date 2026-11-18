package com.spindletrack.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/shops")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminShopController {
    private final AdminShopService shops;
    public AdminShopController(AdminShopService shops) { this.shops = shops; }

    public record RejectRequest(@NotBlank(message = "Rejection reason is required.") @Size(max = 500, message = "Rejection reason must be 500 characters or fewer.") String reason) {}
    public record ActionResponse(String shopId, String status) {}

    @GetMapping
    public AdminShopService.PageResult list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page) {
        return shops.list(status, search, page);
    }
    @GetMapping("/{id}")
    public AdminShopService.ShopDetail detail(@PathVariable String id) { return shops.detail(id); }
    @PostMapping("/{id}/approve")
    public ActionResponse approve(@PathVariable String id, @AuthenticationPrincipal FirebasePrincipal principal) {
        shops.transition(id, "APPROVED", null, principal.userId()); return new ActionResponse(id, "ACTIVE");
    }
    @PostMapping("/{id}/reject")
    public ActionResponse reject(@PathVariable String id, @Valid @RequestBody RejectRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        shops.transition(id, "REJECTED", request.reason().trim(), principal.userId()); return new ActionResponse(id, "REJECTED");
    }
    @PostMapping("/{id}/deactivate")
    public ActionResponse deactivate(@PathVariable String id, @AuthenticationPrincipal FirebasePrincipal principal) {
        shops.transition(id, "DEACTIVATED", null, principal.userId()); return new ActionResponse(id, "DEACTIVATED");
    }
    @PostMapping("/{id}/activate")
    public ActionResponse activate(@PathVariable String id, @AuthenticationPrincipal FirebasePrincipal principal) {
        shops.transition(id, "ACTIVE", null, principal.userId()); return new ActionResponse(id, "ACTIVE");
    }
}
