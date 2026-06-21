package com.commitgotchi.report.api.dto;

public record ReportCallbackResponse(boolean duplicate) {

    public static ReportCallbackResponse acceptedResult() {
        return new ReportCallbackResponse(false);
    }

    public static ReportCallbackResponse duplicateResult() {
        return new ReportCallbackResponse(true);
    }
}
