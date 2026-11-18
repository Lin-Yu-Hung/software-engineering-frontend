package com.booking.auth;

import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "^[0-9+()\\- ]{6,20}$") String phone) {
}
