package com.booking.auth;

import com.booking.member.MeResponse;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, MeResponse user) {
}
