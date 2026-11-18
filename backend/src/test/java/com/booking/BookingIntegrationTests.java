package com.booking;

import com.booking.booking.*;
import com.booking.booking.BookingModels.*;
import com.booking.common.BusinessException;
import com.booking.pricing.*;
import com.booking.pricing.PricingModels.*;
import com.jayway.jsonpath.JsonPath;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class BookingIntegrationTests extends BookingApiTestSupport {
    @Autowired PricingService pricing;
    @Autowired PaymentConfirmationService payments;

    @Test void holdShowsPricesAndPublicOccupancyWithoutPrivateData() throws Exception {
        var held=hold(token,COURT,start); status(held,201);
        assertThat(JsonPath.<String>read(held.body(),"$.status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Integer>read(held.body(),"$.totalAmount")).isGreaterThan(0);
        var grid=send("GET","/availability?date="+start.toLocalDate()+"&courtId="+COURT,null,null,null);
        assertThat(JsonPath.<String>read(grid.body(),"$.slots[10].status")).isEqualTo("HOLDING");
        assertThat(grid.body()).doesNotContain(member.toString(),"customer","phone","holdId");
        status(send("DELETE","/holds/"+id(held),null,token,null),204);
        status(send("DELETE","/holds/"+id(held),null,token,null),204);
        var free=send("GET","/availability?date="+start.toLocalDate()+"&courtId="+COURT,null,null,null);
        assertThat(JsonPath.<String>read(free.body(),"$.slots[10].status")).isEqualTo("AVAILABLE");
    }
    @Test void duplicateClicksReplaySameHoldAndChangedBodyConflicts() throws Exception {
        String key=key(); var body=Map.of("items",List.of(slot(COURT,start)));
        var first=send("POST","/holds",body,token,key); status(first,201);
        var again=send("POST","/holds",body,token,key); status(again,201);
        assertThat(again.body()).isEqualTo(first.body());
        code(send("POST","/holds",Map.of("items",List.of(slot(COURT2,start))),token,key),409,"IDEMPOTENCY_CONFLICT");
        code(hold(token,COURT2,start),409,"ACTIVE_HOLD_EXISTS");
    }
    @Test void concurrentMembersCannotHoldSameSlot() {
        var body=Map.of("items",List.of(slot(COURT,start)));
        var a=client.sendAsync(request("POST","/holds",body,token,key()),java.net.http.HttpResponse.BodyHandlers.ofString());
        var b=client.sendAsync(request("POST","/holds",body,otherToken,key()),java.net.http.HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a,b).join();
        assertThat(List.of(a.join().statusCode(),b.join().statusCode())).containsExactlyInAnyOrder(201,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot_claims WHERE court_id=? AND start_at=?",Long.class,COURT,start)).isEqualTo(1);
    }
    @Test void multiSlotRequestsAreAllOrNothing() throws Exception {
        status(hold(token,COURT,start),201);
        var body=Map.of("items",List.of(slot(COURT2,start),slot(COURT,start)));
        code(send("POST","/holds",body,otherToken,key()),409,"SLOT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_holds WHERE member_id=?",Long.class,other)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot_claims WHERE court_id=? AND start_at=?",Long.class,COURT2,start)).isZero();
    }
    @Test void invalidTimesDuplicateSlotsUnknownFieldsAndMissingKeyAreRejected() throws Exception {
        status(hold(token,COURT,start.plusMinutes(30)),400);
        status(hold(token,COURT,OffsetDateTime.now().minusHours(1)),400);
        code(send("POST","/holds",Map.of("items",List.of(slot(COURT,start),slot(COURT,start.withOffsetSameInstant(ZoneOffset.UTC)))),token,key()),400,"DUPLICATE_SLOT");
        code(hold(token,COURT,start.withHour(2)),409,"VENUE_CLOSED");
        status(send("POST","/holds",Map.of("items",List.of(slot(COURT,start)),"totalAmount",1),token,key()),400);
        status(send("POST","/holds",Map.of("items",List.of(slot(COURT,start))),token,null),400);
        status(send("POST","/holds",Map.of("items",List.of(slot(COURT,start))),token,"bad"),400);
    }
    @Test void ownershipAndRoleBoundariesAreEnforced() throws Exception {
        UUID hold=createdHold();
        status(send("GET","/holds/"+hold,null,otherToken,null),404);
        status(send("DELETE","/holds/"+hold,null,otherToken,null),404);
        status(send("GET","/holds/"+hold,null,null,null),401);
        status(send("POST","/holds",Map.of("items",List.of(slot(COURT2,start))),staffToken,key()),403);
        var response=send("POST","/orders",Map.of("holdId",hold,"customer",customer(),"paymentMethod","CREDIT_CARD"),token,key()); status(response,201);
        UUID order=id(response);
        status(send("GET","/orders/"+order,null,otherToken,null),404);
        status(send("POST","/orders/"+order+"/cancellations",Map.of("reason","Other"),otherToken,null),404);
        assertThat(JsonPath.<List<?>>read(send("GET","/orders",null,otherToken,null).body(),"$.content")).isEmpty();
    }
    @Test void orderConsumesHoldOnceWithoutExtendingExpiry() throws Exception {
        var held=hold(token,COURT,start); status(held,201); UUID hold=id(held); String key=key();
        var body=Map.of("holdId",hold,"customer",customer(),"paymentMethod","CREDIT_CARD");
        var order=send("POST","/orders",body,token,key); status(order,201);
        assertThat(JsonPath.<String>read(order.body(),"$.expiresAt")).isEqualTo(JsonPath.<String>read(held.body(),"$.expiresAt"));
        assertThat(JsonPath.<String>read(order.body(),"$.status")).isEqualTo("PENDING_PAYMENT");
        assertThat(id(send("POST","/orders",body,token,key))).isEqualTo(id(order));
        code(send("POST","/orders",body,token,key()),409,"HOLD_NOT_ACTIVE");
        code(send("DELETE","/holds/"+hold,null,token,null),409,"HOLD_CONSUMED");
        assertThat(JsonPath.<String>read(send("GET","/availability?date="+start.toLocalDate()+"&courtId="+COURT,null,null,null).body(),"$.slots[10].status")).isEqualTo("BOOKED");
    }
    @Test void holdAndPendingOrderExpirationReleaseSlots() throws Exception {
        UUID held=createdHold();
        var before=OffsetDateTime.now().minusMinutes(1);
        jdbc.update("UPDATE booking_holds SET expires_at=? WHERE id=?",before,held);
        jdbc.update("UPDATE slot_claims SET expires_at=? WHERE hold_id=?",before,held);
        assertThat(JsonPath.<String>read(send("GET","/holds/"+held,null,token,null).body(),"$.status")).isEqualTo("EXPIRED");
        UUID order=createdOrder();
        jdbc.update("UPDATE booking_orders SET expires_at=? WHERE id=?",before,order);
        jdbc.update("UPDATE slot_claims SET expires_at=? WHERE order_id=?",before,order);
        assertThat(JsonPath.<String>read(send("GET","/orders/"+order,null,token,null).body(),"$.status")).isEqualTo("EXPIRED");
        status(hold(otherToken,COURT,start),201);
        assertThatThrownBy(() -> payments.confirmVerifiedPayment(order,350,"late-payment"))
                .isInstanceOf(BusinessException.class);
    }
    @Test void pricingChangesDoNotRepriceHeldOrOrderedItems() throws Exception {
        var original=pricing.current(); var held=hold(token,COURT,start); status(held,201);
        try {
            var rules=original.rules().stream().map(r -> new Rule(r.courtId(),r.dayType(),r.startHour(),r.endHour(),999L,r.priceType(),r.priority())).toList();
            pricing.replace(new PricingRequest(original.version(),rules));
            var order=send("POST","/orders",Map.of("holdId",id(held),"customer",customer(),"paymentMethod","CREDIT_CARD"),token,key()); status(order,201);
            assertThat(JsonPath.<Number>read(order.body(),"$.totalAmount")).isEqualTo(JsonPath.<Number>read(held.body(),"$.totalAmount"));
            assertThat(JsonPath.<Number>read(order.body(),"$.pricingVersion")).isEqualTo(JsonPath.<Number>read(held.body(),"$.pricingVersion"));
        } finally { pricing.replace(new PricingRequest(pricing.current().version(),original.rules())); }
    }
    @Test void paymentConfirmationChecksAmountIsIdempotentAndCancellationReleasesInventory() throws Exception {
        UUID id=createdOrder(); long amount=jdbc.queryForObject("SELECT total_amount FROM booking_orders WHERE id=?",Long.class,id);
        assertThatThrownBy(() -> payments.confirmVerifiedPayment(id,amount+1,"payment-"+id)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo("PAYMENT_AMOUNT_MISMATCH");
        var paid=payments.confirmVerifiedPayment(id,amount,"payment-"+id);
        assertThat(paid.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(payments.confirmVerifiedPayment(id,amount,"payment-"+id).version()).isEqualTo(paid.version());
        var cancelled=send("POST","/orders/"+id+"/cancellations",Map.of("reason","Demo cancel"),token,null); status(cancelled,200);
        assertThat(JsonPath.<String>read(cancelled.body(),"$.paymentStatus")).isEqualTo("PAID");
        assertThat(JsonPath.<String>read(cancelled.body(),"$.refundStatus")).isEqualTo("PENDING_REVIEW");
        status(hold(otherToken,COURT,start),201);
    }
    @Test void unpaidCancellationIsIdempotentAndOrdersArePaginated() throws Exception {
        UUID order=createdOrder();
        var response=send("GET","/orders?scope=upcoming&page=0&size=1",null,token,null); status(response,200);
        assertThat(JsonPath.<List<?>>read(response.body(),"$.content")).hasSize(1);
        var cancel=Map.of("reason","不付款取消");
        status(send("POST","/orders/"+order+"/cancellations",cancel,token,null),200);
        assertThat(send("POST","/orders/"+order+"/cancellations",cancel,token,null).body()).isEqualTo(send("GET","/orders/"+order,null,token,null).body());
        assertThat(JsonPath.<String>read(send("GET","/orders/"+order,null,token,null).body(),"$.refundStatus")).isEqualTo("NOT_REQUIRED");
        assertThat(JsonPath.<List<?>>read(send("GET","/orders?scope=past",null,token,null).body(),"$.content")).hasSize(1);
        status(send("GET","/orders?page=-1",null,token,null),400);
        status(send("GET","/orders?size=101",null,token,null),400);
    }
    @Test void operatingAndCourtChangesCannotEraseActiveBookings() throws Exception {
        createdHold();
        long version=jdbc.queryForObject("SELECT version FROM courts WHERE id=?",Long.class,COURT);
        code(send("PATCH","/backoffice/courts/"+COURT,Map.of("expectedVersion",version,"status","DISABLED"),adminToken,null),409,"COURT_OCCUPIED");
        code(send("POST","/backoffice/calendar-exceptions",Map.of("date",start.toLocalDate(),"label","Close","dayType","HOLIDAY","periods",List.of()),adminToken,null),409,"OCCUPIED_SCHEDULE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_exceptions WHERE date=?",Long.class,start.toLocalDate())).isZero();
    }
}
