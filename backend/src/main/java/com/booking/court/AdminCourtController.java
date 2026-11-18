package com.booking.court;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/backoffice/courts")
@Tag(name = "場地管理", description = "管理員維護場地資料與啟用狀態")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class AdminCourtController {
    private final AdminCourtService service;

    public AdminCourtController(AdminCourtService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "查詢所有場地", description = "管理員查詢包含停用場地在內的場地清單。")
    public List<AdminCourtResponse> list() { return service.list(); }

    @GetMapping("/{courtId}")
    @Operation(summary = "查詢場地資料", description = "依場地 UUID 取得後台場地設定。")
    public AdminCourtResponse get(@PathVariable UUID courtId) { return service.get(courtId); }

    @PostMapping
    @Operation(summary = "新增場地", description = "建立場地資料；欄位需符合場地設定驗證規則。")
    public ResponseEntity<AdminCourtResponse> create(@Valid @RequestBody CreateCourtRequest request) {
        AdminCourtResponse court = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/courts/" + court.id())).body(court);
    }

    @PatchMapping("/{courtId}")
    @Operation(summary = "修改場地資料", description = "更新指定場地的名稱、描述或其他可修改設定。")
    public AdminCourtResponse update(@PathVariable UUID courtId, @Valid @RequestBody UpdateCourtRequest request) {
        return service.update(courtId, request);
    }

    @DeleteMapping("/{courtId}")
    @Operation(summary = "停用場地", description = "依 expectedVersion 樂觀鎖定版本停用場地，不會刪除既有訂單紀錄。")
    public ResponseEntity<Void> retire(@PathVariable UUID courtId,
            @RequestParam @PositiveOrZero long expectedVersion) {
        service.retire(courtId, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
