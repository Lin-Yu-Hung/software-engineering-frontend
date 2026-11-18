package com.booking;

import com.jayway.jsonpath.JsonPath;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BookingApiTestSupport {
    protected static final UUID COURT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    protected static final UUID COURT2=UUID.fromString("00000000-0000-0000-0000-000000000002");
    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected PasswordEncoder passwords;
    protected final HttpClient client=HttpClient.newHttpClient();
    protected final List<UUID> users=new ArrayList<>();
    protected UUID member,other;
    protected String token,otherToken,adminToken,staffToken;
    protected OffsetDateTime start;
    @BeforeAll void setupActors() throws Exception {
        if (!Objects.requireNonNull(jdbc.queryForObject("SELECT current_database()",String.class)).endsWith("_test"))
            throw new IllegalStateException("Isolated database required");
        member=seed("MEMBER"); token=login(member);
        other=seed("MEMBER"); otherToken=login(other);
        adminToken=login(seed("ADMIN")); staffToken=login(seed("FRONT_DESK"));
        start=LocalDate.now(ZoneId.of("Asia/Taipei")).plusDays(2).atTime(10,0).atZone(ZoneId.of("Asia/Taipei")).toOffsetDateTime();
    }
    @AfterEach void removeOwnedInventory() {
        for (UUID user:users) {
            jdbc.update("DELETE FROM booking_orders WHERE created_by=?",user);
            jdbc.update("DELETE FROM booking_holds WHERE member_id=?",user);
            jdbc.update("DELETE FROM booking_request_keys WHERE actor_id=?",user);
        }
    }
    @AfterAll void removeActors() { for (UUID id:users) jdbc.update("DELETE FROM users WHERE id=?",id); }
    private UUID seed(String role) {
        UUID id=UUID.randomUUID(); users.add(id);
        jdbc.update("INSERT INTO users(id,email,password_hash,name,phone) VALUES (?,?,?,?,?)",id,"booking-"+id+"@integration.test",passwords.encode("BookingDemo123!"),"Booking test","0912345678");
        jdbc.update("INSERT INTO user_roles VALUES (?,?)",id,role); return id;
    }
    private String login(UUID id) throws Exception {
        var response=send("POST","/auth/login",Map.of("email","booking-"+id+"@integration.test","password","BookingDemo123!"),null,null);
        status(response,200); return JsonPath.read(response.body(),"$.accessToken");
    }
    protected String key() { return UUID.randomUUID().toString(); }
    protected Map<String,Object> slot(UUID court,OffsetDateTime at) { return Map.of("courtId",court,"startAt",at); }
    protected Map<String,Object> customer() { return Map.of("name","測試客人","phone","0912345678"); }
    protected HttpResponse<String> hold(String auth,UUID court,OffsetDateTime at) throws Exception { return send("POST","/holds",Map.of("items",List.of(slot(court,at))),auth,key()); }
    protected UUID createdHold() throws Exception { var response=hold(token,COURT,start); status(response,201); return id(response); }
    protected UUID createdOrder() throws Exception {
        var response=send("POST","/orders",Map.of("holdId",createdHold(),"customer",customer(),"paymentMethod","CREDIT_CARD"),token,key());
        status(response,201); return id(response);
    }
    protected UUID id(HttpResponse<String> response) { return UUID.fromString(JsonPath.read(response.body(),"$.id")); }
    protected HttpResponse<String> send(String method,String path,Object body,String auth,String key) throws Exception {
        return client.send(request(method,path,body,auth,key),HttpResponse.BodyHandlers.ofString());
    }
    protected HttpRequest request(String method,String path,Object body,String auth,String key) {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(20));
        if (auth!=null) builder.header("Authorization","Bearer "+auth);
        if (key!=null) builder.header("Idempotency-Key",key);
        if (body!=null) builder.header("Content-Type","application/json");
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
    }
    protected void status(HttpResponse<String> response,int status) { assertThat(response.statusCode()).withFailMessage("Expected %s got %s: %s",status,response.statusCode(),response.body()).isEqualTo(status); }
    protected void code(HttpResponse<String> response,int status,String code) { status(response,status); assertThat(JsonPath.<String>read(response.body(),"$.code")).isEqualTo(code); }
}
