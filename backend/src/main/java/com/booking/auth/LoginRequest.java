package com.booking.auth;

import jakarta.validation.constraints.*;

public record LoginRequest(
        @NotBlank(message = "Email 不可空白") @Email(message = "Email 格式不正確")
        @Size(max = 254, message = "Email 不可超過 254 字元") String email,
        @NotBlank(message = "密碼不可空白") @Size(max = 72, message = "密碼不可超過 72 字元") String password) {
}
