package com.commitgotchi.report.scheduler;

import com.commitgotchi.report.application.ReportOutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(
        prefix = "commitgotchi.report.dispatcher",
        name = "enabled",
        havingValue = "true"
)
public class ReportOutboxDispatcherScheduler {

    private final ReportOutboxDispatcher dispatcher;

    public ReportOutboxDispatcherScheduler(ReportOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${commitgotchi.report.dispatcher.fixed-delay-ms:30000}")
    public void dispatchAvailable() {
        dispatcher.dispatchAvailable(Instant.now());
    }
}
