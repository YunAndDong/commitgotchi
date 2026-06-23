package com.commitgotchi.quiz.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.fasterxml.jackson.databind.JsonNode;

public record QuizGradeRequestMessage(
        String submissionId,
        long userId,
        long characterId,
        long quizId,
        String problemId,
        String question,
        String modelAnswer,
        String userAnswer,
        FastApiScoreDelta scoreAllocation,
        JsonNode characterMetadata,
        String callbackUrl
) {
}
