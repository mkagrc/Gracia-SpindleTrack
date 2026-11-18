package com.spindletrack.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ShopRegistrationEligibility {
    public void requireEligible(UserAccount account, boolean hasAttachedOrders) {
        if (!"CUSTOMER".equals(account.role()) || account.shopId() != null || hasAttachedOrders) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Please use another account.");
        }
    }
}
