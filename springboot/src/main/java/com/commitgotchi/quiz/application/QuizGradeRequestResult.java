package com.commitgotchi.quiz.application;

public record QuizGradeRequestResult(boolean accepted, String submissionId, String failureReason) {

    public static QuizGradeRequestResult accepted(String submissionId) {
        return new QuizGradeRequestResult(true, submissionId, null);
    }

    public static QuizGradeRequestResult failed(String failureReason) {
        return new QuizGradeRequestResult(false, null, failureReason);
    }
}
