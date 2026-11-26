package com.spindletrack.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShopRegistrationRequest(
        @NotBlank(message = "Enter your shop name.")
        @Size(max = 160, message = "Shop name must be 160 characters or fewer.")
        String shopName,
        @NotBlank(message = "Enter your shop address.")
        @Size(max = 500, message = "Address must be 500 characters or fewer.")
        String address,
        @NotBlank(message = "Enter a contact number.")
        @Pattern(regexp = "[0-9+() .-]{7,32}", message = "Enter a valid contact number.")
        String contactNumber,
        @Size(max = 160, message = "Municipality must be 160 characters or fewer.")
        String municipality,
        @Size(max = 160, message = "Province must be 160 characters or fewer.")
        String province) {
    public ShopRegistrationRequest(String shopName, String address, String contactNumber) {
        this(shopName, address, contactNumber, null, null);
    }
}
