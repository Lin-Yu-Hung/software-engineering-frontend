package com.booking.court;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/courts")
@Tag(name = "場地查詢", description = "公開查詢可預約的羽球場地")
public class CourtController {
    private final CourtService service;

    public CourtController(CourtService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "取得開放中的場地清單", description = "列出目前啟用且可供顧客預約的場地。")
    public List<CourtResponse> list() {
        return service.listActiveCourts();
    }

    @GetMapping("/{courtId}")
    @Operation(summary = "取得單一開放場地", description = "依場地 UUID 查詢場地；不存在或已停用時回傳 404。")
    public CourtResponse get(@PathVariable UUID courtId) {
        return service.getActiveCourt(courtId);
    }
}
