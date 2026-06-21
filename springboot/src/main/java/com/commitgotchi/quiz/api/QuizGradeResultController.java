package com.commitgotchi.quiz.api;

import com.commitgotchi.quiz.api.dto.QuizGradeResultRequest;
import com.commitgotchi.quiz.api.dto.QuizGradeResultResponse;
import com.commitgotchi.quiz.application.QuizGradeResultService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuizGradeResultController {

    private final QuizGradeResultService quizGradeResultService;

    public QuizGradeResultController(QuizGradeResultService quizGradeResultService) {
        this.quizGradeResultService = quizGradeResultService;
    }

    @PostMapping("/api/internal/quizzes/grade-result")
    public QuizGradeResultResponse receiveGradeResult(@Valid @RequestBody QuizGradeResultRequest request) {
        return quizGradeResultService.handle(request);
    }
}
