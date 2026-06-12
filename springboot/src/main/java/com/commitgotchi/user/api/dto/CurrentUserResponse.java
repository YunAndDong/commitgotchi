package com.commitgotchi.user.api.dto;

import com.commitgotchi.security.AuthPrincipal;
import com.commitgotchi.user.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "현재 인증 사용자")
public record CurrentUserResponse(long id, String email, UserRole role) {

    public static CurrentUserResponse from(AuthPrincipal principal) {
        return new CurrentUserResponse(principal.userId(), principal.email(), principal.role());
    }
}
