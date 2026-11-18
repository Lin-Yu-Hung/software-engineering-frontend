package com.booking.auth;

import java.time.Instant;

// Internal result only: refreshToken is written into HttpOnly cookie, never JSON.
public record SessionResult(TokenResponse response, String refreshToken, Instant expiresAt) {
}
