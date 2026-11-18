package com.booking.neon;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/** Adapts the CLI's server-only Postgres URLs to the existing JDBC/Flyway stack. */
public final class NeonDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (context.getEnvironment().matchesProfiles("neon")) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("neonDatabase", properties(context.getEnvironment())));
        }
    }

    static Map<String,Object> properties(Environment environment) {
        var pooled = parse(environment.getProperty("DATABASE_URL"), "DATABASE_URL");
        var direct = parse(environment.getProperty("DATABASE_URL_UNPOOLED"), "DATABASE_URL_UNPOOLED");
        if (direct.host().contains("-pooler.")) {
            throw new IllegalStateException("DATABASE_URL_UNPOOLED must be a direct connection for Flyway");
        }
        if (!pooled.host().replace("-pooler.", ".").equals(direct.host())
                || !pooled.path().equals(direct.path()) || !pooled.user().equals(direct.user())) {
            throw new IllegalStateException("Neon pooled and direct URLs must refer to the same endpoint, database and role");
        }
        return Map.of(
                "spring.datasource.url", pooled.jdbcUrl(),
                "spring.datasource.username", pooled.user(),
                "spring.datasource.password", pooled.password(),
                "spring.flyway.url", direct.jdbcUrl(),
                "spring.flyway.user", direct.user(),
                "spring.flyway.password", direct.password());
    }

    // Never include either URL or an underlying parser exception in an error: both can contain credentials.
    private static Connection parse(String raw, String key) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Missing " + key + " for the neon profile; run neon deploy");
        try {
            URI uri = URI.create(raw);
            if (!Set.of("postgres", "postgresql").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() == null || uri.getRawPath() == null
                    || uri.getRawPath().length() <= 1 || uri.getFragment() != null) throw new IllegalArgumentException();
            String[] credentials = uri.getRawUserInfo().split(":", 2);
            if (credentials.length != 2 || credentials[0].isBlank() || credentials[1].isBlank()) throw new IllegalArgumentException();
            Map<String,String> query = new LinkedHashMap<>();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                if (name.equals("user") || name.equals("password")) throw new IllegalArgumentException();
                if (name.equals("channel_binding")) name = "channelBinding";
                query.put(name, pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
            }
            query.putIfAbsent("sslmode", "require");
            if (!Set.of("require", "verify-ca", "verify-full").contains(query.get("sslmode"))) throw new IllegalArgumentException();
            String params = query.entrySet().stream()
                    .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .reduce((a,b) -> a + "&" + b).orElseThrow();
            String host = uri.getHost();
            String jdbc = "jdbc:postgresql://" + host + ":" + (uri.getPort() == -1 ? 5432 : uri.getPort())
                    + uri.getRawPath() + "?" + params;
            return new Connection(jdbc, decodeCredential(credentials[0]), decodeCredential(credentials[1]), host, uri.getRawPath());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid " + key + "; expected a Postgres URL with credentials and TLS enabled");
        }
    }
    private static String decodeCredential(String value) {
        // '+' in a URI user-info is literal, unlike '+' in a form-encoded query.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record Connection(String jdbcUrl,String user,String password,String host,String path) {
        @Override public String toString() { return "NeonConnection[credentials redacted]"; }
    }
}
