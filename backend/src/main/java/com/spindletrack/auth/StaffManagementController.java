package com.spindletrack.auth;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops/mine")
@PreAuthorize("hasRole('OWNER')")
public class StaffManagementController {
    private final StaffManagementService staff;
    public StaffManagementController(StaffManagementService staff) { this.staff = staff; }

    @GetMapping("/staff")
    public List<StaffManagementService.StaffMember> listStaff(@AuthenticationPrincipal FirebasePrincipal principal) {
        return staff.staff(principal.shopId());
    }

    @PostMapping("/staff/{staffUserId}/deactivate")
    public StaffManagementService.StaffMember deactivateStaff(@PathVariable String staffUserId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return staff.deactivateStaff(principal.shopId(), staffUserId);
    }

    @PostMapping("/staff/{staffUserId}/remove")
    public StaffManagementService.StaffMember removeStaff(@PathVariable String staffUserId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return staff.removeStaff(principal.shopId(), staffUserId);
    }

    @GetMapping("/staff/invitations")
    public List<StaffManagementService.InvitationSummary> listInvitations(@AuthenticationPrincipal FirebasePrincipal principal) {
        return staff.invitations(principal.shopId());
    }

    @PostMapping("/staff/invitations")
    public StaffManagementService.InvitationLink invite(@Valid @RequestBody StaffManagementService.InviteRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return staff.invite(principal.shopId(), principal.userId(), request.email());
    }

    @PostMapping("/staff/invitations/{invitationId}/revoke")
    public StaffManagementService.InvitationSummary revoke(@PathVariable String invitationId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return staff.revoke(principal.shopId(), invitationId);
    }

    @PostMapping("/staff/invitations/{invitationId}/resend")
    public StaffManagementService.InvitationLink resend(@PathVariable String invitationId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return staff.resend(principal.shopId(), invitationId);
    }
}

@RestController
@RequestMapping("/api/staff/invitations")
class StaffInvitationAcceptanceController {
    private final StaffManagementService staff;
    StaffInvitationAcceptanceController(StaffManagementService staff) { this.staff = staff; }

    @PostMapping("/accept")
    @PreAuthorize("hasRole('AUTHENTICATED')")
    public StaffManagementService.AcceptResult accept(@Valid @RequestBody StaffManagementService.AcceptRequest request,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return staff.accept(request.token(), principal);
    }
}
