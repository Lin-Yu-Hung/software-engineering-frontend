package com.booking.security;

import com.booking.auth.RefreshSessionRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SessionJwtValidator implements OAuth2TokenValidator<Jwt> {
    private final RefreshSessionRepository sessions;
    private final Clock clock;

    public SessionJwtValidator(RefreshSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            UUID sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
            boolean active = sessions.findById(sessionId)
                    .filter(session -> session.isActive(clock.instant()))
                    .filter(session -> session.getUser().isEnabled())
                    .filter(session -> session.getUser().getId().equals(userId)).isPresent();
            if (active) return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException | NullPointerException exception) {
            // Malformed identity claims are invalid authentication, not server errors.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Session is not active", null));
    }
}
