package com.commitgotchi.report;

import com.commitgotchi.CommitgotchiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;

class ReportSchedulerConfigurationTest {

    @Test
    void applicationEnablesSpringScheduling() {
        assertThat(CommitgotchiApplication.class)
                .hasAnnotation(EnableScheduling.class);
    }
}
