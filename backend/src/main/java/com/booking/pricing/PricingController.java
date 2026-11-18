package com.booking.pricing;

import static com.booking.pricing.PricingModels.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/backoffice/pricing-rules")
@Tag(name = "費率管理", description = "管理員查詢及更新場地預約價格規則")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class PricingController {
    private final PricingService service;
    public PricingController(PricingService service) { this.service = service; }
    @GetMapping @Operation(summary = "查詢目前費率", description = "取得場地目前套用的預約價格規則。")
    public PricingResponse get() { return service.current(); }
    @PutMapping @Operation(summary = "更新預約費率", description = "以傳入內容取代目前費率設定；新費率依系統規則套用於預約。")
    public PricingResponse replace(@Valid @RequestBody PricingRequest request) { return service.replace(request); }
}
