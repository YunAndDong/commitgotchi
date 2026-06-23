package com.commitgotchi.report.application;

public interface ReportRequestProducer {

    void send(ReportRequestMessage message);
}
