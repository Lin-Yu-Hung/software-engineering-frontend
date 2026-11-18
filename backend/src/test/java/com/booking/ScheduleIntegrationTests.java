package com.booking;

import com.booking.pricing.PricingModels.*;
import com.booking.pricing.PricingService;
import com.booking.venue.ScheduleModels.*;
import com.booking.venue.ScheduleModels.Period;
import com.booking.venue.ScheduleService;
import com.jayway.jsonpath.JsonPath;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduleIntegrationTests {
    private static final String FIRST = "00000000-0000-0000-0000-000000000001";
    private static final String THIRD = "00000000-0000-0000-0000-000000000003";
    private static final String ADMIN = "phase3-admin@integration.test";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder passwords;
    @Autowired ScheduleService schedules;
    @Autowired PricingService prices;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<UUID> exceptionIds = new ArrayList<>();
    private HoursResponse originalHours;
    private PricingResponse originalPrices;
    private String adminToken;
    private LocalDate monday;

    @BeforeAll void setup() throws Exception {
        if (!Objects.requireNonNull(jdbc.queryForObject("SELECT current_database()", String.class)).endsWith("_test"))
            throw new IllegalStateException("Isolated test database required");
        for (String role : List.of("ADMIN", "FRONT_DESK", "MEMBER")) {
            UUID id = UUID.randomUUID();
            String email = "phase3-" + (role.equals("ADMIN") ? "admin" : role.toLowerCase(Locale.ROOT)) + "@integration.test";
            jdbc.update("INSERT INTO users(id,email,password_hash,name,phone) VALUES (?,?,?,?,?)", id, email,
                    passwords.encode("Phase3Demo123!"), "Schedule test", "0912345678");
            jdbc.update("INSERT INTO user_roles(user_id,role) VALUES (?,?)", id, role);
        }
        adminToken = login(ADMIN);
        monday = LocalDate.now(ZoneId.of("Asia/Taipei")).plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
    @BeforeEach void snapshot() { originalHours = schedules.hours(); originalPrices = prices.current(); }
    @AfterEach void restore() {
        for (UUID id : exceptionIds) {
            var rows = jdbc.queryForList("SELECT version FROM calendar_exceptions WHERE id=?", Long.class, id);
            if (!rows.isEmpty()) schedules.delete(id, rows.getFirst());
        }
        exceptionIds.clear();
        if (schedules.hours().version() != originalHours.version()) schedules.replace(new HoursRequest(
                schedules.hours().version(), originalHours.advanceBookingDays(), originalHours.days()));
        if (prices.current().version() != originalPrices.version()) prices.replace(new PricingRequest(prices.current().version(), originalPrices.rules()));
    }
    @AfterAll void cleanup() { jdbc.update("DELETE FROM users WHERE email LIKE 'phase3-%@integration.test'"); }

    @Test void venueAndGridExposeCompleteScheduleAndTaipeiTimezone() throws Exception {
        var venue = send("GET", "/venue", null, null); status(venue, 200);
        assertThat(JsonPath.<String>read(venue.body(), "$.timezone")).isEqualTo("Asia/Taipei");
        assertThat(JsonPath.<Boolean>read(venue.body(), "$.bookingEnabled")).isTrue();
        var grid = availability(monday, null); status(grid, 200);
        assertThat(JsonPath.<List<?>>read(grid.body(), "$.slots")).hasSize(144);
        assertThat(JsonPath.<List<?>>read(grid.body(), "$.slots[?(@.status == 'AVAILABLE')]")).hasSize(84);
        assertThat(JsonPath.<List<?>>read(grid.body(), "$.slots[?(@.status == 'CLOSED')]")).hasSize(60);
        assertThat(grid.body()).doesNotContain("customer", "phone", "token", "memberId");
        var one = availability(monday, FIRST);
        assertThat(slot(one, 17).get("price")).isEqualTo(350);
        assertThat(slot(one, 18).get("price")).isEqualTo(500);
        assertThat(slot(one, 22).get("price")).isNull();
        assertThat(slot(one, 8).get("startAt").toString()).endsWith("+08:00");
        var standard = availability(monday, THIRD);
        assertThat(slot(standard, 17).get("price")).isEqualTo(300);
        assertThat(slot(standard, 18).get("price")).isEqualTo(450);
        assertThat(slot(availability(monday.plusDays(5), THIRD), 8).get("price")).isEqualTo(450);
    }

    @Test void dateParametersAreValidatedAndPastOpenSlotsArePast() throws Exception {
        status(send("GET", "/availability", null, null), 400);
        status(send("GET", "/availability?date=2026-02-30", null, null), 400);
        status(send("GET", "/availability?date=" + monday + "&courtId=bad", null, null), 400);
        status(availability(monday, UUID.randomUUID().toString()), 404);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        code(availability(today.plusDays(originalHours.advanceBookingDays() + 1), null), 400, "DATE_OUT_OF_RANGE");
        code(availability(today.minusDays(31), null), 400, "DATE_OUT_OF_RANGE");
        var yesterday = availability(today.minusDays(1), FIRST);
        assertThat(JsonPath.<List<?>>read(yesterday.body(), "$.slots[?(@.status == 'AVAILABLE')]")).isEmpty();
        assertThat(JsonPath.<List<?>>read(yesterday.body(), "$.slots[?(@.status == 'PAST')]")).hasSize(14);
    }

    @Test void splitHoursAndMidnightEndAreAppliedAtomically() throws Exception {
        var request = hoursWithMonday(List.of(new Period(10, 12), new Period(14, 24)));
        status(send("PUT", "/backoffice/operating-hours", request, adminToken), 200);
        var grid = availability(monday, FIRST);
        assertThat(slot(grid, 9).get("status")).isEqualTo("CLOSED");
        assertThat(slot(grid, 10).get("status")).isEqualTo("AVAILABLE");
        assertThat(slot(grid, 12).get("status")).isEqualTo("CLOSED");
        assertThat(slot(grid, 23).get("status")).isEqualTo("AVAILABLE");
        assertThat(slot(grid, 23).get("endAt").toString()).startsWith(monday.plusDays(1).toString() + "T00:00");
        code(send("PUT", "/backoffice/operating-hours", request, adminToken), 409, "STALE_VERSION");
        assertThat(schedules.hours().days().getFirst().periods()).containsExactly(new Period(10, 12), new Period(14, 24));
    }

    @Test void invalidWeeklySetsDoNotChangePersistedHours() throws Exception {
        var repeated = Collections.nCopies(7, new DayHours(1, List.of(new Period(8, 22))));
        code(send("PUT", "/backoffice/operating-hours", new HoursRequest(originalHours.version(), 30, repeated), adminToken), 400, "INVALID_WEEK");
        code(send("PUT", "/backoffice/operating-hours", hoursWithMonday(List.of(new Period(8, 12), new Period(11, 14))), adminToken), 400, "INVALID_PERIODS");
        code(send("PUT", "/backoffice/operating-hours", hoursWithMonday(List.of(new Period(12, 8))), adminToken), 400, "INVALID_PERIODS");
        status(send("PUT", "/backoffice/operating-hours", hoursWithMonday(List.of(new Period(-1, 24))), adminToken), 400);
        status(send("PUT", "/backoffice/operating-hours", new HoursRequest(originalHours.version(), 0, originalHours.days()), adminToken), 400);
        assertThat(schedules.hours()).isEqualTo(originalHours);
    }

    @Test void exceptionsOverrideWeeklyClosureAndUseHolidayPrice() throws Exception {
        status(send("PUT", "/backoffice/operating-hours", hoursWithMonday(List.of()), adminToken), 200);
        assertThat(JsonPath.<List<?>>read(availability(monday, FIRST).body(), "$.slots[?(@.status == 'AVAILABLE')]")).isEmpty();
        var created = createException(monday, DayType.HOLIDAY, List.of(new Period(12, 14)));
        var grid = availability(monday, FIRST);
        assertThat(JsonPath.<String>read(grid.body(), "$.scheduleSource")).isEqualTo("EXCEPTION");
        assertThat(slot(grid, 12).get("price")).isEqualTo(500);
        assertThat(slot(grid, 12).get("priceType")).isEqualTo("HOLIDAY");
        assertThat(slot(grid, 14).get("status")).isEqualTo("CLOSED");
        UUID id = UUID.fromString(JsonPath.read(created.body(), "$.id"));
        var closed = new ExceptionUpdateRequest(0L, monday, "休館", DayType.HOLIDAY, List.of());
        status(send("PATCH", "/backoffice/calendar-exceptions/" + id, closed, adminToken), 200);
        assertThat(JsonPath.<List<?>>read(availability(monday, FIRST).body(), "$.slots[?(@.status == 'CLOSED')]")).hasSize(24);
        code(send("PATCH", "/backoffice/calendar-exceptions/" + id, closed, adminToken), 409, "STALE_VERSION");
        code(send("DELETE", "/backoffice/calendar-exceptions/" + id + "?expectedVersion=0", null, adminToken), 409, "STALE_VERSION");
        status(send("DELETE", "/backoffice/calendar-exceptions/" + id + "?expectedVersion=1", null, adminToken), 204);
        assertThat(JsonPath.<String>read(availability(monday, FIRST).body(), "$.scheduleSource")).isEqualTo("WEEKLY");
    }

    @Test void exceptionDuplicateRangeAndPastDatesAreRejected() throws Exception {
        createException(monday, DayType.WEEKDAY, List.of());
        code(send("POST", "/backoffice/calendar-exceptions", new ExceptionRequest(monday, "Duplicate", DayType.HOLIDAY, List.of()), adminToken), 409, "RESOURCE_CONFLICT");
        code(send("POST", "/backoffice/calendar-exceptions", new ExceptionRequest(LocalDate.now(ZoneId.of("Asia/Taipei")).minusDays(1), "Past", DayType.HOLIDAY, List.of()), adminToken), 400, "PAST_DATE");
        status(send("GET", "/backoffice/calendar-exceptions?from=" + monday + "&to=" + monday.plusDays(366), null, adminToken), 400);
        status(send("GET", "/backoffice/calendar-exceptions?from=" + monday + "&to=" + monday.minusDays(1), null, adminToken), 400);
        var listed = send("GET", "/backoffice/calendar-exceptions?from=" + monday + "&to=" + monday, null, adminToken);
        status(listed, 200);
        assertThat(JsonPath.<List<?>>read(listed.body(), "$")).hasSize(1);
        status(send("DELETE", "/backoffice/calendar-exceptions/" + UUID.randomUUID() + "?expectedVersion=0", null, adminToken), 404);
    }

    @Test void pricePublicationChangesQuotesAndRetainsPreviousVersion() throws Exception {
        var rules = changedRules(371L);
        status(send("PUT", "/backoffice/pricing-rules", new PricingRequest(originalPrices.version(), rules), adminToken), 200);
        var grid = availability(monday, FIRST);
        assertThat(slot(grid, 17).get("price")).isEqualTo(371);
        assertThat(slot(grid, 18).get("price")).isEqualTo(500);
        assertThat(((Number)JsonPath.read(grid.body(), "$.pricingVersion")).longValue()).isEqualTo(originalPrices.version() + 1);
        assertThat(jdbc.queryForObject("SELECT price FROM pricing_rules WHERE pricing_version=? AND court_id=?::uuid AND day_type='WEEKDAY' AND start_hour=0", Long.class, originalPrices.version(), FIRST)).isEqualTo(350L);
        code(send("PUT", "/backoffice/pricing-rules", new PricingRequest(originalPrices.version(), rules), adminToken), 409, "STALE_VERSION");
    }

    @Test void invalidAmountsAmbiguitiesAndMissingFallbackRollBackEntirePublication() throws Exception {
        for (long price : List.of(-1L, 0L, 100001L, Long.MAX_VALUE))
            status(send("PUT", "/backoffice/pricing-rules", new PricingRequest(originalPrices.version(), changedRules(price)), adminToken), 400);
        String fractional = mapper.writeValueAsString(new PricingRequest(originalPrices.version(), changedRules(371L))).replace("\"price\":371", "\"price\":371.5");
        status(sendRaw("PUT", "/backoffice/pricing-rules", fractional, adminToken), 400);
        var overlap = new ArrayList<>(originalPrices.rules()); overlap.add(originalPrices.rules().getFirst());
        code(send("PUT", "/backoffice/pricing-rules", new PricingRequest(originalPrices.version(), overlap), adminToken), 400, "AMBIGUOUS_PRICING");
        var noFallback = originalPrices.rules().stream().filter(rule -> rule.courtId() != null).toList();
        code(send("PUT", "/backoffice/pricing-rules", new PricingRequest(originalPrices.version(), noFallback), adminToken), 400, "INCOMPLETE_PRICING");
        var unknown = new ArrayList<>(originalPrices.rules());
        unknown.add(new Rule(UUID.randomUUID(), DayType.HOLIDAY, 0, 24, 500L, PriceType.HOLIDAY, 0));
        code(send("PUT", "/backoffice/pricing-rules", new PricingRequest(originalPrices.version(), unknown), adminToken), 400, "UNKNOWN_COURT");
        assertThat(prices.current()).isEqualTo(originalPrices);
    }

    @Test void allConfigurationEndpointsRequireAdmin() throws Exception {
        for (String token : List.of(login("phase3-front_desk@integration.test"), login("phase3-member@integration.test"))) {
            status(send("GET", "/backoffice/operating-hours", null, token), 403);
            status(send("PUT", "/backoffice/operating-hours", hoursWithMonday(List.of()), token), 403);
            status(send("GET", "/backoffice/calendar-exceptions?from=" + monday + "&to=" + monday, null, token), 403);
            status(send("POST", "/backoffice/calendar-exceptions", new ExceptionRequest(monday, "Denied", DayType.HOLIDAY, List.of()), token), 403);
            status(send("PATCH", "/backoffice/calendar-exceptions/" + UUID.randomUUID(), new ExceptionUpdateRequest(0L, monday, "Denied", DayType.HOLIDAY, List.of()), token), 403);
            status(send("DELETE", "/backoffice/calendar-exceptions/" + UUID.randomUUID() + "?expectedVersion=0", null, token), 403);
            status(send("GET", "/backoffice/pricing-rules", null, token), 403);
            status(send("PUT", "/backoffice/pricing-rules", new PricingRequest(originalPrices.version(), originalPrices.rules()), token), 403);
        }
        status(send("GET", "/backoffice/operating-hours", null, null), 401);
    }

    @Test void concurrentHoursWritesHaveOneWinner() throws Exception {
        var request = hoursWithMonday(List.of(new Period(10, 12)));
        assertOneWinner("/backoffice/operating-hours", request);
        assertThat(schedules.hours().version()).isEqualTo(originalHours.version() + 1);
    }
    @Test void concurrentPricingWritesHaveOneWinner() throws Exception {
        assertOneWinner("/backoffice/pricing-rules", new PricingRequest(originalPrices.version(), changedRules(371L)));
        assertThat(prices.current().version()).isEqualTo(originalPrices.version() + 1);
    }

    private void assertOneWinner(String path, Object body) {
        var request = request("PUT", path, mapper.writeValueAsString(body), adminToken);
        var a = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var b = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a, b).join();
        assertThat(List.of(a.join().statusCode(), b.join().statusCode())).containsExactlyInAnyOrder(200, 409);
    }
    private List<Rule> changedRules(long price) {
        return originalPrices.rules().stream().map(rule -> UUID.fromString(FIRST).equals(rule.courtId())
                && rule.dayType() == DayType.WEEKDAY && rule.startHour() == 0
                ? new Rule(rule.courtId(), rule.dayType(), rule.startHour(), rule.endHour(), price, rule.priceType(), rule.priority()) : rule).toList();
    }
    private HoursRequest hoursWithMonday(List<Period> periods) {
        return new HoursRequest(originalHours.version(), originalHours.advanceBookingDays(), originalHours.days().stream()
                .map(day -> day.dayOfWeek() == 1 ? new DayHours(1, periods) : day).toList());
    }
    private HttpResponse<String> createException(LocalDate date, DayType dayType, List<Period> periods) throws Exception {
        var response = send("POST", "/backoffice/calendar-exceptions", new ExceptionRequest(date, "Integration", dayType, periods), adminToken);
        status(response, 201);
        exceptionIds.add(UUID.fromString(JsonPath.read(response.body(), "$.id")));
        return response;
    }
    private Map<String, Object> slot(HttpResponse<String> response, int hour) { return JsonPath.read(response.body(), "$.slots[" + hour + "]"); }
    private HttpResponse<String> availability(LocalDate date, String court) throws Exception {
        return send("GET", "/availability?date=" + date + (court == null ? "" : "&courtId=" + court), null, null);
    }
    private String login(String email) throws Exception {
        var response = send("POST", "/auth/login", Map.of("email", email, "password", "Phase3Demo123!"), null);
        status(response, 200); return JsonPath.read(response.body(), "$.accessToken");
    }
    private HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
        return sendRaw(method, path, body == null ? null : mapper.writeValueAsString(body), token);
    }
    private HttpResponse<String> sendRaw(String method, String path, String body, String token) throws Exception {
        return client.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest request(String method, String path, String body, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private void status(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).withFailMessage("Expected %s received %s: %s", expected, response.statusCode(), response.body()).isEqualTo(expected);
    }
    private void code(HttpResponse<String> response, int expected, String code) {
        status(response, expected); assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
    }
}
