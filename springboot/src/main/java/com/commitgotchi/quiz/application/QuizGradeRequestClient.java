package com.commitgotchi.quiz.application;

public interface QuizGradeRequestClient {

    boolean isEnabled();

    QuizGradeRequestResult requestGrade(QuizGradeRequestMessage request);
}
