package com.booking.member;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProfileRequest(
        @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String name,
        @Pattern(regexp = "^[0-9+()\\- ]{6,20}$") String phone) {
}
