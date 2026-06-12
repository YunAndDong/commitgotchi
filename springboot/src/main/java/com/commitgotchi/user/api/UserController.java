package com.commitgotchi.user.api;

import com.commitgotchi.common.error.ErrorResponse;
import com.commitgotchi.security.AuthPrincipal;
import com.commitgotchi.user.api.dto.CurrentUserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Operation(summary = "현재 인증 사용자 조회")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "현재 사용자 조회 성공"),
            @ApiResponse(responseCode = "401",
                    description = "AUTH_ACCESS_TOKEN_MISSING / AUTH_ACCESS_TOKEN_INVALID / AUTH_ACCESS_TOKEN_EXPIRED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me")
    public CurrentUserResponse currentUser(@AuthenticationPrincipal AuthPrincipal principal) {
        return CurrentUserResponse.from(principal);
    }
}
