package com.commitgotchi.codex.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CodexReviewRequest(
        @NotNull
        @Min(1)
        @Max(5)
        Integer stars,

        @NotBlank
        @Size(max = 1000)
        String text
) {
}
