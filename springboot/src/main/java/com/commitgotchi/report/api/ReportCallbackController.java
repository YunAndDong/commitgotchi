package com.commitgotchi.report.api;

import com.commitgotchi.report.api.dto.ReportCallbackRequest;
import com.commitgotchi.report.api.dto.ReportCallbackResponse;
import com.commitgotchi.report.application.ReportCallbackService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ReportCallbackController.class);

    private final ReportCallbackService reportCallbackService;

    public ReportCallbackController(ReportCallbackService reportCallbackService) {
        this.reportCallbackService = reportCallbackService;
    }

    @PostMapping("/api/report")
    public ReportCallbackResponse receiveReport(@Valid @RequestBody ReportCallbackRequest request) {
        // Diagnostic: how many recommended quizzes survived deserialization + @Valid.
        // Compare with the FastAPI "recommendedQuizzes=N" log to locate where they drop.
        log.info("received report callback requestId={} status={} recommendedQuizzes={}",
                request.requestId(), request.status(),
                request.recommendedQuizzes() == null ? -1 : request.recommendedQuizzes().size());
        return reportCallbackService.handle(request);
    }
}
