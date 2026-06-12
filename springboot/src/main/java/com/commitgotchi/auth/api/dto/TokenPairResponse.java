package com.commitgotchi.auth.api.dto;

import com.commitgotchi.auth.application.IssuedRefreshToken;
import com.commitgotchi.security.IssuedAccessToken;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Access Token과 Rotation용 Refresh Token 쌍")
public record TokenPairResponse(
        @Schema(example = "Bearer") String tokenType,
        @Schema(example = "<access-token>") String accessToken,
        Instant accessTokenExpiresAt,
        @Schema(example = "<refresh-token>") String refreshToken,
        Instant refreshTokenExpiresAt
) {
    public static TokenPairResponse from(IssuedAccessToken accessToken, IssuedRefreshToken refreshToken) {
        return new TokenPairResponse(
                "Bearer",
                accessToken.value(),
                accessToken.expiresAt(),
                refreshToken.rawToken(),
                refreshToken.expiresAt()
        );
    }
}
