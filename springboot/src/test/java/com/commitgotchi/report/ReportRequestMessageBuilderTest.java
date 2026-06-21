package com.commitgotchi.report;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.domain.CharacterEmotion;
import com.commitgotchi.report.application.ReportRequestMessage;
import com.commitgotchi.report.application.ReportRequestMessageBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReportRequestMessageBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ReportRequestMessageBuilder builder = new ReportRequestMessageBuilder();

    @Test
    void reportRequestMessageIncludesUppercaseEmotionAndFastApiStatKeys() throws Exception {
        ReportRequestMessage message = builder.build(
                "report-request-1",
                42L,
                LocalDate.of(2026, 6, 19),
                "0100011",
                new FastApiScoreDelta(0, 3, 0, 1, 0),
                "Focus on algorithms and networking.",
                new ReportRequestMessageBuilder.CharacterSnapshot(
                        10L,
                        "Commit Monster",
                        "Precise and kind",
                        CharacterEmotion.ANGRY,
                        120,
                        200,
                        80,
                        60,
                        140
                ),
                new ReportRequestMessage.DailyReport("오늘 학습 기록", "Spring JPA")
        );

        JsonNode json = objectMapper.valueToTree(message);

        assertThat(json.path("characterMetadata").path("emotion").asText()).isEqualTo("ANGRY");
        JsonNode currentStats = json.path("characterMetadata").path("currentStats");
        assertThat(currentStats.has("db")).isTrue();
        assertThat(currentStats.has("algorithm")).isTrue();
        assertThat(currentStats.has("cs")).isTrue();
        assertThat(currentStats.has("network")).isTrue();
        assertThat(currentStats.has("framework")).isTrue();
        assertThat(currentStats.has("algo")).isFalse();
        assertThat(currentStats.has("net")).isFalse();
        assertThat(currentStats.has("fw")).isFalse();
        JsonNode scoreDeltaHint = json.path("userMetadata").path("reportDirection").path("scoreDeltaHint");
        assertThat(scoreDeltaHint.has("algorithm")).isTrue();
        assertThat(scoreDeltaHint.has("algorithmDelta")).isFalse();
        assertThat(scoreDeltaHint.has("dbDelta")).isFalse();
    }
}
