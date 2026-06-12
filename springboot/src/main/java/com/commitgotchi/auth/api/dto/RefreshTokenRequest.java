package com.commitgotchi.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Refresh Token 재발급 또는 로그아웃 요청")
public record RefreshTokenRequest(
        @NotNull
        @Schema(example = "<refresh-token>")
        String refreshToken
) {
}
