package com.commitgotchi.auth.api.dto;

import com.commitgotchi.auth.api.validation.ValidSignupEmail;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "로그인 요청")
public record LoginRequest(
        @ValidSignupEmail
        @Schema(example = "user@example.com")
        String email,

        @NotBlank
        @Schema(example = "<password>")
        String password
) {
}
