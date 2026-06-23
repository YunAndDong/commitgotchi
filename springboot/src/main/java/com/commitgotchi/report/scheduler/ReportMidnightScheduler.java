package com.commitgotchi.report.scheduler;

import com.commitgotchi.report.application.ReportMidnightEnqueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
@ConditionalOnProperty(
        prefix = "commitgotchi.report.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReportMidnightScheduler {

    private final ReportMidnightEnqueueService service;
    private final Clock clock;

    @Autowired
    public ReportMidnightScheduler(
            ReportMidnightEnqueueService service,
            @Value("${commitgotchi.report.scheduler.zone:Asia/Seoul}") String zoneId
    ) {
        this(service, ZoneId.of(zoneId), Clock.system(ZoneId.of(zoneId)));
    }

    ReportMidnightScheduler(ReportMidnightEnqueueService service, ZoneId zone, Clock clock) {
        this.service = service;
        this.clock = clock.withZone(zone);
    }

    @Scheduled(
            cron = "${commitgotchi.report.scheduler.midnight-cron:0 0 0 * * *}",
            zone = "${commitgotchi.report.scheduler.zone:Asia/Seoul}"
    )
    public void enqueueYesterday() {
        service.enqueueForTargetDate(LocalDate.now(clock).minusDays(1));
    }
}
