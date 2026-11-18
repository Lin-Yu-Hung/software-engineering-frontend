package com.booking;

import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="app.demo-payments.enabled=true")
class DemoPaymentIntegrationTests extends BookingApiTestSupport {
    private long amount(UUID id) { return jdbc.queryForObject("SELECT total_amount FROM booking_orders WHERE id=?",Long.class,id); }
    private String path(UUID id) { return "/demo/orders/"+id+"/confirm-payment"; }
    @Test void demoConfirmationChecksExactAmountAndIsClearlyLabelledAndIdempotent() throws Exception {
        UUID id=createdOrder();long amount=amount(id);
        code(send("POST",path(id),Map.of("amount",amount+1),adminToken,null),409,"PAYMENT_AMOUNT_MISMATCH");
        status(send("POST",path(id),Map.of("amount",0),adminToken,null),400);
        status(send("POST",path(id),Map.of("amount",amount,"paymentStatus","PAID"),adminToken,null),400);
        var paid=send("POST",path(id),Map.of("amount",amount),adminToken,null);status(paid,200);
        assertThat(JsonPath.<String>read(paid.body(),"$.mode")).isEqualTo("DEMO");
        assertThat(JsonPath.<String>read(paid.body(),"$.order.status")).isEqualTo("CONFIRMED");
        assertThat(JsonPath.<String>read(paid.body(),"$.order.paymentStatus")).isEqualTo("PAID");
        assertThat(send("POST",path(id),Map.of("amount",amount),adminToken,null).body()).isEqualTo(paid.body());
    }
    @Test void memberAndFrontDeskCannotForgePaymentSuccess() throws Exception {
        UUID id=createdOrder();var body=Map.of("amount",amount(id));
        status(send("POST",path(id),body,token,null),403);status(send("POST",path(id),body,staffToken,null),403);
        assertThat(jdbc.queryForObject("SELECT payment_status FROM booking_orders WHERE id=?",String.class,id)).isEqualTo("UNPAID");
    }
    @Test void expiredOrCancelledOrdersCannotAcquireInventoryByLatePayment() throws Exception {
        UUID id=createdOrder();long amount=amount(id);var before=OffsetDateTime.now().minusMinutes(1);
        jdbc.update("UPDATE booking_orders SET expires_at=? WHERE id=?",before,id);jdbc.update("UPDATE slot_claims SET expires_at=? WHERE order_id=?",before,id);
        code(send("POST",path(id),Map.of("amount",amount),adminToken,null),409,"ORDER_NOT_PAYABLE");
        status(hold(otherToken,COURT,start),201);
        UUID otherOrder=id(send("POST","/orders",Map.of("holdId",jdbc.queryForObject("SELECT id FROM booking_holds WHERE member_id=? AND status='ACTIVE'",UUID.class,other),"customer",customer(),"paymentMethod","CREDIT_CARD"),otherToken,key()));
        status(send("POST","/orders/"+otherOrder+"/cancellations",Map.of("reason","Cancel before pay"),otherToken,null),200);
        code(send("POST",path(otherOrder),Map.of("amount",amount(otherOrder)),adminToken,null),409,"ORDER_NOT_PAYABLE");
    }
    @Test void paidCancellationMarksRefundReviewAndReleasesSlotWithoutClaimingRefunded() throws Exception {
        UUID id=createdOrder();long amount=amount(id);status(send("POST",path(id),Map.of("amount",amount),adminToken,null),200);
        var cancelled=send("POST","/orders/"+id+"/cancellations",Map.of("reason","Demo cancel"),token,null);status(cancelled,200);
        assertThat(JsonPath.<String>read(cancelled.body(),"$.refundStatus")).isEqualTo("PENDING_REVIEW");
        status(hold(otherToken,COURT,start),201);
        var replay=send("POST",path(id),Map.of("amount",amount),adminToken,null);status(replay,200);
        assertThat(JsonPath.<String>read(replay.body(),"$.order.status")).isEqualTo("CANCELLED");
    }
    @Test void concurrentPaymentRetriesChangeOrderOnlyOnce() throws Exception {
        UUID id=createdOrder();var body=Map.of("amount",amount(id));
        var a=client.sendAsync(request("POST",path(id),body,adminToken,null),HttpResponse.BodyHandlers.ofString());
        var b=client.sendAsync(request("POST",path(id),body,adminToken,null),HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a,b).join();status(a.join(),200);status(b.join(),200);assertThat(a.join().body()).isEqualTo(b.join().body());
        assertThat(jdbc.queryForObject("SELECT version FROM booking_orders WHERE id=?",Long.class,id)).isEqualTo(1);
    }
}
