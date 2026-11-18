package com.booking.backoffice;

import static com.booking.backoffice.BackofficeModels.*;
import static com.booking.booking.BookingModels.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/backoffice")
@Tag(name = "櫃台作業", description = "櫃台人員與管理員共用的每日排程、訂單、代訂及報到操作")
@PreAuthorize("hasRole('FRONT_DESK')")
@SecurityRequirement(name="bearerAuth")
public class BackofficeController {
    private final BackofficeService service;
    public BackofficeController(BackofficeService service) { this.service=service; }
    @GetMapping("/timetable")
    @Operation(summary = "查詢每日排程看板", description = "依日期取得場地排程及訂單狀態，供櫃台與管理員查看。")
    public TimetableResponse timetable(@RequestParam LocalDate date) { return service.timetable(date); }
    @GetMapping("/orders")
    @Operation(summary = "查詢後台訂單", description = "依日期選擇性篩選後台訂單，並以頁碼及每頁筆數分頁。")
    public OrderPage list(@RequestParam(required=false) LocalDate date,@RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.list(date,page,size); }
    @PostMapping("/orders") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "建立現場或電話代訂", description = "由櫃台為顧客建立訂單；需提供 Idempotency-Key 避免重複送出。")
    public OrderResponse create(Authentication auth,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody StaffOrderRequest request) { return service.create(UUID.fromString(auth.getName()),key,request); }
    @GetMapping("/orders/{orderId}")
    @Operation(summary = "查詢後台訂單明細", description = "取得指定訂單及其目前處理狀態。")
    public OrderResponse order(@PathVariable UUID orderId) { return service.order(orderId); }
    @PatchMapping("/orders/{orderId}")
    @Operation(summary = "修改後台訂單", description = "依訂單狀態規則調整允許修改的訂單欄位。")
    public OrderResponse edit(@PathVariable UUID orderId,@Valid @RequestBody OrderEdit request) { return service.edit(orderId,request); }
    @PostMapping("/orders/{orderId}/cancellations")
    @Operation(summary = "由後台取消訂單", description = "由櫃台或管理員取消指定訂單，並更新相關時段與退款狀態。")
    public OrderResponse cancel(@PathVariable UUID orderId,@Valid @RequestBody CancellationRequest request) { return service.cancel(orderId,request); }
    @PostMapping("/orders/{orderId}/check-in")
    @Operation(summary = "辦理顧客報到", description = "核對訂單版本並將有效訂單標記為已報到。")
    public OrderResponse checkIn(Authentication auth,@PathVariable UUID orderId,@Valid @RequestBody VersionRequest request) { return service.checkIn(orderId,UUID.fromString(auth.getName()),request); }
    @PostMapping("/orders/{orderId}/check-in-reversals")
    @Operation(summary = "撤銷報到紀錄", description = "更正指定訂單的報到狀態，需提供符合目前資料的版本資訊。")
    public OrderResponse reverse(@PathVariable UUID orderId,@Valid @RequestBody VersionRequest request) { return service.reverseCheckIn(orderId,request); }
    @PostMapping("/blocks") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "建立維修封場", description = "管理員設定場地封閉時段與原因；需提供 Idempotency-Key。")
    public BlockResponse block(Authentication auth,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody BlockRequest request) { return service.createBlock(UUID.fromString(auth.getName()),key,request); }
    @GetMapping("/blocks") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "查詢指定日期封場", description = "管理員查詢指定日期的維修或暫停營業時段。")
    public List<BlockResponse> blocks(@RequestParam LocalDate date) { return service.blocks(date); }
    @DeleteMapping("/blocks/{blockId}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "移除維修封場", description = "管理員刪除指定封場紀錄並重新開放該時段。")
    public void release(@PathVariable UUID blockId) { service.releaseBlock(blockId); }
}
