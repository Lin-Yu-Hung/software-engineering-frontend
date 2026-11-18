package com.booking.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "綠界付款", description = "綠界全方位金流（信用卡）：產生付款表單、接收付款結果通知")
public class EcpayController {
    private final EcpayPaymentService service;
    private final String frontendResultUrl;
    public EcpayController(EcpayPaymentService service, @Value("${app.ecpay.frontend-result-url}") String frontendResultUrl) {
        this.service = service; this.frontendResultUrl = frontendResultUrl;
    }

    @PostMapping("/orders/{orderId}/payments/ecpay")
    @PreAuthorize("hasRole('MEMBER')") @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "發起綠界信用卡付款", description = "為待付款訂單產生已簽章的綠界表單欄位；前端以 POST form 送到 action 網址。每次呼叫產生新的 MerchantTradeNo。")
    public EcpayPaymentService.Checkout start(Authentication auth, @PathVariable UUID orderId) {
        return service.start(orderId, UUID.fromString(auth.getName()));
    }

    @PostMapping("/payments/ecpay/notify")
    @Operation(summary = "綠界付款結果通知 (ReturnURL)", description = "由綠界伺服器呼叫，驗證 CheckMacValue 後入帳，並回應純文字 1|OK。")
    public ResponseEntity<String> receiveNotify(@RequestParam Map<String, String> params) {
        // 1|OK 只代表「已收到通知」；回其他內容綠界會重送，對偽造或不明的通知來說正是我們要的。
        // 直接指定 Content-Type（不用 produces），避免綠界送來的 Accept 標頭讓 Spring 回 406。
        boolean authentic = service.handle(params).authentic();
        return ResponseEntity.status(authentic ? HttpStatus.OK : HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN)
                .body(authentic ? "1|OK" : "0|CheckMacValue Error");
    }

    @PostMapping("/payments/ecpay/result")
    @Operation(summary = "綠界付款完成導回 (OrderResultURL)", description = "使用者瀏覽器從綠界導回，驗證後轉址到前端訂單頁。")
    public ResponseEntity<Void> result(@RequestParam Map<String, String> params) {
        var outcome = service.handle(params);
        var uri = UriComponentsBuilder.fromUriString(frontendResultUrl);
        if (outcome.orderId() != null) uri.queryParam("orderId", outcome.orderId());
        uri.queryParam("payment", !outcome.authentic() ? "unknown" : outcome.paid() ? "success" : "failed");
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(uri.build().toUri()).build();
    }
}
