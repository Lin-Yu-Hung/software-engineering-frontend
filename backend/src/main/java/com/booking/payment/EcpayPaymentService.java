package com.booking.payment;

import static com.booking.booking.BookingModels.*;
import static com.booking.booking.InventoryRepository.conflict;
import static com.booking.venue.ScheduleModels.ZONE;
import com.booking.booking.BookingService;
import com.booking.booking.PaymentConfirmationService;
import com.booking.common.BusinessException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 綠界全方位金流（信用卡）轉接層：產生已簽章的付款表單，並驗證付款結果通知。 */
@Service
public class EcpayPaymentService {
    private static final Logger log = LoggerFactory.getLogger(EcpayPaymentService.class);
    private static final DateTimeFormatter TRADE_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final DateTimeFormatter TRADE_NO_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    public record Checkout(String action, String merchantTradeNo, Map<String, String> fields) { }
    public record Notification(boolean authentic, UUID orderId, boolean paid) { }

    private final BookingService bookings;
    private final PaymentConfirmationService confirmations;
    private final PaymentRepository payments;
    private final Clock clock;
    private final String merchantId, hashKey, hashIv, checkoutUrl, returnUrl, orderResultUrl, clientBackUrl;
    private final long minRemainingSeconds;
    private final boolean acceptSimulated;

    public EcpayPaymentService(BookingService bookings, PaymentConfirmationService confirmations, PaymentRepository payments, Clock clock,
            @Value("${app.ecpay.merchant-id}") String merchantId, @Value("${app.ecpay.hash-key}") String hashKey,
            @Value("${app.ecpay.hash-iv}") String hashIv, @Value("${app.ecpay.checkout-url}") String checkoutUrl,
            @Value("${app.ecpay.return-url:}") String returnUrl, @Value("${app.ecpay.order-result-url:}") String orderResultUrl,
            @Value("${app.ecpay.frontend-result-url}") String clientBackUrl,
            @Value("${app.ecpay.min-remaining-seconds:180}") long minRemainingSeconds,
            @Value("${app.ecpay.accept-simulated:false}") boolean acceptSimulated) {
        this.bookings = bookings; this.confirmations = confirmations; this.payments = payments; this.clock = clock;
        this.merchantId = merchantId; this.hashKey = hashKey; this.hashIv = hashIv; this.checkoutUrl = checkoutUrl;
        this.returnUrl = returnUrl; this.orderResultUrl = orderResultUrl; this.clientBackUrl = clientBackUrl;
        this.minRemainingSeconds = minRemainingSeconds; this.acceptSimulated = acceptSimulated;
    }

    @Transactional
    public Checkout start(UUID orderId, UUID member) {
        if (returnUrl.isBlank()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ECPAY_NOT_CONFIGURED", "尚未設定綠界付款通知網址 (ECPAY_RETURN_URL)");
        var order = bookings.order(orderId, member);
        if (order.status() != OrderStatus.PENDING_PAYMENT || order.paymentStatus() != PaymentStatus.UNPAID)
            throw conflict("ORDER_NOT_PAYABLE", "此訂單不能付款");
        // 若使用者還在綠界頁面時保留就到期，會變成「卡被扣款、場地卻被釋出」，所以剩餘時間太短就先拒絕。
        if (order.expiresAt() == null || order.expiresAt().toInstant().isBefore(clock.instant().plusSeconds(minRemainingSeconds)))
            throw conflict("PAYMENT_WINDOW_TOO_SHORT", "保留時間即將到期，請重新選擇時段");
        var now = clock.instant().atZone(ZONE);
        String tradeNo = "P" + TRADE_NO_TIME.format(now) + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(Locale.ROOT);
        payments.insert(UUID.randomUUID(), orderId, tradeNo, order.totalAmount());

        var fields = new LinkedHashMap<String, String>();
        fields.put("MerchantID", merchantId);
        fields.put("MerchantTradeNo", tradeNo);
        fields.put("MerchantTradeDate", TRADE_DATE.format(now));
        fields.put("PaymentType", "aio");
        fields.put("TotalAmount", Long.toString(order.totalAmount()));
        fields.put("TradeDesc", "羽球場地預約");
        fields.put("ItemName", "羽球場地 " + order.items().size() + " 小時");
        fields.put("ReturnURL", returnUrl);
        if (!orderResultUrl.isBlank()) fields.put("OrderResultURL", orderResultUrl);
        fields.put("ClientBackURL", clientBackUrl);
        fields.put("ChoosePayment", "Credit");
        fields.put("EncryptType", "1");
        fields.put("CheckMacValue", CheckMacValue.of(fields, hashKey, hashIv));
        return new Checkout(checkoutUrl, tradeNo, fields);
    }

    /**
     * 同時處理 ReturnURL（綠界伺服器呼叫）與 OrderResultURL（瀏覽器導回）；兩者都有簽章，重複處理也只會入帳一次。
     * 刻意不加 @Transactional：入帳被拒絕時，不能連帶回滾「標記待退款」的紀錄。
     */
    public Notification handle(Map<String, String> p) {
        if (!CheckMacValue.verify(p, hashKey, hashIv) || !merchantId.equals(p.get("MerchantID"))) {
            log.warn("Rejected ECPay notification with invalid signature or merchant, MerchantTradeNo={}", p.get("MerchantTradeNo"));
            return new Notification(false, null, false);
        }
        var tx = payments.find(String.valueOf(p.get("MerchantTradeNo"))).orElse(null);
        if (tx == null) {
            log.warn("Signed ECPay notification for unknown MerchantTradeNo={}", p.get("MerchantTradeNo"));
            return new Notification(false, null, false);
        }
        if (tx.status().equals("PAID") || tx.status().equals("NEEDS_REFUND")) // 綠界重送通知；結果已確定，不再處理
            return new Notification(true, tx.orderId(), tx.status().equals("PAID"));

        String gatewayTradeNo = p.get("TradeNo"), rtnMsg = p.get("RtnMsg"), raw = new TreeMap<>(p).toString();
        Integer rtnCode = parseInt(p.get("RtnCode"));
        if (rtnCode == null || rtnCode != 1) {
            payments.record(tx.id(), "FAILED", gatewayTradeNo, rtnCode, rtnMsg, raw);
            return new Notification(true, tx.orderId(), false);
        }
        if ("1".equals(p.get("SimulatePaid")) && !acceptSimulated) { // 廠商後台的「模擬付款」：沒有實際扣款
            payments.record(tx.id(), "INITIATED", gatewayTradeNo, rtnCode, "SIMULATED_IGNORED", raw);
            return new Notification(true, tx.orderId(), false);
        }
        Long amount = parseLong(p.get("TradeAmt"));
        if (amount == null || amount != tx.amount()) {
            log.warn("ECPay charged {} but attempt {} expected {}", amount, tx.id(), tx.amount());
            payments.record(tx.id(), "NEEDS_REFUND", gatewayTradeNo, rtnCode, "AMOUNT_MISMATCH", raw);
            return new Notification(true, tx.orderId(), false);
        }
        try {
            confirmations.confirmVerifiedPayment(tx.orderId(), amount, gatewayTradeNo);
            payments.record(tx.id(), "PAID", gatewayTradeNo, rtnCode, rtnMsg, raw);
            return new Notification(true, tx.orderId(), true);
        } catch (BusinessException e) { // 例如：保留已到期、訂單已取消，或已被另一筆付款付清
            log.warn("ECPay payment {} for order {} needs refund: {}", gatewayTradeNo, tx.orderId(), e.getCode());
            payments.record(tx.id(), "NEEDS_REFUND", gatewayTradeNo, rtnCode, e.getCode(), raw);
            return new Notification(true, tx.orderId(), false);
        }
    }

    private static Integer parseInt(String v) { try { return v == null ? null : Integer.valueOf(v.trim()); } catch (NumberFormatException e) { return null; } }
    private static Long parseLong(String v) { try { return v == null ? null : Long.valueOf(v.trim()); } catch (NumberFormatException e) { return null; } }
}
