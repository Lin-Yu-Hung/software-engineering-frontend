package com.booking.availability;

import static com.booking.availability.AvailabilityModels.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "營業與空場", description = "查詢場館資訊、營業時間及可預約時段")
public class AvailabilityController {
    private final AvailabilityService service;
    public AvailabilityController(AvailabilityService service) { this.service = service; }
    @GetMapping("/venue") @Operation(summary = "查詢場館資訊", description = "取得場館名稱、時區與營業相關基本資訊。")
    public VenueResponse venue() { return service.venue(); }
    @GetMapping("/availability")
    @Operation(summary = "查詢可預約時段", description = "依日期列出可預約時段；可選擇 courtId 篩選單一場地。")
    public AvailabilityResponse get(@RequestParam LocalDate date, @RequestParam(required = false) UUID courtId) {
        return service.get(date, courtId);
    }
}
