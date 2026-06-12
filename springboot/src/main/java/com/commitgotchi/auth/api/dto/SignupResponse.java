package com.commitgotchi.auth.api.dto;

import com.commitgotchi.user.domain.User;
import com.commitgotchi.user.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "회원가입 성공 응답")
public record SignupResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "new.user@example.com") String email,
        @Schema(example = "USER") UserRole role,
        Instant createdAt
) {
    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
