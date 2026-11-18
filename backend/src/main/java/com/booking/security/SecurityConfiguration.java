package com.booking.security;

import com.booking.common.ApiError;
import com.booking.member.Role;
import com.booking.member.UserAccountRepository;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class SecurityConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecretKey jwtKey(@Value("${app.security.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key.getEncoded()));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, SessionJwtValidator sessionValidator,
            @Value("${app.security.jwt-issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), sessionValidator));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(UserAccountRepository users) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var roles = new HashSet<>(users.findById(UUID.fromString(jwt.getSubject()))
                    .orElseThrow().getRoles());
            if (roles.contains(Role.ADMIN)) roles.add(Role.FRONT_DESK);
            return roles.stream().<org.springframework.security.core.GrantedAuthority>map(
                    role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        });
        return converter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter,
            ObjectMapper mapper) throws Exception {
        // Only Bearer tokens authorize business writes. Cookie-only refresh/logout
        // have an explicit custom-header and Origin guard in AuthController.
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health", "/actuator/health", "/swagger-ui.html",
                                "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/courts", "/api/v1/courts/*").permitAll()
                        .requestMatchers("/api/v1/venue", "/api/v1/availability").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        // 由綠界伺服器或付款後導回的瀏覽器呼叫：不會帶 JWT，改由 CheckMacValue 簽章驗證身分。
                        .requestMatchers("/api/v1/payments/ecpay/notify", "/api/v1/payments/ecpay/result").permitAll()
                        .requestMatchers("/api/v1/me").authenticated()
                        .requestMatchers("/api/v1/holds", "/api/v1/holds/**", "/api/v1/orders", "/api/v1/orders/**").hasRole("MEMBER")
                        .requestMatchers("/api/v1/backoffice/courts", "/api/v1/backoffice/courts/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/backoffice/orders", "/api/v1/backoffice/orders/**", "/api/v1/backoffice/timetable").hasRole("FRONT_DESK")
                        .requestMatchers("/api/v1/backoffice/blocks", "/api/v1/backoffice/blocks/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/demo/orders/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/backoffice/operating-hours", "/api/v1/backoffice/calendar-exceptions",
                                "/api/v1/backoffice/calendar-exceptions/*", "/api/v1/backoffice/pricing-rules").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setHeader("WWW-Authenticate", "Bearer");
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getOutputStream(), new ApiError("UNAUTHENTICATED",
                                    "請登入或重新取得有效憑證", request.getRequestURI(), Instant.now()));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getOutputStream(), new ApiError("FORBIDDEN",
                                    "沒有執行此操作的權限", request.getRequestURI(), Instant.now()));
                        }))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setHeader("WWW-Authenticate", "Bearer");
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getOutputStream(), new ApiError("UNAUTHENTICATED",
                                    "憑證無效或已失效", request.getRequestURI(), Instant.now()));
                        }));
        return http.build();
    }
}
