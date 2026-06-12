package com.commitgotchi.auth.api;

import com.commitgotchi.auth.api.dto.LoginRequest;
import com.commitgotchi.auth.api.dto.RefreshTokenRequest;
import com.commitgotchi.auth.api.dto.SignupRequest;
import com.commitgotchi.auth.api.dto.SignupResponse;
import com.commitgotchi.auth.api.dto.TokenPairResponse;
import com.commitgotchi.auth.application.AuthService;
import com.commitgotchi.auth.application.RefreshTokenService;
import com.commitgotchi.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
    }

    @Operation(summary = "이메일과 비밀번호로 회원가입")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회원가입 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "USER_EMAIL_CONFLICT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request.email(), request.password());
    }

    @Operation(summary = "이메일과 비밀번호로 로그인")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공",
                    content = @Content(schema = @Schema(implementation = TokenPairResponse.class))),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "AUTH_INVALID_CREDENTIALS",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/login")
    public TokenPairResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @Operation(summary = "Refresh Token Rotation으로 Token Pair 재발급")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token Pair 재발급 성공",
                    content = @Content(schema = @Schema(implementation = TokenPairResponse.class))),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "AUTH_REFRESH_TOKEN_INVALID / AUTH_REFRESH_TOKEN_REUSED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/refresh")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return refreshTokenService.rotate(request.refreshToken());
    }

    @Operation(
            summary = "현재 Refresh Token 세션 로그아웃",
            description = "제출된 Refresh Token 세션을 멱등 종료합니다. 이후 해당 Refresh Token 재발급은 실패하지만, "
                    + "기존 Access Token은 블랙리스트 없이 만료 전 최대 15분 동안 유효할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "로그아웃 완료", content = @Content),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revokeIfPresent(request.refreshToken());
    }
}
