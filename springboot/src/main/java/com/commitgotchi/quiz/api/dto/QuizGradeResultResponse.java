package com.commitgotchi.quiz.api.dto;

public record QuizGradeResultResponse(boolean accepted, boolean duplicate) {

    public static QuizGradeResultResponse acceptedResult() {
        return new QuizGradeResultResponse(true, false);
    }

    public static QuizGradeResultResponse duplicateResult() {
        return new QuizGradeResultResponse(true, true);
    }
}
