package com.spindletrack.auth;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops/mine/staff-presence")
@PreAuthorize("hasRole('OWNER')")
public class OwnerStaffPresenceController {
    private final UserPresenceService presence;
    public OwnerStaffPresenceController(UserPresenceService presence) { this.presence = presence; }

    @GetMapping
    public List<UserPresenceService.StaffPresence> list(@AuthenticationPrincipal FirebasePrincipal principal) {
        return presence.staffForShop(principal.shopId());
    }
}
