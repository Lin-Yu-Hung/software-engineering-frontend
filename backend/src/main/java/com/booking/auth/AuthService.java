package com.booking.auth;

import com.booking.common.BusinessException;
import com.booking.member.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserAccountRepository users;
    private final RefreshSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final long accessSeconds;
    private final long refreshDays;
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserAccountRepository users, RefreshSessionRepository sessions,
            PasswordEncoder passwords, JwtEncoder encoder, Clock clock,
            @Value("${app.security.jwt-issuer}") String issuer,
            @Value("${app.security.access-token-seconds}") long accessSeconds,
            @Value("${app.security.refresh-token-days}") long refreshDays) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.accessSeconds = accessSeconds;
        this.refreshDays = refreshDays;
        dummyHash = passwords.encode("not-a-user-password");
    }

    @Transactional
    public MeResponse register(RegisterRequest request) {
        return createAccount(request, Role.MEMBER);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public MeResponse createAdmin(RegisterRequest request) {
        return createAccount(request, Role.ADMIN);
    }

    private MeResponse createAccount(RegisterRequest request, Role role) {
        checkPasswordBytes(request.password());
        String email = normalize(request.email());
        if (users.findByEmail(email).isPresent()) throw duplicateEmail();
        try {
            UserAccount user = users.saveAndFlush(new UserAccount(email, passwords.encode(request.password()),
                    request.name().strip(), request.phone().strip(), role, clock.instant()));
            return MeResponse.from(user);
        } catch (DataIntegrityViolationException exception) {
            // The unique key also protects simultaneous registration attempts.
            throw duplicateEmail();
        }
    }

    @Transactional
    public SessionResult login(LoginRequest request) {
        checkPasswordBytes(request.password());
        UserAccount user = users.findByEmail(normalize(request.email())).orElse(null);
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null || !user.isEnabled()) throw invalidCredentials();
        String secret = newSecret();
        RefreshSession session = sessions.save(new RefreshSession(user, hash(secret),
                clock.instant().plus(Duration.ofDays(refreshDays)), clock.instant()));
        return issue(session, secret);
    }

    @Transactional
    public SessionResult refresh(String token) {
        ParsedToken parsed = parse(token);
        RefreshSession session = sessions.findLocked(parsed.id()).orElseThrow(AuthService::invalidCredentials);
        if (!session.isActive(clock.instant()) || !session.getUser().isEnabled()
                || !matchesHash(parsed.secret(), session.getTokenHash())) throw invalidCredentials();
        String secret = newSecret();
        session.rotate(hash(secret));
        return issue(session, secret);
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        ParsedToken parsed;
        try { parsed = parse(token); } catch (BusinessException exception) { return; }
        sessions.findLocked(parsed.id()).filter(session -> matchesHash(parsed.secret(), session.getTokenHash()))
                .ifPresent(session -> session.revoke(clock.instant()));
    }

    private SessionResult issue(RefreshSession session, String secret) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer)
                .subject(session.getUser().getId().toString()).issuedAt(clock.instant())
                .expiresAt(clock.instant().plusSeconds(accessSeconds)).id(UUID.randomUUID().toString())
                .claim("sid", session.getId().toString()).build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new SessionResult(new TokenResponse(accessToken, "Bearer", accessSeconds,
                MeResponse.from(session.getUser())), session.getId() + "." + secret, session.getExpiresAt());
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String secret) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static boolean matchesHash(String secret, String storedHash) {
        return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    private static ParsedToken parse(String token) {
        if (token == null || token.length() > 200) throw invalidCredentials();
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2 || parts[1].length() != 43) throw invalidCredentials();
        try { return new ParsedToken(UUID.fromString(parts[0]), parts[1]); }
        catch (IllegalArgumentException exception) { throw invalidCredentials(); }
    }

    private static void checkPasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密碼 UTF-8 長度不可超過 72 bytes");
        }
    }

    private static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private static BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登入資料或登入憑證無效");
    }
    private static BusinessException duplicateEmail() {
        return new BusinessException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "此 Email 已經註冊");
    }
    private record ParsedToken(UUID id, String secret) { }
}
