package com.commitgotchi.quiz.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.application.CharacterCommandService;
import com.commitgotchi.character.application.CharacterEventService;
import com.commitgotchi.character.domain.CharacterEmotion;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.game.domain.GameState;
import com.commitgotchi.game.domain.GameStateRepository;
import com.commitgotchi.quiz.api.dto.QuizGradeResultRequest;
import com.commitgotchi.quiz.api.dto.QuizGradeResultResponse;
import com.commitgotchi.quiz.api.dto.QuizGradeStatus;
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
public class QuizGradeResultService {

    private final CharacterCommandService characterCommandService;
    private final CharacterEventService characterEventService;
    private final GameStateRepository gameStateRepository;
    private final ObjectMapper objectMapper;

    public QuizGradeResultService(
            CharacterCommandService characterCommandService,
            CharacterEventService characterEventService,
            GameStateRepository gameStateRepository,
            ObjectMapper objectMapper
    ) {
        this.characterCommandService = characterCommandService;
        this.characterEventService = characterEventService;
        this.gameStateRepository = gameStateRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public QuizGradeResultResponse handle(QuizGradeResultRequest request) {
        validateDeltaBounds(request.scoreAllocation(), request.scoreDelta());
        QuizGrowthDecision decision = toGrowthDecision(request);
        QuizStateUpdate stateUpdate = applyQuizResult(request);
        if (!stateUpdate.found()) {
            return QuizGradeResultResponse.acceptedResult();
        }
        if (stateUpdate.duplicate()) {
            return QuizGradeResultResponse.duplicateResult();
        }
        applyGrowth(request, decision, stateUpdate.characterId())
                .ifPresent(character -> characterEventService.publishCharacterUpdatedAfterCommit(request.userId(), character));
        stateUpdate.publishQuizEvent(characterEventService, request.userId());
        // TODO(BE-3): persist quiz_submissions idempotency by submissionId and apply growth in one transaction.
        return QuizGradeResultResponse.acceptedResult();
    }

    public QuizGrowthDecision toGrowthDecision(QuizGradeResultRequest request) {
        FastApiScoreDelta allocation = request.scoreAllocation();
        FastApiScoreDelta delta = request.scoreDelta();
        if (request.status() == QuizGradeStatus.UNGRADED && delta.sum() != 0) {
            throw new IllegalArgumentException("UNGRADED grade-result must not include positive scoreDelta.");
        }
        return new QuizGrowthDecision(
                delta.dbDelta(),
                delta.algorithmDelta(),
                delta.csDelta(),
                delta.networkDelta(),
                delta.frameworkDelta(),
                request.emotion() != null
                        ? request.emotion()
                        : decideEmotion(request.status(), allocation, delta),
                request.statusMessage()
        );
    }

    private void validateDeltaBounds(FastApiScoreDelta allocation, FastApiScoreDelta delta) {
        requireDeltaWithinAllocation("db", delta.dbDelta(), allocation.dbDelta());
        requireDeltaWithinAllocation("algorithm", delta.algorithmDelta(), allocation.algorithmDelta());
        requireDeltaWithinAllocation("cs", delta.csDelta(), allocation.csDelta());
        requireDeltaWithinAllocation("network", delta.networkDelta(), allocation.networkDelta());
        requireDeltaWithinAllocation("framework", delta.frameworkDelta(), allocation.frameworkDelta());
    }

    private void requireDeltaWithinAllocation(String key, int delta, int allocation) {
        if (delta > allocation) {
            throw new IllegalArgumentException("scoreDelta." + key + " cannot exceed scoreAllocation." + key);
        }
    }

    private CharacterEmotion decideEmotion(QuizGradeStatus status, FastApiScoreDelta allocation, FastApiScoreDelta delta) {
        if (status == QuizGradeStatus.UNGRADED) {
            return CharacterEmotion.SAD;
        }
        int maxScore = allocation.sum();
        if (maxScore == 0) {
            return CharacterEmotion.SAD;
        }
        return delta.sum() * 10 >= maxScore * 6 ? CharacterEmotion.JOY : CharacterEmotion.SAD;
    }

    private Optional<LearningCharacter> applyGrowth(
            QuizGradeResultRequest request,
            QuizGrowthDecision decision,
            Long stateCharacterId
    ) {
        if (stateCharacterId == null) {
            return Optional.empty();
        }
        return characterCommandService.applyScoreDeltas(
                request.userId(),
                stateCharacterId,
                decision.dbDelta(),
                decision.algorithmDelta(),
                decision.csDelta(),
                decision.networkDelta(),
                decision.frameworkDelta(),
                decision.emotion(),
                decision.statusMessage()
        );
    }

    private QuizStateUpdate applyQuizResult(QuizGradeResultRequest request) {
        Optional<GameState> stateEntity = gameStateRepository.findByIdForUpdate(request.userId());
        if (stateEntity.isEmpty()) {
            return QuizStateUpdate.notFound();
        }

        GameState entity = stateEntity.get();
        ObjectNode state = parse(entity.getStateJson());
        ObjectNode quiz = findQuiz(state, request);
        if (quiz == null) {
            return QuizStateUpdate.notFound();
        }

        if (quiz.path("scored").asBoolean(false)
                || (quiz.path("gradeFailed").asBoolean(false) && !quiz.path("grading").asBoolean(false))) {
            return QuizStateUpdate.duplicate(parseCharacterId(quiz.path("characterId")), quiz.deepCopy());
        }

        quiz.put("submissionId", request.submissionId());
        quiz.put("quizId", request.quizId());
        quiz.put("grading", false);
        quiz.set("scoreAllocation", scoreVector(request.scoreAllocation()));
        quiz.set("scoreDelta", scoreVector(request.scoreDelta()));
        quiz.put("deltaAmount", request.scoreDelta().sum());
        quiz.put("deltaStat", primaryDeltaStat(request.scoreDelta(), quiz.path("deltaStat").asText("algo")));
        quiz.put("feedback", feedbackFor(request));

        if (request.status() == QuizGradeStatus.GRADED) {
            quiz.put("submitted", true);
            quiz.put("scored", true);
            quiz.put("gradeFailed", false);
            quiz.put("correct", isPassingScore(request.scoreAllocation(), request.scoreDelta()));
        } else {
            quiz.put("submitted", false);
            quiz.put("scored", false);
            quiz.put("gradeFailed", true);
            quiz.set("correct", NullNode.instance);
            quiz.put("deltaAmount", 0);
        }

        entity.updateStateJson(stringifyForPersistence(state));
        gameStateRepository.save(entity);
        return QuizStateUpdate.updated(parseCharacterId(quiz.path("characterId")), quiz.deepCopy());
    }

    private ObjectNode findQuiz(ObjectNode state, QuizGradeResultRequest request) {
        if (request.characterId() == null) {
            return null;
        }
        for (JsonNode node : array(state, "quizzes")) {
            if (!(node instanceof ObjectNode quiz)) {
                continue;
            }
            if (quiz.path("quizId").asLong(0L) == request.quizId()
                    && request.characterId().equals(parseCharacterId(quiz.path("characterId")))) {
                return quiz;
            }
        }
        return null;
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

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored game state is not valid JSON.", exception);
        }
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

    private ObjectNode scoreVector(FastApiScoreDelta score) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("db", score.dbDelta());
        node.put("algorithm", score.algorithmDelta());
        node.put("cs", score.csDelta());
        node.put("network", score.networkDelta());
        node.put("framework", score.frameworkDelta());
        return node;
    }

    private String primaryDeltaStat(FastApiScoreDelta delta, String fallback) {
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
            max = delta.frameworkDelta();
            stat = "fw";
        }
        return max > 0 ? stat : fallback;
    }

    private String feedbackFor(QuizGradeResultRequest request) {
        if (request.feedback() != null && !request.feedback().isBlank()) {
            return request.feedback().strip();
        }
        return request.statusMessage();
    }

    private boolean isPassingScore(FastApiScoreDelta allocation, FastApiScoreDelta delta) {
        int maxScore = allocation.sum();
        return maxScore > 0 && delta.sum() * 10 >= maxScore * 6;
    }

    private Long parseCharacterId(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.valueOf(node.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record QuizGrowthDecision(
            int dbDelta,
            int algorithmDelta,
            int csDelta,
            int networkDelta,
            int frameworkDelta,
            CharacterEmotion emotion,
            String statusMessage
    ) {
    }

    private record QuizStateUpdate(boolean found, boolean duplicate, Long characterId, ObjectNode quiz) {

        static QuizStateUpdate notFound() {
            return new QuizStateUpdate(false, false, null, null);
        }

        static QuizStateUpdate updated(Long characterId, ObjectNode quiz) {
            return new QuizStateUpdate(true, false, characterId, quiz);
        }

        static QuizStateUpdate duplicate(Long characterId, ObjectNode quiz) {
            return new QuizStateUpdate(true, true, characterId, quiz);
        }

        void publishQuizEvent(CharacterEventService characterEventService, long userId) {
            if (!found || duplicate || characterId == null || quiz == null) {
                return;
            }
            characterEventService.publishQuizGradedAfterCommit(userId, characterId, quiz);
        }
    }
}
