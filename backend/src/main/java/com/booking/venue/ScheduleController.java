package com.booking.venue;

import static com.booking.venue.ScheduleModels.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/backoffice")
@Tag(name = "營業日程管理", description = "管理員設定每週營業時段及特定日期例外")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class ScheduleController {
    private final ScheduleService service;
    public ScheduleController(ScheduleService service) { this.service = service; }
    @GetMapping("/operating-hours")
    @Operation(summary = "查詢每週營業時間", description = "取得場館目前每週各日的營業時間設定。")
    public HoursResponse hours() { return service.hours(); }
    @PutMapping("/operating-hours")
    @Operation(summary = "更新每週營業時間", description = "以傳入設定完整取代每週營業時間表。")
    public HoursResponse replace(@Valid @RequestBody HoursRequest request) { return service.replace(request); }
    @GetMapping("/calendar-exceptions")
    @Operation(summary = "查詢營業日曆例外", description = "查詢 from 至 to 日期範圍內的公休、特別營業或營業時間調整。")
    public List<CalendarException> list(@RequestParam LocalDate from, @RequestParam LocalDate to) { return service.list(from, to); }
    @PostMapping("/calendar-exceptions")
    @Operation(summary = "新增營業日曆例外", description = "建立指定日期的公休或特殊營業時間設定。")
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarException create(@Valid @RequestBody ExceptionRequest request) { return service.create(request); }
    @PatchMapping("/calendar-exceptions/{exceptionId}")
    @Operation(summary = "修改營業日曆例外", description = "更新指定日期的例外類型、時間或說明。")
    public CalendarException update(@PathVariable UUID exceptionId, @Valid @RequestBody ExceptionUpdateRequest request) { return service.update(exceptionId, request); }
    @DeleteMapping("/calendar-exceptions/{exceptionId}")
    @Operation(summary = "刪除營業日曆例外", description = "依 expectedVersion 版本資訊刪除日期例外並恢復一般營業規則。")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID exceptionId, @RequestParam @PositiveOrZero long expectedVersion) { service.delete(exceptionId, expectedVersion); }
}
