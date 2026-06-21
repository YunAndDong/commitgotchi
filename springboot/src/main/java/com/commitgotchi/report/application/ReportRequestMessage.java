package com.commitgotchi.report.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;

import java.time.LocalDate;

public record ReportRequestMessage(
        String requestId,
        long userId,
        LocalDate targetDate,
        UserMetadata userMetadata,
        CharacterMetadata characterMetadata,
        DailyReport dailyReport
) {

    public record UserMetadata(
            String weeklyStudyStreak,
            ReportDirection reportDirection
    ) {
    }

    public record ReportDirection(
            FastApiScoreDelta scoreDeltaHint,
            String focus
    ) {
    }

    public record CharacterMetadata(
            long characterId,
            String name,
            String personality,
            String emotion,
            CurrentStats currentStats
    ) {
    }

    public record CurrentStats(
            int db,
            int algorithm,
            int cs,
            int network,
            int framework
    ) {
    }

    public record DailyReport(
            String title,
            String content
    ) {
    }
}
