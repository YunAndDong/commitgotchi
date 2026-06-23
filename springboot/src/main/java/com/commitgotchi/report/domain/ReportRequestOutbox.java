package com.commitgotchi.report.domain;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public class ReportRequestOutbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private String requestId;
    private long userId;
    private long characterId;
    private LocalDate targetDate;
    private String reportTitle;
    private String reportContent;
    private String weeklyStudyStreak;
    private String focus;
    private String characterName;
    private String characterPersonality;
    private String characterEmotion;
    private int characterStatDb;
    private int characterStatAlgorithm;
    private int characterStatCs;
    private int characterStatNetwork;
    private int characterStatFramework;
    private int scoreDeltaHintDb;
    private int scoreDeltaHintAlgorithm;
    private int scoreDeltaHintCs;
    private int scoreDeltaHintNetwork;
    private int scoreDeltaHintFramework;
    private String status;
    private int attemptCount;
    private Instant availableAt;
    private Instant sentAt;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;

    protected ReportRequestOutbox() {
    }

    private ReportRequestOutbox(
            String requestId,
            long userId,
            long characterId,
            LocalDate targetDate,
            String reportTitle,
            String reportContent,
            String weeklyStudyStreak,
            String focus,
            String characterName,
            String characterPersonality,
            String characterEmotion,
            int characterStatDb,
            int characterStatAlgorithm,
            int characterStatCs,
            int characterStatNetwork,
            int characterStatFramework,
            FastApiScoreDelta scoreDeltaHint,
            Instant availableAt
    ) {
        this.requestId = requireText(requestId, "requestId", 120);
        this.userId = requirePositive(userId, "userId");
        this.characterId = requirePositive(characterId, "characterId");
        this.targetDate = Objects.requireNonNull(targetDate, "targetDate must not be null");
        this.reportTitle = requireText(reportTitle, "reportTitle", 200);
        this.reportContent = requireText(reportContent, "reportContent");
        this.weeklyStudyStreak = requireWeeklyStudyStreak(weeklyStudyStreak);
        this.focus = requireText(focus, "focus", 300);
        this.characterName = requireText(characterName, "characterName", 40);
        this.characterPersonality = requireText(characterPersonality, "characterPersonality", 500);
        this.characterEmotion = requireCharacterEmotion(characterEmotion);
        this.characterStatDb = requireNonNegative(characterStatDb, "characterStatDb");
        this.characterStatAlgorithm = requireNonNegative(characterStatAlgorithm, "characterStatAlgorithm");
        this.characterStatCs = requireNonNegative(characterStatCs, "characterStatCs");
        this.characterStatNetwork = requireNonNegative(characterStatNetwork, "characterStatNetwork");
        this.characterStatFramework = requireNonNegative(characterStatFramework, "characterStatFramework");
        FastApiScoreDelta hint = Objects.requireNonNull(scoreDeltaHint, "scoreDeltaHint must not be null");
        this.scoreDeltaHintDb = clampScoreHint(hint.dbDelta());
        this.scoreDeltaHintAlgorithm = clampScoreHint(hint.algorithmDelta());
        this.scoreDeltaHintCs = clampScoreHint(hint.csDelta());
        this.scoreDeltaHintNetwork = clampScoreHint(hint.networkDelta());
        this.scoreDeltaHintFramework = clampScoreHint(hint.frameworkDelta());
        this.status = STATUS_PENDING;
        this.attemptCount = 0;
        this.availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
    }

    public static ReportRequestOutbox pending(
            String requestId,
            long userId,
            long characterId,
            LocalDate targetDate,
            String reportTitle,
            String reportContent,
            String weeklyStudyStreak,
            String focus,
            String characterName,
            String characterPersonality,
            String characterEmotion,
            int characterStatDb,
            int characterStatAlgorithm,
            int characterStatCs,
            int characterStatNetwork,
            int characterStatFramework,
            FastApiScoreDelta scoreDeltaHint,
            Instant availableAt
    ) {
        return new ReportRequestOutbox(
                requestId,
                userId,
                characterId,
                targetDate,
                reportTitle,
                reportContent,
                weeklyStudyStreak,
                focus,
                characterName,
                characterPersonality,
                characterEmotion,
                characterStatDb,
                characterStatAlgorithm,
                characterStatCs,
                characterStatNetwork,
                characterStatFramework,
                scoreDeltaHint,
                availableAt
        );
    }

    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        String text = requireText(value, fieldName);
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength + " characters");
        }
        return text;
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static String requireWeeklyStudyStreak(String value) {
        String streak = requireText(value, "weeklyStudyStreak");
        if (!streak.matches("[01]{7}")) {
            throw new IllegalArgumentException("weeklyStudyStreak must be a 7-character 0/1 string");
        }
        return streak;
    }

    private static int clampScoreHint(int value) {
        return Math.max(0, Math.min(10, value));
    }

    private static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
        return value;
    }

    private static String requireCharacterEmotion(String value) {
        String emotion = requireText(value, "characterEmotion", 20);
        if (!emotion.equals("JOY") && !emotion.equals("SAD") && !emotion.equals("ANGRY")) {
            throw new IllegalArgumentException("characterEmotion must be JOY, SAD, or ANGRY");
        }
        return emotion;
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public long getUserId() {
        return userId;
    }

    public long getCharacterId() {
        return characterId;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public String getReportTitle() {
        return reportTitle;
    }

    public String getReportContent() {
        return reportContent;
    }

    public String getWeeklyStudyStreak() {
        return weeklyStudyStreak;
    }

    public String getFocus() {
        return focus;
    }

    public String getCharacterName() {
        return characterName;
    }

    public String getCharacterPersonality() {
        return characterPersonality;
    }

    public String getCharacterEmotion() {
        return characterEmotion;
    }

    public int getCharacterStatDb() {
        return characterStatDb;
    }

    public int getCharacterStatAlgorithm() {
        return characterStatAlgorithm;
    }

    public int getCharacterStatCs() {
        return characterStatCs;
    }

    public int getCharacterStatNetwork() {
        return characterStatNetwork;
    }

    public int getCharacterStatFramework() {
        return characterStatFramework;
    }

    public int getScoreDeltaHintDb() {
        return scoreDeltaHintDb;
    }

    public int getScoreDeltaHintAlgorithm() {
        return scoreDeltaHintAlgorithm;
    }

    public int getScoreDeltaHintCs() {
        return scoreDeltaHintCs;
    }

    public int getScoreDeltaHintNetwork() {
        return scoreDeltaHintNetwork;
    }

    public int getScoreDeltaHintFramework() {
        return scoreDeltaHintFramework;
    }

    public String getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
