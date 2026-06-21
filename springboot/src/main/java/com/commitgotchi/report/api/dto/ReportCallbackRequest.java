package com.commitgotchi.report.api.dto;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ReportCallbackRequest(
        @NotBlank
        String requestId,

        @Positive
        long userId,

        @Positive
        long characterId,

        @NotNull
        LocalDate targetDate,

        @NotNull
        ReportCallbackStatus status,

        @Valid
        @NotNull
        FastApiScoreDelta scoreDelta,

        @NotBlank
        @Size(max = 160)
        String statusMessage,

        JsonNode dailyReport,

        JsonNode nextRecommendation,

        @Valid
        @NotNull
        List<RecommendedQuiz> recommendedQuizzes,

        List<@NotBlank String> failedStages
) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RecommendedQuiz(
            Long problemId,

            @NotBlank
            String question,

            @NotBlank
            String modelAnswer,

            @Valid
            @NotNull
            FastApiScoreDelta scoreAllocation
    ) {
    }
}
