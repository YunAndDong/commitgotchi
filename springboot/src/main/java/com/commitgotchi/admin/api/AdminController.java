package com.commitgotchi.admin.api;

import com.commitgotchi.admin.api.dto.AdminPingResponse;
import com.commitgotchi.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN 전용 최소 검증 API.
 *
 * <p>인가 정책은 {@code SecurityConfig}가 URL 기반(`/api/admin/**` → {@code hasRole("ADMIN")})으로
 * 강제한다. 컨트롤러는 Repository, {@code JwtDecoder}, {@code SecurityContextHolder}에 직접 의존하지 않는다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Operation(
            summary = "관리자 검증 (ADMIN 전용)",
            description = "ADMIN Role을 가진 사용자만 접근할 수 있는 최소 검증 API. USER는 403 AUTH_FORBIDDEN."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "ADMIN 접근 성공"),
            @ApiResponse(responseCode = "401",
                    description = "AUTH_ACCESS_TOKEN_MISSING / AUTH_ACCESS_TOKEN_INVALID / AUTH_ACCESS_TOKEN_EXPIRED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403",
                    description = "AUTH_FORBIDDEN",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/ping")
    public AdminPingResponse ping() {
        return AdminPingResponse.ok();
    }
}
