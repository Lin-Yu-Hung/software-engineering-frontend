package com.booking.auth;

import com.booking.common.BusinessException;
import com.booking.member.MeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "會員驗證", description = "註冊、登入與安全工作階段管理")
public class AuthController {
    private static final String COOKIE = "booking_refresh";
    private final AuthService service;
    private final boolean secureCookie;
    private final String[] allowedOrigins;
    private final Clock clock;

    public AuthController(AuthService service, Clock clock,
            @Value("${app.security.secure-cookie}") boolean secureCookie,
            @Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.service = service;
        this.clock = clock;
        this.secureCookie = secureCookie;
        this.allowedOrigins = allowedOrigins;
    }

    @PostMapping("/register")
    @Operation(summary = "註冊會員", description = "使用電子郵件、密碼及基本聯絡資料建立會員帳號。")
    public ResponseEntity<MeResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "會員登入", description = "驗證會員帳密並回傳存取權杖；更新用的 refresh token 以 HttpOnly Cookie 設定。")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return response(service.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "更新登入權杖", description = "以 refresh cookie 換發新的存取權杖與 refresh cookie，舊 refresh token 會輪替失效。需帶 X-Requested-With: BookingClient。")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request,
            @CookieValue(name = COOKIE, required = false) String token) {
        checkCookieRequest(request);
        return response(service.refresh(token));
    }

    @PostMapping("/logout")
    @Operation(summary = "會員登出", description = "撤銷目前登入工作階段並清除 refresh cookie。需帶 X-Requested-With: BookingClient。")
    public ResponseEntity<Void> logout(HttpServletRequest request,
            @CookieValue(name = COOKIE, required = false) String token) {
        checkCookieRequest(request);
        service.logout(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    private ResponseEntity<TokenResponse> response(SessionResult result) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE,
                        cookie(result.refreshToken(), Duration.between(clock.instant(), result.expiresAt())))
                .body(result.response());
    }

    private String cookie(String token, Duration maxAge) {
        return ResponseCookie.from(COOKIE, token).httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/api/v1/auth").maxAge(maxAge).build().toString();
    }

    private void checkCookieRequest(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (!"BookingClient".equals(request.getHeader("X-Requested-With"))
                || (origin != null && Arrays.stream(allowedOrigins).noneMatch(origin::equals))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CSRF_REJECTED", "Cookie 請求缺少必要標頭或來源不允許");
        }
    }
}
