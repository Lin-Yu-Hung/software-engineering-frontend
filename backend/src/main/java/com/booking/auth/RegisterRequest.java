package com.booking.auth;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public record RegisterRequest(
        @NotBlank(message = "Email 不可空白") @Email(message = "Email 格式不正確")
        @Size(max = 254, message = "Email 不可超過 254 字元") String email,
        @Schema(description = "密碼需 8–72 字元且 UTF-8 不超過 72 bytes；無強制大小寫、數字或特殊符號組合要求", example = "MemberDemo123!")
        @NotBlank(message = "密碼不可空白")
        @Size(min = 8, max = 72, message = "密碼長度需為 8–72 字元，且 UTF-8 不可超過 72 bytes") String password,
        @NotBlank(message = "姓名不可空白") @Size(max = 100, message = "姓名不可超過 100 字元") String name,
        @NotBlank(message = "電話不可空白")
        @Pattern(regexp = "^[0-9+()\\- ]{6,20}$", message = "電話需為 6–20 字元，只能包含數字、+、括號、連字號與空白") String phone) {
}
