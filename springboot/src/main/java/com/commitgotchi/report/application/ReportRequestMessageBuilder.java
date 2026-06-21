package com.commitgotchi.report.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.domain.CharacterEmotion;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

@Component
public class ReportRequestMessageBuilder {

    public ReportRequestMessage build(
            String requestId,
            long userId,
            LocalDate targetDate,
            String weeklyStudyStreak,
            FastApiScoreDelta scoreDeltaHint,
            String focus,
            CharacterSnapshot character,
            ReportRequestMessage.DailyReport dailyReport
    ) {
        CharacterSnapshot snapshot = requireNonNull(character, "character");
        return new ReportRequestMessage(
                requireText(requestId, "requestId"),
                requirePositive(userId, "userId"),
                requireNonNull(targetDate, "targetDate"),
                new ReportRequestMessage.UserMetadata(
                        requireText(weeklyStudyStreak, "weeklyStudyStreak"),
                        new ReportRequestMessage.ReportDirection(
                                requireNonNull(scoreDeltaHint, "scoreDeltaHint"),
                                requireText(focus, "focus")
                        )
                ),
                new ReportRequestMessage.CharacterMetadata(
                        requirePositive(snapshot.characterId(), "characterId"),
                        requireText(snapshot.name(), "name"),
                        requireText(snapshot.personality(), "personality"),
                        requireNonNull(snapshot.emotion(), "emotion").name(),
                        new ReportRequestMessage.CurrentStats(
                                snapshot.statDb(),
                                snapshot.statAlgorithm(),
                                snapshot.statCs(),
                                snapshot.statNetwork(),
                                snapshot.statFramework()
                        )
                ),
                requireNonNull(dailyReport, "dailyReport")
        );
    }

    public record CharacterSnapshot(
            long characterId,
            String name,
            String personality,
            CharacterEmotion emotion,
            int statDb,
            int statAlgorithm,
            int statCs,
            int statNetwork,
            int statFramework
    ) {
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }
}
