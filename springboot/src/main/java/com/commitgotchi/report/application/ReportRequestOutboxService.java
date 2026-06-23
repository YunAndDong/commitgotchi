package com.commitgotchi.report.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.report.domain.ReportRequestOutbox;
import com.commitgotchi.report.domain.ReportRequestOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ReportRequestOutboxService {

    private final LearningCharacterRepository characterRepository;
    private final ReportRequestMessageBuilder messageBuilder;
    private final ReportRequestOutboxRepository outboxRepository;

    public ReportRequestOutboxService(
            LearningCharacterRepository characterRepository,
            ReportRequestMessageBuilder messageBuilder,
            ReportRequestOutboxRepository outboxRepository
    ) {
        this.characterRepository = characterRepository;
        this.messageBuilder = messageBuilder;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public ReportRequestOutbox createOrRefreshSnapshot(
            long userId,
            long characterId,
            LocalDate targetDate,
            JsonNode report,
            JsonNode state
    ) {
        LearningCharacter character = characterRepository.findByIdAndUserIdForUpdate(characterId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Report character does not belong to user."));
        FastApiScoreDelta scoreDeltaHint = scoreDeltaHint(report.path("tags"));
        String requestId = requestIdFor(userId, characterId, targetDate);
        ReportRequestMessage message = messageBuilder.build(
                requestId,
                userId,
                targetDate,
                weeklyStudyStreak(state, targetDate),
                scoreDeltaHint,
                reportFocus(scoreDeltaHint),
                new ReportRequestMessageBuilder.CharacterSnapshot(
                        character.getId(),
                        character.getName(),
                        character.getPersonality(),
                        character.getEmotion(),
                        character.getStatDb(),
                        character.getStatAlgorithm(),
                        character.getStatCs(),
                        character.getStatNetwork(),
                        character.getStatFramework()
                ),
                new ReportRequestMessage.DailyReport(
                        requiredReportText(report, "title"),
                        requiredReportText(report, "content")
                )
        );

        return outboxRepository.upsertPendingSnapshot(ReportRequestOutbox.pending(
                message.requestId(),
                message.userId(),
                message.characterMetadata().characterId(),
                message.targetDate(),
                message.dailyReport().title(),
                message.dailyReport().content(),
                message.userMetadata().weeklyStudyStreak(),
                message.userMetadata().reportDirection().focus(),
                message.characterMetadata().name(),
                message.characterMetadata().personality(),
                message.characterMetadata().emotion(),
                message.characterMetadata().currentStats().db(),
                message.characterMetadata().currentStats().algorithm(),
                message.characterMetadata().currentStats().cs(),
                message.characterMetadata().currentStats().network(),
                message.characterMetadata().currentStats().framework(),
                message.userMetadata().reportDirection().scoreDeltaHint(),
                Instant.now()
        ));
    }

    public String requestIdFor(long userId, long characterId, LocalDate targetDate) {
        return "report-request-%d-%s-%d".formatted(userId, targetDate, characterId);
    }

    private String weeklyStudyStreak(JsonNode state, LocalDate targetDate) {
        Set<LocalDate> reportDates = new HashSet<>();
        JsonNode reports = state.path("reports");
        if (reports instanceof ArrayNode array) {
            for (JsonNode node : array) {
                LocalDate date = parseDate(node.path("date").asText(null));
                if (date != null) {
                    reportDates.add(date);
                }
            }
        }

        StringBuilder streak = new StringBuilder(7);
        for (int daysAgo = 6; daysAgo >= 0; daysAgo--) {
            streak.append(reportDates.contains(targetDate.minusDays(daysAgo)) ? '1' : '0');
        }
        return streak.toString();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private FastApiScoreDelta scoreDeltaHint(JsonNode tags) {
        int db = 0;
        int algorithm = 0;
        int cs = 0;
        int network = 0;
        int framework = 0;
        if (tags != null && tags.isArray()) {
            for (JsonNode tagNode : tags) {
                String tag = tagNode.asText("").strip().toLowerCase(Locale.ROOT);
                switch (tag) {
                    case "db", "database", "sql" -> db += 2;
                    case "algo", "algorithm", "algorithms", "dp", "greedy" -> algorithm += 3;
                    case "cs", "computer-science", "os" -> cs += 2;
                    case "net", "network", "networking", "tcp", "http" -> network += 2;
                    case "fw", "framework", "spring", "springboot", "vue" -> framework += 2;
                    default -> {
                    }
                }
            }
        }
        if (db + algorithm + cs + network + framework == 0) {
            algorithm = 1;
        }
        return new FastApiScoreDelta(
                Math.min(db, 10),
                Math.min(algorithm, 10),
                Math.min(cs, 10),
                Math.min(network, 10),
                Math.min(framework, 10)
        );
    }

    private String reportFocus(FastApiScoreDelta scoreDeltaHint) {
        List<String> focusAreas = new ArrayList<>();
        if (scoreDeltaHint.dbDelta() > 0) {
            focusAreas.add("DB");
        }
        if (scoreDeltaHint.algorithmDelta() > 0) {
            focusAreas.add("알고리즘");
        }
        if (scoreDeltaHint.csDelta() > 0) {
            focusAreas.add("CS");
        }
        if (scoreDeltaHint.networkDelta() > 0) {
            focusAreas.add("네트워크");
        }
        if (scoreDeltaHint.frameworkDelta() > 0) {
            focusAreas.add("프레임워크");
        }
        return String.join(", ", focusAreas) + " 학습 증가분을 중심으로 코멘트";
    }

    private String requiredReportText(JsonNode report, String field) {
        String value = report.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("report " + field + " must not be blank");
        }
        return value;
    }
}
