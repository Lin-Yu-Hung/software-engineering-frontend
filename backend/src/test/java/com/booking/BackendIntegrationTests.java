package com.booking;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendIntegrationTests {
    private static final String FIRST_COURT = "00000000-0000-0000-0000-000000000001";
    private static final String DISABLED_COURT = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String RETIRED_COURT = "aaaaaaaa-0000-0000-0000-000000000002";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    void addInactiveFixturesOnlyToTestDatabase() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (database == null || !database.endsWith("_test")) {
            throw new IllegalStateException("Integration tests require a database ending in _test");
        }
        jdbc.update("""
                INSERT INTO courts(id, name, type, status, display_order)
                VALUES (?::uuid, '停用測試場地', '標準 PU', 'DISABLED', 7),
                       (?::uuid, '退役測試場地', '標準 PU', 'RETIRED', 8)
                ON CONFLICT (id) DO NOTHING
                """, DISABLED_COURT, RETIRED_COURT);
    }

    @AfterAll
    void removeOwnedFixtures() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (database != null && database.endsWith("_test")) {
            jdbc.update("DELETE FROM courts WHERE id IN (?::uuid, ?::uuid)",
                    DISABLED_COURT, RETIRED_COURT);
        }
    }

    @Test
    void healthConfirmsPostgreSqlAndMigrations() throws Exception {
        HttpResponse<String> response = get("/api/v1/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("UP");
        assertThat(JsonPath.<String>read(response.body(), "$.database")).isEqualTo("UP");
        assertThat(JsonPath.<String>read(response.body(), "$.databaseName")).endsWith("_test");
        assertThat(JsonPath.<String>read(response.body(), "$.schemaVersion")).isEqualTo("8");
        assertThat(JsonPath.<Integer>read(response.body(), "$.courtCount")).isGreaterThanOrEqualTo(6);
    }

    @Test
    void listReadsSeededCourtsInOrderAndExcludesInactiveCourts() throws Exception {
        HttpResponse<String> response = get("/api/v1/courts");
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> ids = JsonPath.read(response.body(), "$[*].id");
        assertThat(ids).hasSize(6).startsWith(FIRST_COURT)
                .doesNotContain(DISABLED_COURT, RETIRED_COURT);
        assertThat(JsonPath.<String>read(response.body(), "$[0].name")).isEqualTo("第 1 場地");
    }

    @Test
    void detailsReadThePersistedCourt() throws Exception {
        HttpResponse<String> response = get("/api/v1/courts/" + FIRST_COURT);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.id")).isEqualTo(FIRST_COURT);
        assertThat(JsonPath.<String>read(response.body(), "$.type")).isEqualTo("VIP 木地板");
        assertThat(JsonPath.<Boolean>read(response.body(), "$.enabled")).isTrue();
    }

    @Test
    void unknownDisabledAndRetiredCourtsAreNotPubliclyAccessible() throws Exception {
        for (String id : List.of("ffffffff-ffff-ffff-ffff-ffffffffffff", DISABLED_COURT, RETIRED_COURT)) {
            HttpResponse<String> response = get("/api/v1/courts/" + id);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("COURT_NOT_FOUND");
        }
    }

    @Test
    void invalidCourtIdReturns400InsteadOfServerError() throws Exception {
        HttpResponse<String> response = get("/api/v1/courts/not-a-uuid");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    void repeatedMigrationDoesNotDuplicateSeedData() {
        Long before = jdbc.queryForObject("SELECT count(*) FROM courts", Long.class);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM courts", Long.class)).isEqualTo(before);
    }

    @Test
    void publicCourtEndpointRemainsReadOnly() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/v1/courts"))
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void corsAllowsFrontendOriginAndRejectsUnknownOrigin() throws Exception {
        HttpResponse<String> allowed = preflight("http://localhost:5173");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("access-control-allow-origin"))
                .contains("http://localhost:5173");
        assertThat(preflight("https://untrusted.example").statusCode()).isEqualTo(403);
    }

    @Test
    void openApiDescribesImplementedEndpoints() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/api/v1/health", "/api/v1/courts", "/api/v1/courts/{courtId}");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/courts"))
                        .timeout(Duration.ofSeconds(10)).header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
