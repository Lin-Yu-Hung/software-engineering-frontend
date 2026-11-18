package com.booking;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthAndCourtIntegrationTests {
    private static final String PASSWORD = "MemberDemo123!";
    private static final String ADMIN = "phase2-admin@integration.test";
    private static final String STAFF = "phase2-staff@integration.test";
    private static final String FIRST = "00000000-0000-0000-0000-000000000001";
    private static final String SECOND = "00000000-0000-0000-0000-000000000002";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> ownedCourts = new ArrayList<>();
    private String email;

    @BeforeAll
    void setup() {
        if (!Objects.requireNonNull(jdbc.queryForObject("SELECT current_database()", String.class)).endsWith("_test"))
            throw new IllegalStateException("Tests require an isolated _test database");
        seed(ADMIN, "ADMIN");
        seed(STAFF, "FRONT_DESK");
    }

    @BeforeEach
    void uniqueMember() { email = "phase2-" + UUID.randomUUID() + "@integration.test"; }

    @AfterEach
    void cleanupCourts() {
        for (String id : ownedCourts) jdbc.update("DELETE FROM courts WHERE id = ?::uuid", id);
        ownedCourts.clear();
    }

    @AfterAll
    void cleanupUsers() {
        jdbc.update("DELETE FROM users WHERE email LIKE 'phase2-%@integration.test'");
    }

    @Test
    void registrationStoresHashAndRejectsCaseInsensitiveDuplicates() throws Exception {
        var response = register(email, PASSWORD);
        assertStatus(response, 201);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.roles")).containsExactly("MEMBER");
        assertThat(response.body()).doesNotContain(PASSWORD, "passwordHash", "password_hash");
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertCode(register(email.toUpperCase(Locale.ROOT), PASSWORD), 409, "EMAIL_ALREADY_EXISTS");
    }

    @Test
    void registrationCannotAssignRolesAndRejectsInvalidPasswords() throws Exception {
        var payload = registration(email, PASSWORD);
        payload.put("roles", List.of("ADMIN"));
        assertCode(send("POST", "/api/v1/auth/register", payload, null, null, false), 400, "INVALID_BODY");
        assertStatus(register(email, "short"), 400);
        assertCode(register(email, "羽".repeat(25)), 400, "INVALID_PASSWORD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email)).isZero();
    }

    @Test
    void memberReadsAndEditsOnlyOwnProfile() throws Exception {
        registerMember();
        assertCode(send("GET", "/api/v1/me", null, null, null, false), 401, "UNAUTHENTICATED");
        Session member = login(email);
        var updated = send("PATCH", "/api/v1/me", Map.of("name", "新名字", "phone", "0987654321"), member.access(), null, false);
        assertStatus(updated, 200);
        assertThat(JsonPath.<String>read(updated.body(), "$.name")).isEqualTo("新名字");
        assertThat(JsonPath.<String>read(updated.body(), "$.email")).isEqualTo(email);
        assertStatus(send("PATCH", "/api/v1/me", Map.of("roles", List.of("ADMIN")), member.access(), null, false), 400);
        assertStatus(send("PATCH", "/api/v1/me", Map.of("email", ADMIN), member.access(), null, false), 400);
        assertCode(send("PATCH", "/api/v1/me", Map.of(), member.access(), null, false), 400, "EMPTY_UPDATE");
    }

    @Test
    void badCredentialsAndTamperedTokensAreRejected() throws Exception {
        registerMember();
        assertCode(send("POST", "/api/v1/auth/login", Map.of("email", email, "password", "wrong-password"), null, null, false), 401, "INVALID_CREDENTIALS");
        assertStatus(send("POST", "/api/v1/auth/login", Map.of("email", "missing@integration.test", "password", PASSWORD), null, null, false), 401);
        assertStatus(send("GET", "/api/v1/me", null, "invalid.jwt.token", null, false), 401);
    }

    @Test
    void refreshRotatesCookieAndLogoutRevokesAccess() throws Exception {
        registerMember();
        Session first = login(email);
        var refreshed = send("POST", "/api/v1/auth/refresh", null, null, first.cookie(), true);
        assertStatus(refreshed, 200);
        Session second = session(refreshed);
        assertThat(second.cookie()).isNotEqualTo(first.cookie());
        assertThat(refreshed.headers().firstValue("set-cookie").orElseThrow()).contains("HttpOnly", "SameSite=Lax", "Path=/api/v1/auth");
        assertThat(refreshed.headers().firstValue("cache-control")).contains("no-store");
        assertStatus(send("POST", "/api/v1/auth/refresh", null, null, first.cookie(), true), 401);
        assertStatus(send("POST", "/api/v1/auth/logout", null, null, second.cookie(), true), 204);
        assertStatus(send("GET", "/api/v1/me", null, first.access(), null, false), 401);
        assertStatus(send("GET", "/api/v1/me", null, second.access(), null, false), 401);
        assertStatus(send("POST", "/api/v1/auth/refresh", null, null, second.cookie(), true), 401);
        assertStatus(send("POST", "/api/v1/auth/logout", null, null, null, true), 204);
    }

    @Test
    void cookieOperationsRequireCustomHeaderAndTrustedOrigin() throws Exception {
        registerMember();
        Session member = login(email);
        assertCode(send("POST", "/api/v1/auth/refresh", null, null, member.cookie(), false), 403, "CSRF_REJECTED");
        assertStatus(send("POST", "/api/v1/auth/logout", null, null, member.cookie(), false), 403);
        var request = request("POST", "/api/v1/auth/refresh", null, null, member.cookie(), true)
                .header("Origin", "https://untrusted.example").build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var preflight = HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
                .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Requested-With,Content-Type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertStatus(response, 200);
        assertThat(response.headers().firstValue("access-control-allow-credentials")).contains("true");
    }

    @Test
    void concurrentRefreshAllowsOnlyOneRotation() throws Exception {
        registerMember();
        Session member = login(email);
        HttpRequest request = request("POST", "/api/v1/auth/refresh", null, null, member.cookie(), true).build();
        var a = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var b = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a, b).join();
        assertThat(List.of(a.join().statusCode(), b.join().statusCode())).containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void expiredTokensAndDisabledUsersAreRejected() throws Exception {
        registerMember();
        Session member = login(email);
        Jwt jwt = decoder.decode(member.access());
        JwtClaimsSet expired = JwtClaimsSet.builder().issuer(jwt.getClaimAsString("iss")).subject(jwt.getSubject())
                .issuedAt(Instant.now().minusSeconds(400)).expiresAt(Instant.now().minusSeconds(200))
                .claim("sid", jwt.getClaimAsString("sid")).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), expired)).getTokenValue();
        assertStatus(send("GET", "/api/v1/me", null, token, null, false), 401);
        jdbc.update("UPDATE users SET enabled = false WHERE email = ?", email);
        assertStatus(send("GET", "/api/v1/me", null, member.access(), null, false), 401);
        assertStatus(send("POST", "/api/v1/auth/refresh", null, null, member.cookie(), true), 401);
        assertStatus(send("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, null, false), 401);
    }

    @Test
    void onlyAdminMayManageCourts() throws Exception {
        registerMember();
        Session member = login(email);
        Session staff = login(STAFF);
        assertStatus(send("GET", "/api/v1/backoffice/courts", null, null, null, false), 401);
        for (Session forbidden : List.of(member, staff)) {
            assertCode(send("GET", "/api/v1/backoffice/courts", null, forbidden.access(), null, false), 403, "FORBIDDEN");
            assertStatus(send("POST", "/api/v1/backoffice/courts", courtBody("Denied"), forbidden.access(), null, false), 403);
            assertStatus(send("PATCH", "/api/v1/backoffice/courts/" + FIRST, Map.of("status", "DISABLED", "expectedVersion", 0), forbidden.access(), null, false), 403);
            assertStatus(send("DELETE", "/api/v1/backoffice/courts/" + FIRST + "?expectedVersion=0", null, forbidden.access(), null, false), 403);
        }
        assertStatus(send("GET", "/api/v1/backoffice/courts", null, login(ADMIN).access(), null, false), 200);
    }

    @Test
    void adminCrudPreservesHistoryAndPubliclyHidesInactiveCourts() throws Exception {
        Session admin = login(ADMIN);
        var created = createCourt(admin);
        String id = JsonPath.read(created.body(), "$.id");
        assertThat(created.headers().firstValue("location")).contains("/api/v1/backoffice/courts/" + id);
        assertStatus(send("GET", "/api/v1/courts/" + id, null, null, null, false), 200);
        var updated = send("PATCH", "/api/v1/backoffice/courts/" + id,
                Map.of("status", "DISABLED", "name", "Renamed " + id, "expectedVersion", 0), admin.access(), null, false);
        assertStatus(updated, 200);
        long version = ((Number) JsonPath.read(updated.body(), "$.version")).longValue();
        assertThat(version).isEqualTo(1);
        assertStatus(send("GET", "/api/v1/courts/" + id, null, null, null, false), 404);
        assertStatus(send("DELETE", "/api/v1/backoffice/courts/" + id + "?expectedVersion=" + version, null, admin.access(), null, false), 204);
        assertThat(jdbc.queryForObject("SELECT status FROM courts WHERE id = ?::uuid", String.class, id)).isEqualTo("RETIRED");
        assertStatus(send("GET", "/api/v1/backoffice/courts/" + id, null, admin.access(), null, false), 200);
        assertCode(send("PATCH", "/api/v1/backoffice/courts/" + id, Map.of("name", "Reopen", "expectedVersion", 2), admin.access(), null, false), 409, "COURT_RETIRED");
    }

    @Test
    void duplicateNamesAndStaleVersionsReturnConflict() throws Exception {
        Session admin = login(ADMIN);
        var created = createCourt(admin);
        String id = JsonPath.read(created.body(), "$.id");
        String name = JsonPath.read(created.body(), "$.name");
        assertCode(send("POST", "/api/v1/backoffice/courts", courtBody(name.toUpperCase(Locale.ROOT)), admin.access(), null, false), 409, "RESOURCE_CONFLICT");
        assertStatus(send("PATCH", "/api/v1/backoffice/courts/" + id, Map.of("name", "Changed " + id, "expectedVersion", 0), admin.access(), null, false), 200);
        assertCode(send("PATCH", "/api/v1/backoffice/courts/" + id, Map.of("status", "DISABLED", "expectedVersion", 0), admin.access(), null, false), 409, "STALE_VERSION");
        assertCode(send("DELETE", "/api/v1/backoffice/courts/" + id + "?expectedVersion=0", null, admin.access(), null, false), 409, "STALE_VERSION");
    }

    @Test
    void invalidCourtInputsReturn400() throws Exception {
        Session admin = login(ADMIN);
        var invalid = courtBody(" ");
        assertStatus(send("POST", "/api/v1/backoffice/courts", invalid, admin.access(), null, false), 400);
        invalid = courtBody("Invalid"); invalid.put("status", "RETIRED");
        assertCode(send("POST", "/api/v1/backoffice/courts", invalid, admin.access(), null, false), 400, "INVALID_STATUS");
        assertStatus(send("PATCH", "/api/v1/backoffice/courts/" + FIRST, Map.of("name", "Change"), admin.access(), null, false), 400);
        assertStatus(send("DELETE", "/api/v1/backoffice/courts/" + FIRST, null, admin.access(), null, false), 400);
        assertStatus(send("DELETE", "/api/v1/backoffice/courts/" + FIRST + "?expectedVersion=-1", null, admin.access(), null, false), 400);
    }

    @Test
    void simultaneousUpdatesCannotOverwriteEachOther() throws Exception {
        Session admin = login(ADMIN);
        String id = JsonPath.read(createCourt(admin).body(), "$.id");
        HttpRequest a = request("PATCH", "/api/v1/backoffice/courts/" + id, Map.of("name", "A " + id, "expectedVersion", 0), admin.access(), null, false).build();
        HttpRequest b = request("PATCH", "/api/v1/backoffice/courts/" + id, Map.of("name", "B " + id, "expectedVersion", 0), admin.access(), null, false).build();
        var first = client.sendAsync(a, HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(b, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).join();
        assertThat(List.of(first.join().statusCode(), second.join().statusCode())).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void concurrentDisableCannotRemoveLastActiveCourt() throws Exception {
        Session admin = login(ADMIN);
        List<Map<String, Object>> original = jdbc.queryForList("SELECT id, status FROM courts");
        try {
            jdbc.update("UPDATE courts SET status = CASE WHEN id IN (?::uuid, ?::uuid) THEN 'ACTIVE' ELSE 'DISABLED' END", FIRST, SECOND);
            var a = request("PATCH", "/api/v1/backoffice/courts/" + FIRST, Map.of("status", "DISABLED", "expectedVersion", version(FIRST)), admin.access(), null, false).build();
            var b = request("PATCH", "/api/v1/backoffice/courts/" + SECOND, Map.of("status", "DISABLED", "expectedVersion", version(SECOND)), admin.access(), null, false).build();
            var first = client.sendAsync(a, HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(b, HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(first, second).join();
            assertThat(List.of(first.join().statusCode(), second.join().statusCode())).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM courts WHERE status = 'ACTIVE'", Long.class)).isEqualTo(1);
            String remaining = jdbc.queryForObject("SELECT id::text FROM courts WHERE status = 'ACTIVE'", String.class);
            assertCode(send("DELETE", "/api/v1/backoffice/courts/" + remaining + "?expectedVersion=" + version(remaining), null, admin.access(), null, false), 409, "LAST_ACTIVE_COURT");
        } finally {
            for (var row : original) jdbc.update("UPDATE courts SET status = ? WHERE id = ?", row.get("status"), row.get("id"));
        }
    }

    private long version(String id) { return jdbc.queryForObject("SELECT version FROM courts WHERE id = ?::uuid", Long.class, id); }

    private void seed(String account, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,name,phone) VALUES (?,?,?,?,?)", id, account, passwords.encode(PASSWORD), "測試角色", "0912345678");
        jdbc.update("INSERT INTO user_roles(user_id,role) VALUES (?,?)", id, role);
    }

    private Map<String, Object> registration(String account, String password) {
        return new HashMap<>(Map.of("email", account, "password", password, "name", "測試會員", "phone", "0912345678"));
    }
    private HttpResponse<String> register(String account, String password) throws Exception {
        return send("POST", "/api/v1/auth/register", registration(account, password), null, null, false);
    }
    private void registerMember() throws Exception { assertStatus(register(email, PASSWORD), 201); }
    private Session login(String account) throws Exception {
        var response = send("POST", "/api/v1/auth/login", Map.of("email", account, "password", PASSWORD), null, null, false);
        assertStatus(response, 200);
        return session(response);
    }
    private Session session(HttpResponse<String> response) {
        return new Session(JsonPath.read(response.body(), "$.accessToken"), response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0]);
    }
    private Map<String, Object> courtBody(String name) {
        return new HashMap<>(Map.of("name", name, "type", "標準 PU", "status", "ACTIVE", "displayOrder", 20));
    }
    private HttpResponse<String> createCourt(Session admin) throws Exception {
        var response = send("POST", "/api/v1/backoffice/courts", courtBody("Integration " + UUID.randomUUID()), admin.access(), null, false);
        assertStatus(response, 201);
        ownedCourts.add(JsonPath.read(response.body(), "$.id"));
        return response;
    }
    private HttpResponse<String> send(String method, String path, Object body, String token, String cookie, boolean guard) throws Exception {
        return client.send(request(method, path, body, token, cookie, guard).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder request(String method, String path, Object body, String token, String cookie, boolean guard) {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (cookie != null) builder.header("Cookie", cookie);
        if (guard) builder.header("X-Requested-With", "BookingClient");
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, publisher);
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private void assertStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).withFailMessage("Expected %s, received %s: %s", expected, response.statusCode(), response.body()).isEqualTo(expected);
    }
    private void assertCode(HttpResponse<String> response, int status, String code) {
        assertStatus(response, status);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
    }
    private record Session(String access, String cookie) { }
}
