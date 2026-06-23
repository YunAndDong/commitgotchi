package com.commitgotchi.report.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class DailyReportProjectionService {

    private final ObjectMapper objectMapper;

    public DailyReportProjectionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode overlay(long userId, ObjectNode state) {
        ArrayNode reports = sortedObjectArray(state.path("reports"));
        ArrayNode quizzes = sortedObjectArray(state.path("quizzes"));
        state.set("reports", reports);
        state.set("quizzes", quizzes);

        ObjectNode dailyReport = projectDailyReport(state.path("dailyReport"), reports, quizzes);
        state.set("dailyReport", dailyReport);
        return dailyReport;
    }

    private ObjectNode projectDailyReport(JsonNode existingNode, ArrayNode reports, ArrayNode quizzes) {
        ObjectNode existing = existingNode instanceof ObjectNode object
                ? object.deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode sourceReport = sourceReport(existing, reports);
        String status = publicStatus(existing, sourceReport);

        ObjectNode daily = objectMapper.createObjectNode();
        daily.put("status", status);
        putOrNull(daily, "date", firstText(existing.path("date"), sourceReport == null ? null : sourceReport.path("date")));
        putCharacterId(daily, existing.path("characterId"), sourceReport == null ? null : sourceReport.path("characterId"));
        putOrNull(daily, "requestId", firstText(existing.path("requestId"), sourceReport == null ? null : sourceReport.path("requestId")));

        if ("pending".equals(status)) {
            daily.put("message", "일일 리포트는 오전 9시 제공을 목표로 준비 중이에요.");
            nullIfMissing(daily, existing, "summary");
            nullIfMissing(daily, existing, "text");
            nullIfMissing(daily, existing, "feedback");
            nullIfMissing(daily, existing, "deltas");
            nullIfMissing(daily, existing, "quizComment");
            nullIfMissing(daily, existing, "nextRecommendation");
            daily.set("recommendedQuizIds", objectMapper.createArrayNode());
            return daily;
        }

        copyResultFields(daily, existing);
        if ("ready".equals(status)) {
            daily.put("message", "어제 학습 리포트가 도착했어요.");
        } else if ("fallback".equals(status)) {
            daily.put("message", "일부 분석이 지연되어 기본 리포트로 이어갈게요.");
            daily.set("deltas", zeroDeltas());
        } else {
            daily.put("message", "리포트 분석이 지연됐어요. 학습 기록은 보존되어 있습니다.");
            daily.set("deltas", zeroDeltas());
        }
        daily.set("recommendedQuizIds", recommendedQuizIds(daily.path("requestId").asText(""), quizzes));
        return daily;
    }

    private ObjectNode sourceReport(ObjectNode existing, ArrayNode reports) {
        String requestId = existing.path("requestId").asText("");
        if (!requestId.isBlank()) {
            for (JsonNode node : reports) {
                if (requestId.equals(node.path("requestId").asText()) && node instanceof ObjectNode object) {
                    return object;
                }
            }
        }
        if (!reports.isEmpty() && reports.get(0) instanceof ObjectNode object) {
            return object;
        }
        return null;
    }

    private String publicStatus(ObjectNode existing, ObjectNode sourceReport) {
        String reportStatus = sourceReport == null ? "" : sourceReport.path("status").asText("");
        if ("reflected".equals(reportStatus) || "ready".equals(reportStatus)) {
            return "ready";
        }
        if ("fallback".equals(reportStatus)) {
            return "fallback";
        }
        if ("failed".equals(reportStatus)) {
            return "failed";
        }
        if ("analyzing".equals(reportStatus)) {
            return "pending";
        }
        String existingStatus = existing.path("status").asText("pending");
        if ("ready".equals(existingStatus) || "fallback".equals(existingStatus) || "failed".equals(existingStatus)) {
            return existingStatus;
        }
        return "pending";
    }

    private void copyResultFields(ObjectNode daily, ObjectNode existing) {
        copyOrNull(daily, existing, "summary");
        copyOrNull(daily, existing, "text");
        copyOrNull(daily, existing, "feedback");
        if (existing.path("deltas").isObject()) {
            daily.set("deltas", existing.path("deltas").deepCopy());
        } else {
            daily.set("deltas", zeroDeltas());
        }
        copyOrNull(daily, existing, "quizComment");
        copyOrNull(daily, existing, "nextRecommendation");
    }

    private ArrayNode recommendedQuizIds(String requestId, ArrayNode quizzes) {
        ArrayNode ids = objectMapper.createArrayNode();
        if (requestId.isBlank()) {
            return ids;
        }
        for (JsonNode quiz : quizzes) {
            if (requestId.equals(quiz.path("sourceReportRequestId").asText(""))) {
                ids.add(quiz.path("id").asText());
            }
        }
        return ids;
    }

    private ArrayNode sortedObjectArray(JsonNode source) {
        List<ObjectNode> nodes = new ArrayList<>();
        if (source != null && source.isArray()) {
            for (JsonNode node : source) {
                if (node instanceof ObjectNode object) {
                    nodes.add(object.deepCopy());
                }
            }
        }
        nodes.sort(Comparator.comparing(
                (ObjectNode node) -> parseDate(node.path("date").asText("")),
                Comparator.nullsLast(Comparator.reverseOrder())
        ));
        ArrayNode sorted = objectMapper.createArrayNode();
        nodes.forEach(sorted::add);
        return sorted;
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

    private String firstText(JsonNode first, JsonNode second) {
        if (first != null && !first.isMissingNode() && !first.isNull() && !first.asText("").isBlank()) {
            return first.asText();
        }
        if (second != null && !second.isMissingNode() && !second.isNull() && !second.asText("").isBlank()) {
            return second.asText();
        }
        return null;
    }

    private void putCharacterId(ObjectNode target, JsonNode preferred, JsonNode fallback) {
        JsonNode value = valueOrFallback(preferred, fallback);
        if (value == null || value.isMissingNode() || value.isNull() || value.asText("").isBlank()) {
            target.set("characterId", NullNode.instance);
            return;
        }
        target.set("characterId", value.deepCopy());
    }

    private JsonNode valueOrFallback(JsonNode preferred, JsonNode fallback) {
        if (preferred != null && !preferred.isMissingNode() && !preferred.isNull() && !preferred.asText("").isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private void putOrNull(ObjectNode target, String field, String value) {
        if (value == null || value.isBlank()) {
            target.set(field, NullNode.instance);
        } else {
            target.put(field, value);
        }
    }

    private void copyOrNull(ObjectNode target, ObjectNode source, String field) {
        JsonNode value = source.path(field);
        if (value.isMissingNode()) {
            target.set(field, NullNode.instance);
        } else {
            target.set(field, value.deepCopy());
        }
    }

    private void nullIfMissing(ObjectNode target, ObjectNode source, String field) {
        JsonNode value = source.path(field);
        target.set(field, value.isMissingNode() ? NullNode.instance : value.deepCopy());
    }

    private ObjectNode zeroDeltas() {
        return objectMapper.createObjectNode()
                .put("algo", 0)
                .put("cs", 0)
                .put("db", 0)
                .put("net", 0)
                .put("fw", 0);
    }
}
