package com.booking.member;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "會員資料", description = "查詢及更新目前登入會員的個人資料")
@SecurityRequirement(name = "bearerAuth")
public class MemberController {
    private final MemberService service;

    public MemberController(MemberService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "取得我的會員資料", description = "依目前存取權杖取得登入會員的個人資料與角色。")
    public MeResponse me(Authentication authentication) {
        return service.get(UUID.fromString(authentication.getName()));
    }

    @PatchMapping
    @Operation(summary = "更新我的會員資料", description = "更新目前登入會員允許修改的基本資料。")
    public MeResponse update(Authentication authentication, @Valid @RequestBody ProfileRequest request) {
        return service.update(UUID.fromString(authentication.getName()), request);
    }
}
