package com.commitgotchi.report.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.application.CharacterCommandService;
import com.commitgotchi.character.application.CharacterEventService;
import com.commitgotchi.character.domain.CharacterEmotion;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.game.domain.GameState;
import com.commitgotchi.game.domain.GameStateRepository;
import com.commitgotchi.report.api.dto.ReportCallbackRequest;
import com.commitgotchi.report.api.dto.ReportCallbackResponse;
import com.commitgotchi.report.api.dto.ReportCallbackStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ReportCallbackService {

    private final CharacterCommandService characterCommandService;
    private final CharacterEventService characterEventService;
    private final ReportEventService reportEventService;
    private final GameStateRepository gameStateRepository;
    private final ObjectMapper objectMapper;

    public ReportCallbackService(
            CharacterCommandService characterCommandService,
            CharacterEventService characterEventService,
            ReportEventService reportEventService,
            GameStateRepository gameStateRepository,
            ObjectMapper objectMapper
    ) {
        this.characterCommandService = characterCommandService;
        this.characterEventService = characterEventService;
        this.reportEventService = reportEventService;
        this.gameStateRepository = gameStateRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReportCallbackResponse handle(ReportCallbackRequest request) {
        if (characterCommandService.findOwned(request.userId(), request.characterId()).isEmpty()) {
            throw new IllegalArgumentException("Report callback character does not belong to user.");
        }

        ReportStateUpdate stateUpdate = applyReportResult(request);
        if (stateUpdate.duplicate()) {
            return ReportCallbackResponse.duplicateResult();
        }

        FastApiScoreDelta delta = growthDelta(request);
        Optional<LearningCharacter> updated = characterCommandService.applyScoreDeltas(
                request.userId(),
                request.characterId(),
                delta.dbDelta(),
                delta.algorithmDelta(),
                delta.csDelta(),
                delta.networkDelta(),
                delta.frameworkDelta(),
                decideEmotion(request.status(), delta),
                request.statusMessage()
        );
        updated.ifPresent(character -> characterEventService.publishCharacterUpdatedAfterCommit(request.userId(), character));
        if (request.status() == ReportCallbackStatus.SUCCESS) {
            reportEventService.publishReportReadyAfterCommit(request.userId());
        } else {
            reportEventService.publishReportFailedAfterCommit(request.userId());
        }
        return ReportCallbackResponse.acceptedResult();
    }

    private ReportStateUpdate applyReportResult(ReportCallbackRequest request) {
        GameState entity = gameStateRepository.findByIdForUpdate(request.userId())
                .orElseGet(() -> GameState.create(request.userId(), stringifyForPersistence(emptyState())));
        ObjectNode state = parse(entity.getStateJson());
        ArrayNode reports = array(state, "reports");
        if (alreadyProcessed(state, reports, request.requestId())) {
            return new ReportStateUpdate(true);
        }

        FastApiScoreDelta publicDelta = request.status() == ReportCallbackStatus.SUCCESS
                ? request.scoreDelta()
                : new FastApiScoreDelta(0, 0, 0, 0, 0);
        ObjectNode report = findReport(reports, request).orElseGet(() -> {
            ObjectNode created = objectMapper.createObjectNode();
            created.put("id", nextId(state, "r"));
            reports.insert(0, created);
            return created;
        });
        report.put("requestId", request.requestId());
        report.put("date", request.targetDate().toString());
        report.put("characterId", Long.toString(request.characterId()));
        report.put("status", request.status() == ReportCallbackStatus.SUCCESS ? "reflected" : "fallback");
        report.put("scoreApplied", request.status() == ReportCallbackStatus.SUCCESS);
        report.set("deltas", uiDeltas(publicDelta));
        report.put("summary", reportText(request.dailyReport(), "text", request.statusMessage()));
        report.put("feedback", reportText(request.dailyReport(), "feedback", request.statusMessage()));

        ArrayNode recommendedQuizIds = appendRecommendedQuizzes(state, request);

        ObjectNode daily = objectMapper.createObjectNode();
        daily.put("status", request.status() == ReportCallbackStatus.SUCCESS ? "ready" : "fallback");
        daily.put("date", request.targetDate().toString());
        daily.put("characterId", Long.toString(request.characterId()));
        daily.put("requestId", request.requestId());
        daily.put("summary", reportText(request.dailyReport(), "text", request.statusMessage()));
        daily.put("text", reportText(request.dailyReport(), "text", request.statusMessage()));
        daily.put("feedback", reportText(request.dailyReport(), "feedback", request.statusMessage()));
        daily.set("deltas", uiDeltas(publicDelta));
        daily.put("quizComment", request.recommendedQuizzes().isEmpty()
                ? "오늘은 복습 리포트만 확인해도 좋아요."
                : "추천 퀴즈로 어제 배운 내용을 한 번 더 확인해 보세요.");
        daily.set("nextRecommendation", safeCopy(request.nextRecommendation()));
        daily.set("recommendedQuizIds", recommendedQuizIds);
        state.set("dailyReport", daily);
        state.put("notice", request.status() == ReportCallbackStatus.SUCCESS
                ? "어제의 리포트 도착 - 학습이 반영됐어요."
                : "AI 분석이 일부 지연되어 기본 리포트로 이어갈게요.");

        entity.updateStateJson(stringifyForPersistence(state));
        gameStateRepository.save(entity);
        return new ReportStateUpdate(false);
    }

    private boolean alreadyProcessed(ObjectNode state, ArrayNode reports, String requestId) {
        if (isProcessed(state.path("dailyReport"), requestId)) {
            return true;
        }
        for (JsonNode node : reports) {
            if (isProcessed(node, requestId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isProcessed(JsonNode node, String requestId) {
        if (node == null || !requestId.equals(node.path("requestId").asText())) {
            return false;
        }
        String status = node.path("status").asText("");
        return "ready".equals(status)
                || "reflected".equals(status)
                || "fallback".equals(status)
                || "failed".equals(status);
    }

    private Optional<ObjectNode> findReport(ArrayNode reports, ReportCallbackRequest request) {
        for (JsonNode node : reports) {
            if (!(node instanceof ObjectNode report)) {
                continue;
            }
            if (request.requestId().equals(report.path("requestId").asText())) {
                return Optional.of(report);
            }
            if (request.targetDate().toString().equals(report.path("date").asText())
                    && Long.toString(request.characterId()).equals(report.path("characterId").asText())) {
                return Optional.of(report);
            }
        }
        return Optional.empty();
    }

    private ArrayNode appendRecommendedQuizzes(ObjectNode state, ReportCallbackRequest request) {
        ArrayNode ids = objectMapper.createArrayNode();
        ArrayNode quizzes = array(state, "quizzes");
        for (JsonNode node : quizzes) {
            if (request.requestId().equals(node.path("sourceReportRequestId").asText(""))) {
                ids.add(node.path("id").asText());
            }
        }
        if (!ids.isEmpty()) {
            return ids;
        }

        for (ReportCallbackRequest.RecommendedQuiz quiz : request.recommendedQuizzes()) {
            String id = nextId(state, "q");
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", id);
            item.put("date", request.targetDate().toString());
            item.put("characterId", Long.toString(request.characterId()));
            item.put("sourceReportRequestId", request.requestId());
            if (quiz.problemId() == null) {
                item.set("problemId", NullNode.instance);
                item.put("quizId", Long.parseLong(id.replaceAll("\\D+", "")));
            } else {
                item.put("problemId", quiz.problemId());
                item.put("quizId", quiz.problemId());
            }
            item.put("tag", primaryStat(quiz.scoreAllocation()));
            item.put("question", quiz.question());
            item.put("modelAnswer", quiz.modelAnswer());
            item.set("scoreAllocation", apiDeltas(quiz.scoreAllocation()));
            item.put("submitted", false);
            item.put("scored", false);
            item.put("gradeFailed", false);
            item.put("grading", false);
            item.put("deltaAmount", 0);
            item.put("deltaStat", primaryStat(quiz.scoreAllocation()));
            item.set("userAnswer", NullNode.instance);
            item.set("correct", NullNode.instance);
            item.set("feedback", NullNode.instance);
            item.set("options", objectMapper.createArrayNode());
            quizzes.add(item);
            ids.add(id);
        }
        return ids;
    }

    private FastApiScoreDelta growthDelta(ReportCallbackRequest request) {
        if (request.status() == ReportCallbackStatus.FALLBACK) {
            return new FastApiScoreDelta(0, 0, 0, 0, 0);
        }
        return request.scoreDelta();
    }

    private CharacterEmotion decideEmotion(ReportCallbackStatus status, FastApiScoreDelta delta) {
        if (status == ReportCallbackStatus.FALLBACK) {
            return CharacterEmotion.SAD;
        }
        return delta.sum() > 0 ? CharacterEmotion.JOY : CharacterEmotion.SAD;
    }

    private String reportText(JsonNode report, String field, String fallback) {
        if (report != null && report.has(field) && !report.path(field).asText("").isBlank()) {
            return report.path(field).asText().strip();
        }
        return fallback;
    }

    private ObjectNode uiDeltas(FastApiScoreDelta delta) {
        return objectMapper.createObjectNode()
                .put("algo", delta.algorithmDelta())
                .put("cs", delta.csDelta())
                .put("db", delta.dbDelta())
                .put("net", delta.networkDelta())
                .put("fw", delta.frameworkDelta());
    }

    private ObjectNode apiDeltas(FastApiScoreDelta delta) {
        return objectMapper.createObjectNode()
                .put("db", delta.dbDelta())
                .put("algorithm", delta.algorithmDelta())
                .put("cs", delta.csDelta())
                .put("network", delta.networkDelta())
                .put("framework", delta.frameworkDelta());
    }

    private String primaryStat(FastApiScoreDelta delta) {
        int max = delta.algorithmDelta();
        String stat = "algo";
        if (delta.csDelta() > max) {
            max = delta.csDelta();
            stat = "cs";
        }
        if (delta.dbDelta() > max) {
            max = delta.dbDelta();
            stat = "db";
        }
        if (delta.networkDelta() > max) {
            max = delta.networkDelta();
            stat = "net";
        }
        if (delta.frameworkDelta() > max) {
            stat = "fw";
        }
        return stat;
    }

    private JsonNode safeCopy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return NullNode.instance;
        }
        return node.deepCopy();
    }

    private ObjectNode parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node instanceof ObjectNode object) {
                return object;
            }
            return emptyState();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored game state is not valid JSON.", exception);
        }
    }

    private ObjectNode emptyState() {
        ObjectNode state = objectMapper.createObjectNode();
        state.put("nextId", 100);
        state.set("reports", objectMapper.createArrayNode());
        state.set("quizzes", objectMapper.createArrayNode());
        state.set("dailyReport", NullNode.instance);
        state.set("notice", NullNode.instance);
        return state;
    }

    private ArrayNode array(ObjectNode object, String field) {
        JsonNode existing = object.path(field);
        if (existing instanceof ArrayNode array) {
            return array;
        }
        ArrayNode created = objectMapper.createArrayNode();
        object.set(field, created);
        return created;
    }

    private String nextId(ObjectNode state, String prefix) {
        int next = state.path("nextId").asInt(100) + 1;
        state.put("nextId", next);
        return prefix + next;
    }

    private String stringifyForPersistence(ObjectNode state) {
        ObjectNode persisted = state.deepCopy();
        persisted.set("characters", objectMapper.createArrayNode());
        return stringify(persisted);
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize game state.", exception);
        }
    }

    private record ReportStateUpdate(boolean duplicate) {
    }
}
