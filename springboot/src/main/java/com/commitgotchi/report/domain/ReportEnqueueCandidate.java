package com.commitgotchi.report.domain;

public class ReportEnqueueCandidate {

    private long userId;
    private String stateJson;
    private String reportJson;

    protected ReportEnqueueCandidate() {
    }

    public long getUserId() {
        return userId;
    }

    public String getStateJson() {
        return stateJson;
    }

    public String getReportJson() {
        return reportJson;
    }
}
