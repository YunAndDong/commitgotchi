package com.commitgotchi.auth.api.dto;

import com.commitgotchi.auth.api.validation.ValidSignupEmail;
import com.commitgotchi.auth.api.validation.ValidSignupPassword;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "회원가입 요청")
public record SignupRequest(
        @ValidSignupEmail
        @Schema(example = "new.user@example.com")
        String email,

        @ValidSignupPassword
        @Schema(example = "<12-64 character password>")
        String password
) {
}
