package com.commitgotchi.report.application;

public class ReportRequestPublishException extends RuntimeException {

    public ReportRequestPublishException(String message) {
        super(message);
    }

    public ReportRequestPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
