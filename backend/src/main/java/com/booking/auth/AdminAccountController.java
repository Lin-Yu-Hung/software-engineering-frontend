package com.booking.auth;

import com.booking.member.MeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/backoffice/admins")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "管理員帳號", description = "由現有管理員建立新的管理員帳號")
public class AdminAccountController {
    private final AuthService service;

    public AdminAccountController(AuthService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新增管理員帳號", description = "僅 ADMIN 可用。輸入 email、password、name、phone；密碼規則與會員註冊相同。回傳新帳號資料，不會登入或切換目前操作者。Email 已註冊時回傳 409，不會修改既有會員角色。第一位管理員需由本機初始化或資料庫授權建立。")
    public MeResponse create(@Valid @RequestBody RegisterRequest request) {
        return service.createAdmin(request);
    }
}
