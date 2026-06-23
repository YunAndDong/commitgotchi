package com.commitgotchi.report.scheduler;

import com.commitgotchi.report.application.ReportMidnightEnqueueService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReportMidnightSchedulerTest {

    @Test
    void scheduledMethodCalculatesYesterdayUsingAsiaSeoul() {
        ReportMidnightEnqueueService service = mock(ReportMidnightEnqueueService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-22T15:05:00Z"), ZoneId.of("UTC"));
        ReportMidnightScheduler scheduler = new ReportMidnightScheduler(service, ZoneId.of("Asia/Seoul"), clock);

        scheduler.enqueueYesterday();

        verify(service).enqueueForTargetDate(LocalDate.of(2026, 6, 22));
    }

    @Test
    void scheduledMethodUsesConfigurableSixFieldCronAndAsiaSeoulZone() throws NoSuchMethodException {
        Method method = ReportMidnightScheduler.class.getMethod("enqueueYesterday");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${commitgotchi.report.scheduler.midnight-cron:0 0 0 * * *}");
        assertThat(scheduled.zone()).isEqualTo("${commitgotchi.report.scheduler.zone:Asia/Seoul}");
    }

    @Test
    void schedulerCanBeDisabledByProperty() {
        ConditionalOnProperty conditional = ReportMidnightScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.prefix()).isEqualTo("commitgotchi.report.scheduler");
        assertThat(conditional.name()).containsExactly("enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(conditional.matchIfMissing()).isTrue();
    }
}
