package com.booking.booking;

import static com.booking.booking.BookingModels.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "會員預約", description = "會員保留場地時段、建立訂單、查詢及取消預約")
@PreAuthorize("hasRole('MEMBER')")
@SecurityRequirement(name="bearerAuth")
public class BookingController {
    private final BookingService service;
    public BookingController(BookingService service) { this.service=service; }
    private UUID member(Authentication auth) { return UUID.fromString(auth.getName()); }
    @PostMapping("/holds") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "暫時保留時段", description = "暫時鎖定指定場地時段以供結帳；需提供 Idempotency-Key，保留逾時後會自動釋出。")
    public HoldResponse createHold(Authentication auth, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody HoldRequest request) { return service.createHold(member(auth),key,request); }
    @GetMapping("/holds/{holdId}")
    @Operation(summary = "查詢時段保留", description = "取得目前會員建立的保留與到期時間。")
    public HoldResponse hold(Authentication auth,@PathVariable UUID holdId) { return service.hold(holdId,member(auth)); }
    @DeleteMapping("/holds/{holdId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "釋放時段保留", description = "取消目前會員尚未轉成訂單的保留，並釋出時段。")
    public void release(Authentication auth,@PathVariable UUID holdId) { service.release(holdId,member(auth)); }
    @PostMapping("/orders") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "建立預約訂單", description = "將有效保留轉成待付款訂單；需提供 Idempotency-Key，金額由伺服器依場地費率計算。")
    public OrderResponse createOrder(Authentication auth,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OrderRequest request) { return service.createOrder(member(auth),key,request); }
    @GetMapping("/orders")
    @Operation(summary = "查詢我的訂單", description = "分頁查詢目前會員的預約，可用 scope 篩選訂單範圍。")
    public OrderPage orders(Authentication auth,@RequestParam(defaultValue="all") String scope,
            @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.orders(member(auth),scope,page,size); }
    @GetMapping("/orders/{orderId}")
    @Operation(summary = "查詢預約訂單", description = "取得目前會員擁有的指定訂單；其他會員無法讀取。")
    public OrderResponse order(Authentication auth,@PathVariable UUID orderId) { return service.order(orderId,member(auth)); }
    @PostMapping("/orders/{orderId}/cancellations")
    @Operation(summary = "取消預約", description = "取消目前會員擁有的訂單，並依訂單付款狀態更新退款處理狀態及釋出時段。")
    public OrderResponse cancel(Authentication auth,@PathVariable UUID orderId,@Valid @RequestBody CancellationRequest request) { return service.cancel(orderId,member(auth),request); }
}
