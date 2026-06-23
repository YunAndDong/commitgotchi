package com.commitgotchi.quiz.api.dto;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.domain.CharacterEmotion;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record QuizGradeResultRequest(
        @NotBlank
        String submissionId,

        @Positive
        long userId,

        @Positive
        Long characterId,

        @Positive
        long quizId,

        @NotNull
        QuizGradeStatus status,

        @Valid
        @NotNull
        FastApiScoreDelta scoreAllocation,

        @Valid
        @NotNull
        FastApiScoreDelta scoreDelta,

        String feedback,

        CharacterEmotion emotion,

        @NotBlank
        @Size(max = 160)
        String statusMessage,

        String failedReason
) {
}
