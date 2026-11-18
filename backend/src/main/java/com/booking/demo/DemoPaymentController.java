package com.booking.demo;

import com.booking.booking.BookingModels.OrderResponse;
import com.booking.booking.BookingService;
import com.booking.booking.PaymentConfirmationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Local coursework demo adapter. This never contacts a payment provider. */
@RestController
@RequestMapping("/api/v1/demo/orders")
@Tag(name = "本機示範付款", description = "僅用於本機展示，不會連接綠界或進行真實扣款")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name="bearerAuth")
public class DemoPaymentController {
    private final PaymentConfirmationService payments;
    private final boolean enabled;
    public DemoPaymentController(PaymentConfirmationService payments,@Value("${app.demo-payments.enabled:false}") boolean enabled) {
        this.payments=payments;this.enabled=enabled;
    }
    public record ConfirmRequest(@NotNull @Positive Long amount) { }
    public record DemoPaymentResponse(String mode,OrderResponse order) { }
    @PostMapping("/{orderId}/confirm-payment")
    @Operation(summary="模擬示範付款成功", description="僅在啟用本機 DEMO 付款時可用；管理員確認指定金額並模擬付款結果，不會向綠界請款。Azure 雲端預設停用。")
    public DemoPaymentResponse confirm(@PathVariable UUID orderId,@Valid @RequestBody ConfirmRequest request) {
        if(!enabled) throw BookingService.missing("DEMO_PAYMENTS_DISABLED","示範付款功能未啟用");
        return new DemoPaymentResponse("DEMO",payments.confirmVerifiedPayment(orderId,request.amount(),"DEMO-"+orderId));
    }
}
