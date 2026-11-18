package com.booking;

import static org.assertj.core.api.Assertions.*;
import com.booking.payment.CheckMacValue;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.ecpay.return-url=https://example.test/api/v1/payments/ecpay/notify",
                "app.ecpay.frontend-result-url=http://localhost:5173/my-bookings"})
class EcpayPaymentIntegrationTests extends BookingApiTestSupport {
    private static final String KEY = "pwFHCqoQZGmho4w6", IV = "EkRm7iFT261dpevs";

    private Map<String, String> checkout(UUID orderId) throws Exception {
        var response = send("POST", "/orders/" + orderId + "/payments/ecpay", null, token, null);
        status(response, 200);
        return JsonPath.read(response.body(), "$.fields");
    }
    private String tradeNo(UUID orderId) throws Exception { return checkout(orderId).get("MerchantTradeNo"); }
    private long amount(UUID id) { return jdbc.queryForObject("SELECT total_amount FROM booking_orders WHERE id=?", Long.class, id); }
    private String orderCol(String col, UUID id) { return jdbc.queryForObject("SELECT " + col + " FROM booking_orders WHERE id=?", String.class, id); }
    private String txStatus(String tradeNo) { return jdbc.queryForObject("SELECT status FROM payment_transactions WHERE merchant_trade_no=?", String.class, tradeNo); }

    /** 模擬綠界 POST 到 ReturnURL 的內容，並用和綠界相同的方式簽章。 */
    private Map<String, String> notification(String tradeNo, long amount, String rtnCode, String gatewayTradeNo) {
        var p = new HashMap<String, String>();
        p.put("MerchantID", "3002607"); p.put("MerchantTradeNo", tradeNo); p.put("RtnCode", rtnCode);
        p.put("RtnMsg", rtnCode.equals("1") ? "交易成功" : "交易失敗"); p.put("TradeNo", gatewayTradeNo);
        p.put("TradeAmt", Long.toString(amount)); p.put("PaymentDate", "2026/11/18 12:00:00");
        p.put("PaymentType", "Credit_CreditCard"); p.put("PaymentTypeChargeFee", "1");
        p.put("TradeDate", "2026/11/18 11:59:00"); p.put("SimulatePaid", "0");
        p.put("CheckMacValue", CheckMacValue.of(p, KEY, IV));
        return p;
    }
    private HttpResponse<String> post(String path, Map<String, String> form, String... headers) throws Exception {
        String body = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (headers.length > 0) builder.headers(headers);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 回歸測試：真實的綠界請求會帶 Accept / Origin 標頭，曾造成 406 與 "Invalid CORS request"。 */
    @Test void realWorldHeadersFromEcpayAreAccepted() throws Exception {
        UUID order = createdOrder(); String tradeNo = tradeNo(order);
        var n = notification(tradeNo, amount(order), "1", "2611181200012345");
        var notify = post("/payments/ecpay/notify", n, "Accept", "application/json");
        status(notify, 200); assertThat(notify.body()).isEqualTo("1|OK");
        assertThat(orderCol("payment_status", order)).isEqualTo("PAID");
        for (String origin : new String[] {"https://payment-stage.ecpay.com.tw", "null"}) {
            var browser = post("/payments/ecpay/result", n, "Origin", origin, "Accept", "text/html");
            status(browser, 303);
            assertThat(browser.headers().firstValue("Location").orElseThrow()).endsWith("&payment=success");
        }
    }

    @Test void checkoutFormIsSignedAndMatchesServerAmount() throws Exception {
        UUID order = createdOrder();
        var fields = checkout(order);
        assertThat(CheckMacValue.verify(fields, KEY, IV)).isTrue();
        assertThat(fields.get("TotalAmount")).isEqualTo(Long.toString(amount(order)));
        assertThat(fields.get("MerchantTradeNo")).matches("[A-Za-z0-9]{1,20}");
        assertThat(fields).containsEntry("ChoosePayment", "Credit").containsEntry("EncryptType", "1").containsEntry("PaymentType", "aio")
                .containsEntry("ReturnURL", "https://example.test/api/v1/payments/ecpay/notify");
        assertThat(fields.get("MerchantTradeDate")).matches("\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(tradeNo(order)).as("每次重新付款都要產生新的 MerchantTradeNo").isNotEqualTo(fields.get("MerchantTradeNo"));
    }

    @Test void onlyOwnerMemberCanStartCheckout() throws Exception {
        UUID order = createdOrder();
        code(send("POST", "/orders/" + order + "/payments/ecpay", null, otherToken, null), 404, "ORDER_NOT_FOUND");
        status(send("POST", "/orders/" + order + "/payments/ecpay", null, staffToken, null), 403);
        status(send("POST", "/orders/" + order + "/payments/ecpay", null, null, null), 401);
    }

    @Test void checkoutRefusedWhenHoldIsAboutToExpire() throws Exception {
        UUID order = createdOrder();
        var soon = OffsetDateTime.now().plusSeconds(60);
        jdbc.update("UPDATE booking_orders SET expires_at=? WHERE id=?", soon, order);
        jdbc.update("UPDATE slot_claims SET expires_at=? WHERE order_id=?", soon, order);
        code(send("POST", "/orders/" + order + "/payments/ecpay", null, token, null), 409, "PAYMENT_WINDOW_TOO_SHORT");
    }

    @Test void genuineNotificationConfirmsOrderOnceAndAcknowledgesRetries() throws Exception {
        UUID order = createdOrder(); String tradeNo = tradeNo(order);
        var n = notification(tradeNo, amount(order), "1", "2611181200001234");
        var first = post("/payments/ecpay/notify", n);
        status(first, 200); assertThat(first.body()).isEqualTo("1|OK");
        assertThat(orderCol("status", order)).isEqualTo("CONFIRMED");
        assertThat(orderCol("payment_status", order)).isEqualTo("PAID");
        assertThat(orderCol("paid_reference", order)).isEqualTo("2611181200001234");
        assertThat(txStatus(tradeNo)).isEqualTo("PAID");
        assertThat(post("/payments/ecpay/notify", n).body()).isEqualTo("1|OK");
        assertThat(jdbc.queryForObject("SELECT version FROM booking_orders WHERE id=?", Long.class, order)).isEqualTo(1);
    }

    @Test void forgedOrUnknownNotificationsNeverConfirm() throws Exception {
        UUID order = createdOrder(); String tradeNo = tradeNo(order);
        var forged = notification(tradeNo, amount(order), "1", "2611181200009999");
        forged.put("CheckMacValue", "0".repeat(64));
        var response = post("/payments/ecpay/notify", forged);
        status(response, 400); assertThat(response.body()).isNotEqualTo("1|OK");
        var otherMerchant = notification(tradeNo, amount(order), "1", "2611181200009998");
        otherMerchant.put("MerchantID", "2000132"); otherMerchant.put("CheckMacValue", CheckMacValue.of(otherMerchant, KEY, IV));
        status(post("/payments/ecpay/notify", otherMerchant), 400);
        status(post("/payments/ecpay/notify", notification("PUNKNOWN000000000001", amount(order), "1", "2611181200009997")), 400);
        assertThat(orderCol("payment_status", order)).isEqualTo("UNPAID");
        assertThat(txStatus(tradeNo)).isEqualTo("INITIATED");
    }

    @Test void amountOffByOneIsFlaggedForRefundNotConfirmed() throws Exception {
        for (long delta : new long[] {1, -1}) {
            UUID order = createdOrder(); String tradeNo = tradeNo(order);
            assertThat(post("/payments/ecpay/notify", notification(tradeNo, amount(order) + delta, "1", "26111812000" + (delta > 0 ? "11111" : "22222"))).body())
                    .isEqualTo("1|OK");
            assertThat(orderCol("payment_status", order)).isEqualTo("UNPAID");
            assertThat(txStatus(tradeNo)).isEqualTo("NEEDS_REFUND");
            removeOwnedInventory();
        }
    }

    @Test void failedCardLeavesOrderPayableWithNewAttempt() throws Exception {
        UUID order = createdOrder(); String tradeNo = tradeNo(order);
        assertThat(post("/payments/ecpay/notify", notification(tradeNo, amount(order), "10100248", "2611181200003333")).body()).isEqualTo("1|OK");
        assertThat(txStatus(tradeNo)).isEqualTo("FAILED");
        assertThat(orderCol("status", order)).isEqualTo("PENDING_PAYMENT");
        String retry = tradeNo(order);
        post("/payments/ecpay/notify", notification(retry, amount(order), "1", "2611181200004444"));
        assertThat(orderCol("payment_status", order)).isEqualTo("PAID");
    }

    @Test void simulatedPaymentIsIgnoredByDefault() throws Exception {
        UUID order = createdOrder(); String tradeNo = tradeNo(order);
        var n = notification(tradeNo, amount(order), "1", "2611181200005555");
        n.put("SimulatePaid", "1"); n.put("CheckMacValue", CheckMacValue.of(n, KEY, IV));
        assertThat(post("/payments/ecpay/notify", n).body()).isEqualTo("1|OK");
        assertThat(orderCol("payment_status", order)).isEqualTo("UNPAID");
    }

    @Test void paymentAfterHoldExpiredIsAcknowledgedAndFlaggedForRefund() throws Exception {
        UUID order = createdOrder(); String tradeNo = tradeNo(order);
        var before = OffsetDateTime.now().minusMinutes(1);
        jdbc.update("UPDATE booking_orders SET expires_at=? WHERE id=?", before, order);
        jdbc.update("UPDATE slot_claims SET expires_at=? WHERE order_id=?", before, order);
        assertThat(post("/payments/ecpay/notify", notification(tradeNo, amount(order), "1", "2611181200006666")).body()).isEqualTo("1|OK");
        var seen = send("GET", "/orders/" + order, null, token, null);
        assertThat(JsonPath.<String>read(seen.body(), "$.status")).isEqualTo("EXPIRED");
        assertThat(JsonPath.<String>read(seen.body(), "$.paymentStatus")).isEqualTo("UNPAID");
        assertThat(txStatus(tradeNo)).isEqualTo("NEEDS_REFUND");
    }

    @Test void secondSuccessfulAttemptOnPaidOrderNeedsRefund() throws Exception {
        UUID order = createdOrder(); String a = tradeNo(order), b = tradeNo(order);
        post("/payments/ecpay/notify", notification(a, amount(order), "1", "2611181200007777"));
        assertThat(post("/payments/ecpay/notify", notification(b, amount(order), "1", "2611181200008888")).body()).isEqualTo("1|OK");
        assertThat(txStatus(b)).isEqualTo("NEEDS_REFUND");
        assertThat(orderCol("paid_reference", order)).isEqualTo("2611181200007777");
    }

    @Test void browserResultRedirectsToFrontendWithOutcome() throws Exception {
        UUID order = createdOrder(); String tradeNo = tradeNo(order);
        var ok = post("/payments/ecpay/result", notification(tradeNo, amount(order), "1", "2611181200001111"));
        status(ok, 303);
        assertThat(ok.headers().firstValue("Location").orElseThrow())
                .isEqualTo("http://localhost:5173/my-bookings?orderId=" + order + "&payment=success");
        var forged = notification(tradeNo, amount(order), "1", "2611181200001111"); forged.put("CheckMacValue", "BAD");
        assertThat(post("/payments/ecpay/result", forged).headers().firstValue("Location").orElseThrow()).endsWith("?payment=unknown");
    }
}
