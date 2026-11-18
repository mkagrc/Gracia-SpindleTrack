package com.spindletrack.auth;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminUserController {
    private final UserPresenceService users;
    public AdminUserController(UserPresenceService users) { this.users = users; }

    @GetMapping
    public UserPresenceService.AdminPage list(@RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page) {
        return users.listUsers(role, page);
    }
}
