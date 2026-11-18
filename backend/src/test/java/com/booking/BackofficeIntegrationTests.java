package com.booking;

import com.jayway.jsonpath.JsonPath;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class BackofficeIntegrationTests extends BookingApiTestSupport {
    @AfterEach void removeBlocks() { for(UUID actor:users) jdbc.update("DELETE FROM court_blocks WHERE created_by=?",actor); }
    private Map<String,Object> staffBody(OffsetDateTime at) {
        return Map.of("source","WALK_IN","paymentMethod","CASH","customer",customer(),"notes","電話確認","items",List.of(slot(COURT,at)));
    }
    private UUID staffOrder(OffsetDateTime at) throws Exception {
        var response=send("POST","/backoffice/orders",staffBody(at),staffToken,key());status(response,201);return id(response);
    }
    private OffsetDateTime nearby() {
        var now=ZonedDateTime.now(ZoneId.of("Asia/Taipei"));
        return (now.getHour()<21?now.toLocalDate().atTime(Math.max(8,now.getHour()+1),0):now.toLocalDate().plusDays(1).atTime(8,0)).atZone(now.getZone()).toOffsetDateTime();
    }
    @Test void staffBookingIsConfirmedUnpaidAndRetriesDoNotDuplicate() throws Exception {
        String key=key();var body=staffBody(start);var first=send("POST","/backoffice/orders",body,staffToken,key);status(first,201);
        assertThat(JsonPath.<String>read(first.body(),"$.status")).isEqualTo("CONFIRMED");
        assertThat(JsonPath.<String>read(first.body(),"$.paymentStatus")).isEqualTo("UNPAID");
        assertThat(JsonPath.<Object>read(first.body(),"$.expiresAt")).isNull();
        assertThat(send("POST","/backoffice/orders",body,staffToken,key).body()).isEqualTo(first.body());
        var changed=new HashMap<>(body);changed.put("notes","Changed");code(send("POST","/backoffice/orders",changed,staffToken,key),409,"IDEMPOTENCY_CONFLICT");
        code(hold(token,COURT,start),409,"SLOT_UNAVAILABLE");
    }
    @Test void concurrentOnlineAndStaffRequestsCannotOverbook() {
        var a=client.sendAsync(request("POST","/holds",Map.of("items",List.of(slot(COURT,start))),token,key()),HttpResponse.BodyHandlers.ofString());
        var b=client.sendAsync(request("POST","/backoffice/orders",staffBody(start),staffToken,key()),HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a,b).join();assertThat(List.of(a.join().statusCode(),b.join().statusCode())).containsExactlyInAnyOrder(201,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot_claims WHERE court_id=? AND start_at=?",Long.class,COURT,start)).isEqualTo(1);
    }
    @Test void staffBatchBookingRollsBackIfAnySlotIsOccupied() throws Exception {
        createdHold();var body=new HashMap<>(staffBody(start));body.put("items",List.of(slot(COURT2,start),slot(COURT,start)));
        code(send("POST","/backoffice/orders",body,staffToken,key()),409,"SLOT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot_claims WHERE court_id=? AND start_at=?",Long.class,COURT2,start)).isZero();
    }
    @Test void rolesAndStaffSourceAndPaymentMethodAreValidated() throws Exception {
        status(send("POST","/backoffice/orders",staffBody(start),token,key()),403);
        status(send("GET","/backoffice/timetable?date="+start.toLocalDate(),null,null,null),401);
        var body=new HashMap<>(staffBody(start));body.put("source","MEMBER");status(send("POST","/backoffice/orders",body,staffToken,key()),400);
        body.put("source","PHONE");body.put("paymentMethod","CREDIT_CARD");status(send("POST","/backoffice/orders",body,staffToken,key()),400);
        body.put("paymentMethod","ONSITE_CARD");status(send("POST","/backoffice/orders",body,adminToken,key()),201);
        status(send("GET","/backoffice/orders?size=101",null,staffToken,null),400);
    }
    @Test void timetableContainsStaffOnlyContactAndPaginatedOrders() throws Exception {
        UUID id=staffOrder(start);var grid=send("GET","/backoffice/timetable?date="+start.toLocalDate(),null,staffToken,null);status(grid,200);
        assertThat(JsonPath.<List<String>>read(grid.body(),"$.orders[*].id")).contains(id.toString());
        assertThat(JsonPath.<String>read(grid.body(),"$.orders[0].customer.phone")).isEqualTo("0912345678");
        var list=send("GET","/backoffice/orders?date="+start.toLocalDate()+"&size=1",null,adminToken,null);status(list,200);
        assertThat(JsonPath.<List<?>>read(list.body(),"$.content")).hasSize(1);
        status(send("GET","/backoffice/orders/"+id,null,token,null),403);
        assertThat(send("GET","/availability?date="+start.toLocalDate(),null,null,null).body()).doesNotContain("0912345678","電話確認",id.toString());
    }
    @Test void editableContactCannotOverrideAmountAndStaleVersionFails() throws Exception {
        UUID id=staffOrder(start);var body=Map.of("expectedVersion",0,"customer",Map.of("name","新名字","phone","0987654321"),"notes","備註更新");
        var edit=send("PATCH","/backoffice/orders/"+id,body,staffToken,null);status(edit,200);
        assertThat(JsonPath.<String>read(edit.body(),"$.customer.name")).isEqualTo("新名字");
        code(send("PATCH","/backoffice/orders/"+id,body,staffToken,null),409,"VERSION_CONFLICT");
        var invalid=new HashMap<>(body);invalid.put("totalAmount",1);status(send("PATCH","/backoffice/orders/"+id,invalid,staffToken,null),400);
    }
    @Test void checkInRetryReversalAndCancellationRemainConsistent() throws Exception {
        UUID id=staffOrder(nearby());String path="/backoffice/orders/"+id;
        var checked=send("POST",path+"/check-in",Map.of("expectedVersion",0),staffToken,null);status(checked,200);
        assertThat(JsonPath.<String>read(checked.body(),"$.checkedInAt")).isNotBlank();
        assertThat(send("POST",path+"/check-in",Map.of("expectedVersion",0),staffToken,null).body()).isEqualTo(checked.body());
        code(send("POST",path+"/cancellations",Map.of("reason","Cancel"),staffToken,null),409,"ORDER_CHECKED_IN");
        code(send("POST",path+"/check-in-reversals",Map.of("expectedVersion",0),staffToken,null),409,"VERSION_CONFLICT");
        var reversed=send("POST",path+"/check-in-reversals",Map.of("expectedVersion",1),adminToken,null);status(reversed,200);
        assertThat(JsonPath.<Object>read(reversed.body(),"$.checkedInAt")).isNull();
        status(send("POST",path+"/cancellations",Map.of("reason","Cancel"),staffToken,null),200);
        code(send("POST",path+"/check-in",Map.of("expectedVersion",3),staffToken,null),409,"ORDER_NOT_CONFIRMED");
    }
    @Test void checkInWindowAndUnpaidOnlineOrderAreGuarded() throws Exception {
        UUID distant=staffOrder(start);code(send("POST","/backoffice/orders/"+distant+"/check-in",Map.of("expectedVersion",0),staffToken,null),409,"CHECK_IN_WINDOW");
        status(send("POST","/backoffice/orders/"+distant+"/cancellations",Map.of("reason","Cleanup"),staffToken,null),200);
        UUID online=createdOrder();code(send("POST","/backoffice/orders/"+online+"/check-in",Map.of("expectedVersion",0),staffToken,null),409,"ORDER_NOT_CONFIRMED");
    }
    @Test void maintenanceSharesInventoryAndHidesReasonFromPublic() throws Exception {
        var body=Map.of("reason","內部燈具維修","items",List.of(slot(COURT,start)));String key=key();
        var block=send("POST","/backoffice/blocks",body,adminToken,key);status(block,201);
        assertThat(send("POST","/backoffice/blocks",body,adminToken,key).body()).isEqualTo(block.body());
        var grid=send("GET","/availability?date="+start.toLocalDate()+"&courtId="+COURT,null,null,null);
        assertThat(JsonPath.<String>read(grid.body(),"$.slots[10].status")).isEqualTo("MAINTENANCE");
        assertThat(grid.body()).doesNotContain("內部燈具維修",id(block).toString());
        code(hold(token,COURT,start),409,"SLOT_UNAVAILABLE");
        code(send("POST","/backoffice/orders",staffBody(start),staffToken,key()),409,"SLOT_UNAVAILABLE");
        status(send("GET","/backoffice/blocks?date="+start.toLocalDate(),null,staffToken,null),403);
        status(send("DELETE","/backoffice/blocks/"+id(block),null,adminToken,null),204);
        status(send("DELETE","/backoffice/blocks/"+id(block),null,adminToken,null),204);
        status(hold(token,COURT,start),201);
    }
    @Test void blocksCannotOverwriteOrdersAndScheduleCannotEraseBlocks() throws Exception {
        staffOrder(start);var body=Map.of("reason","Repair","items",List.of(slot(COURT2,start),slot(COURT,start)));
        code(send("POST","/backoffice/blocks",body,adminToken,key()),409,"SLOT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot_claims WHERE court_id=? AND start_at=?",Long.class,COURT2,start)).isZero();
        status(send("POST","/backoffice/blocks",Map.of("reason","Repair","items",List.of(slot(COURT2,start))),adminToken,key()),201);
        code(send("POST","/backoffice/calendar-exceptions",Map.of("date",start.toLocalDate(),"label","Close","dayType","HOLIDAY","periods",List.of()),adminToken,null),409,"OCCUPIED_SCHEDULE");
        status(send("POST","/backoffice/blocks",body,staffToken,key()),403);
    }
}
