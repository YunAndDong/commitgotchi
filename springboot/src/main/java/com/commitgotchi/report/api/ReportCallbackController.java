package com.commitgotchi.report.api;

import com.commitgotchi.report.api.dto.ReportCallbackRequest;
import com.commitgotchi.report.api.dto.ReportCallbackResponse;
import com.commitgotchi.report.application.ReportCallbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportCallbackController {

    private final ReportCallbackService reportCallbackService;

    public ReportCallbackController(ReportCallbackService reportCallbackService) {
        this.reportCallbackService = reportCallbackService;
    }

    @PostMapping("/api/report")
    public ReportCallbackResponse receiveReport(@Valid @RequestBody ReportCallbackRequest request) {
        return reportCallbackService.handle(request);
    }
}
