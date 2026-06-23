package com.commitgotchi.report.application;

import java.time.LocalDate;

public record ReportMidnightEnqueueResult(
        LocalDate targetDate,
        String jobId,
        int candidateCount,
        int createdCount,
        int updatedCount,
        int skippedCount,
        int failureCount
) {
}
