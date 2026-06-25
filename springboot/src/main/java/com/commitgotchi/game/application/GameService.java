package com.commitgotchi.game.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.api.dto.CharacterUpdateRequest;
import com.commitgotchi.character.application.CharacterCommandService;
import com.commitgotchi.character.application.CharacterCreationService;
import com.commitgotchi.character.application.CharacterDeletionResult;
import com.commitgotchi.character.application.CharacterEventService;
import com.commitgotchi.character.application.CharacterGameProjectionService;
import com.commitgotchi.character.application.CharacterImageService;
import com.commitgotchi.character.application.CharacterNotFoundException;
import com.commitgotchi.character.domain.CharacterEmotion;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.game.api.dto.GameMutationResponse;
import com.commitgotchi.game.domain.GameState;
import com.commitgotchi.game.domain.GameStateRepository;
import com.commitgotchi.quiz.application.QuizGradeRequestClient;
import com.commitgotchi.quiz.application.QuizGradeRequestMessage;
import com.commitgotchi.quiz.application.QuizGradeRequestResult;
import com.commitgotchi.quiz.application.QuizGradingProperties;
import com.commitgotchi.report.application.DailyReportProjectionService;
import com.commitgotchi.report.application.ReportCallbackService;
import com.commitgotchi.report.application.ReportOutboxDispatcher;
import com.commitgotchi.report.application.ReportRequestOutboxService;
import com.commitgotchi.report.sqs.ReportQueueProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class GameService {

    private static final int EVOLVE_THRESHOLD = 1000;
    private static final int DEMO_STAT_BOOST_AMOUNT = 200;
    private static final String CURRENT_OWNER = "나";
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<String> STAT_KEYS = List.of("algo", "cs", "db", "net", "fw");

    private final GameStateRepository repository;
    private final ObjectMapper objectMapper;
    private final CharacterCommandService characterCommandService;
    private final CharacterCreationService characterCreationService;
    private final CharacterGameProjectionService characterProjectionService;
    private final CharacterImageService characterImageService;
    private final CharacterEventService characterEventService;
    private final DailyReportProjectionService dailyReportProjectionService;
    private final ReportCallbackService reportCallbackService;
    private final ReportRequestOutboxService reportRequestOutboxService;
    private final ReportOutboxDispatcher reportOutboxDispatcher;
    private final ReportQueueProperties reportQueueProperties;
    private final QuizGradeRequestClient quizGradeRequestClient;
    private final QuizGradingProperties quizGradingProperties;

    public GameService(
            GameStateRepository repository,
            ObjectMapper objectMapper,
            CharacterCommandService characterCommandService,
            CharacterCreationService characterCreationService,
            CharacterGameProjectionService characterProjectionService,
            CharacterImageService characterImageService,
            CharacterEventService characterEventService,
            DailyReportProjectionService dailyReportProjectionService,
            ReportCallbackService reportCallbackService,
            ReportRequestOutboxService reportRequestOutboxService,
            ReportOutboxDispatcher reportOutboxDispatcher,
            ReportQueueProperties reportQueueProperties,
            QuizGradeRequestClient quizGradeRequestClient,
            QuizGradingProperties quizGradingProperties
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.characterCommandService = characterCommandService;
        this.characterCreationService = characterCreationService;
        this.characterProjectionService = characterProjectionService;
        this.characterImageService = characterImageService;
        this.characterEventService = characterEventService;
        this.dailyReportProjectionService = dailyReportProjectionService;
        this.reportCallbackService = reportCallbackService;
        this.reportRequestOutboxService = reportRequestOutboxService;
        this.reportOutboxDispatcher = reportOutboxDispatcher;
        this.reportQueueProperties = reportQueueProperties;
        this.quizGradeRequestClient = quizGradeRequestClient;
        this.quizGradingProperties = quizGradingProperties;
    }

    @Transactional
    public JsonNode state(long userId) {
        return loadWithCharacterProjection(userId);
    }

    public GameMutationResponse createCharacter(long userId, CharacterCreateRequest request) {
        LearningCharacter created = characterCreationService.create(userId, request);
        LearningCharacter character = characterImageService.generateOrFallback(userId, created.getId());
        ObjectNode state = loadOrCreate(userId);
        ObjectNode item = characterProjectionService.project(character);
        applyCharacterProjection(userId, state);
        syncDailyReportCharacter(state, character.getId());
        save(userId, state);
        return response(state, item);
    }

    @Transactional
    public GameMutationResponse getCharacter(long userId, String id) {
        long characterId = parseCharacterIdOrThrow(id);
        LearningCharacter character = characterCommandService.findOwned(userId, characterId)
                .orElseThrow(CharacterNotFoundException::new);
        ObjectNode state = loadWithCharacterProjection(userId);
        return response(state, characterProjectionService.project(character));
    }

    @Transactional
    public GameMutationResponse updateCharacter(long userId, String id, CharacterUpdateRequest request) {
        long characterId = parseCharacterIdOrThrow(id);
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        LearningCharacter character = characterCommandService.update(
                userId,
                characterId,
                request.name(),
                request.keyword(),
                request.personality()
        ).orElseThrow(CharacterNotFoundException::new);
        applyCharacterProjection(userId, state);
        save(userId, state);
        return response(state, characterProjectionService.project(character));
    }

    @Transactional
    public GameMutationResponse setActiveCharacter(long userId, String id) {
        long characterId = parseCharacterIdOrThrow(id);
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        LearningCharacter target = characterCommandService.activate(userId, characterId)
                .orElseThrow(CharacterNotFoundException::new);
        replaceDailyReportCharacter(state, target.getId());
        applyCharacterProjection(userId, state);
        save(userId, state);
        return response(state, characterProjectionService.project(target));
    }

    @Transactional
    public GameMutationResponse retryImage(long userId, String id) {
        long characterId = parseCharacterIdOrThrow(id);
        LearningCharacter character = characterImageService.generateOrFallback(userId, characterId);
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        applyCharacterProjection(userId, state);
        save(userId, state);
        return response(state, characterProjectionService.project(character));
    }

    @Transactional
    public GameMutationResponse boostCharacterStat(long userId, String id, JsonNode request) {
        long characterId = parseCharacterIdOrThrow(id);
        String stat = defaultText(request, "stat", "");
        if (!STAT_KEYS.contains(stat)) {
            throw new IllegalArgumentException("stat must be one of " + STAT_KEYS);
        }

        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode beforeCharacter = findObject(array(state, "characters"), Long.toString(characterId));
        boolean wasEvolved = beforeCharacter != null && beforeCharacter.path("isEvolved").asBoolean(false);
        LearningCharacter updated = characterCommandService.applyScoreDelta(
                userId,
                characterId,
                stat,
                DEMO_STAT_BOOST_AMOUNT,
                CharacterEmotion.JOY,
                "시연용 보너스로 " + displayStat(stat) + " 스탯이 " + DEMO_STAT_BOOST_AMOUNT + " 올랐어요."
        ).orElseThrow(CharacterNotFoundException::new);

        applyCharacterProjection(userId, state);
        save(userId, state);
        characterEventService.publishCharacterUpdatedAfterCommit(userId, updated);
        ObjectNode item = characterProjectionService.project(updated);
        item.put("_evolvedNow", !wasEvolved && updated.isEvolved());
        return response(state, item);
    }

    @Transactional
    public GameMutationResponse deleteCharacter(long userId, String id) {
        long characterId = parseCharacterIdOrThrow(id);
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        CharacterDeletionResult deletion = characterCommandService.delete(userId, characterId)
                .orElseThrow(CharacterNotFoundException::new);
        ObjectNode item = characterProjectionService.project(deletion.removed());
        applyCharacterProjection(userId, state);
        if (deletion.newActive().isPresent()) {
            replaceDailyReportCharacter(state, deletion.newActive().get().getId());
        } else if (deletion.wasActive()) {
            clearActiveCharacter(state);
        } else {
            syncDailyReportAfterDeletedReference(state, deletion.removed().getId());
        }
        syncPendingReportsAfterDeletedReference(state, deletion.removed().getId());
        syncUnscoredQuizzesAfterDeletedReference(state, deletion.removed().getId());
        save(userId, state);
        return response(state, item);
    }

    @Transactional
    public GameMutationResponse saveReport(long userId, JsonNode request) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        String mood = defaultText(request, "mood", "joy");
        LearningCharacter character = characterCommandService.reactActive(
                userId,
                emotionFor(mood),
                reactionFor(mood)
        ).orElse(null);
        if (character == null) {
            return response(state, NullNode.instance);
        }
        LocalDate targetDate = todayDate();
        String today = targetDate.toString();
        String characterId = character.getId().toString();
        ObjectNode report = findReportForDate(state, today, characterId);
        if (report == null) {
            report = objectMapper.createObjectNode()
                    .put("id", nextId(state, "r"))
                    .put("scoreApplied", false);
            array(state, "reports").insert(0, report);
        }
        report.put("date", today)
                .put("mood", mood)
                .put("title", requiredText(request, "title"))
                .put("content", text(request, "content"))
                .put("status", "analyzing")
                .put("characterId", characterId)
                .put("requestId", reportRequestOutboxService.requestIdFor(userId, character.getId(), targetDate));
        report.set("tags", copyArray(request.path("tags")));
        ObjectNode dailyReport = dailyReport(today, characterId, "pending");
        state.set("dailyReport", dailyReport);
        applyCharacterProjection(userId, state);
        state.set("notice", NullNode.instance);
        save(userId, state);
        reportRequestOutboxService.createOrRefreshSnapshot(userId, character.getId(), targetDate, report, state);
        return response(state, report);
    }

    @Transactional
    public GameMutationResponse submitQuiz(long userId, String id, JsonNode request) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode quiz = findObject(array(state, "quizzes"), id);
        if (quiz == null || quiz.path("scored").asBoolean(false)) {
            return response(state, quiz == null ? NullNode.instance : quiz);
        }
        String userAnswer = requiredText(request, "userAnswer");
        quiz.put("submitted", true);
        quiz.put("userAnswer", userAnswer);
        quiz.put("scored", false);
        quiz.put("gradeFailed", false);
        quiz.put("grading", false);
        if (bool(request, "fail")) {
            quiz.put("submitted", false);
            quiz.put("gradeFailed", true);
            quiz.put("grading", false);
            save(userId, state);
            return response(state, objectMapper.createObjectNode().put("ok", false).set("quiz", quiz));
        }

        if (quizGradeRequestClient.isEnabled()) {
            return requestQuizGrade(userId, id, state, quiz, userAnswer);
        }

        return submitQuizLocally(userId, state, quiz, userAnswer);
    }

    @Transactional
    public GameMutationResponse createDemoRecommendedQuizzes(long userId, JsonNode request) {
        ObjectNode currentState = loadWithCharacterProjection(userId);
        Long requestedCharacterId = parseCharacterId(request == null ? null : request.path("characterId"));
        Long characterId = requestedCharacterId == null
                ? parseCharacterId(activeCharacterId(currentState))
                : requestedCharacterId;
        if (characterId == null) {
            return response(currentState, NullNode.instance);
        }
        if (characterCommandService.findOwned(userId, characterId).isEmpty()) {
            return response(currentState, NullNode.instance);
        }

        reportCallbackService.createDemoRecommendedQuizzes(userId, characterId, todayDate());
        ObjectNode state = loadWithCharacterProjection(userId);
        return response(state, state.path("dailyReport"));
    }

    private GameMutationResponse requestQuizGrade(
            long userId,
            String id,
            ObjectNode state,
            ObjectNode quiz,
            String userAnswer
    ) {
        ObjectNode character = findObject(array(state, "characters"), quiz.path("characterId").asText());
        Long characterId = parseCharacterId(quiz.path("characterId").asText());
        if (character == null || characterId == null) {
            quiz.put("submitted", false);
            quiz.put("gradeFailed", true);
            quiz.put("grading", false);
            quiz.put("deltaAmount", 0);
            quiz.put("feedback", "채점 대상 캐릭터를 찾을 수 없어 채점 요청을 보내지 못했어요.");
            save(userId, state);
            return response(state, quiz);
        }

        String submissionId = submissionIdFor(quiz);
        long quizId = positiveQuizId(id, quiz);
        quiz.put("submissionId", submissionId);
        quiz.put("quizId", quizId);
        quiz.put("grading", true);
        quiz.set("correct", NullNode.instance);
        quiz.set("feedback", NullNode.instance);
        quiz.put("deltaAmount", 0);

        QuizGradeRequestMessage message = new QuizGradeRequestMessage(
                submissionId,
                userId,
                characterId,
                quizId,
                problemIdFor(quiz),
                quiz.path("question").asText(""),
                modelAnswerFor(quiz),
                userAnswer,
                scoreAllocationFor(quiz),
                characterMetadata(character),
                quizGradingProperties.normalizedCallbackUrl()
        );
        QuizGradeRequestResult result = quizGradeRequestClient.requestGrade(message);
        if (!result.accepted()) {
            quiz.put("submitted", false);
            quiz.put("gradeFailed", true);
            quiz.put("grading", false);
            quiz.put("feedback", "채점 요청을 보내지 못했어요. 잠시 후 다시 시도해 주세요.");
            save(userId, state);
            ObjectNode item = objectMapper.createObjectNode()
                    .put("ok", false)
                    .put("failureReason", result.failureReason());
            item.set("quiz", quiz);
            return response(state, item);
        }

        save(userId, state);
        return response(state, quiz);
    }

    private GameMutationResponse submitQuizLocally(long userId, ObjectNode state, ObjectNode quiz, String userAnswer) {
        QuizEvaluation evaluation = evaluateQuizAnswer(quiz, userAnswer);
        boolean correct = evaluation.correct();
        quiz.put("correct", correct);
        quiz.put("deltaAmount", evaluation.deltaAmount());
        quiz.put("feedback", evaluation.feedback());
        ObjectNode character = findObject(array(state, "characters"), quiz.path("characterId").asText());
        Long characterId = parseCharacterId(quiz.path("characterId").asText());
        if (character == null || characterId == null) {
            quiz.put("scored", false);
            quiz.put("gradeFailed", true);
            quiz.put("grading", false);
            quiz.put("deltaAmount", 0);
            quiz.put("feedback", "채점 대상 캐릭터를 찾을 수 없어 점수 반영을 건너뛰었어요.");
            save(userId, state);
            return response(state, quiz);
        }

        boolean wasEvolved = character.path("isEvolved").asBoolean(false);
        String stat = quiz.path("deltaStat").asText("algo");
        LearningCharacter updated = characterCommandService.applyScoreDelta(
                userId,
                characterId,
                stat,
                quiz.path("deltaAmount").asInt(0),
                correct ? CharacterEmotion.JOY : CharacterEmotion.SAD,
                correct ? "좋은 답변이야! 핵심을 잡았어." : "괜찮아, 답을 다듬으면서 크는 거지."
        ).orElse(null);
        if (updated == null) {
            quiz.put("scored", false);
            quiz.put("gradeFailed", true);
            quiz.put("grading", false);
            quiz.put("deltaAmount", 0);
            quiz.put("feedback", "채점 대상 캐릭터를 찾을 수 없어 점수 반영을 건너뛰었어요.");
            save(userId, state);
            return response(state, quiz);
        }

        quiz.put("scored", true);
        quiz.put("gradeFailed", false);
        quiz.put("grading", false);
        quiz.put("_evolvedNow", !wasEvolved && updated.isEvolved());
        applyCharacterProjection(userId, state);
        save(userId, state);
        characterEventService.publishCharacterUpdatedAfterCommit(userId, updated);
        return response(state, quiz);
    }

    @Transactional
    public GameMutationResponse deliverDailyReport(long userId, JsonNode request) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode report = pendingReport(state);
        if (report == null) {
            return response(state, state.path("dailyReport"));
        }

        String reportCharacterId = report.path("characterId").isNull()
                ? null
                : report.path("characterId").asText(null);
        Long characterId = parseCharacterId(reportCharacterId);
        LocalDate targetDate = parseDate(report.path("date").asText(""));
        if (characterId == null
                || targetDate == null
                || findObject(array(state, "characters"), reportCharacterId) == null) {
            ObjectNode failed = dailyReport(report.path("date").asText(today()), null, "failed");
            String requestId = report.path("requestId").asText("").strip();
            if (!requestId.isBlank()) {
                failed.put("requestId", requestId);
            }
            report.put("scoreApplied", false);
            state.set("dailyReport", failed);
            state.put("notice", "리포트 분석 요청 대상을 찾지 못해 worker 요청을 보내지 못했어요.");
            save(userId, state);
            return response(state, failed);
        }

        String requestId = reportRequestOutboxService.requestIdFor(userId, characterId, targetDate);
        report.put("requestId", requestId);
        reportRequestOutboxService.createOrRefreshSnapshot(userId, characterId, targetDate, report, state);

        ObjectNode daily = dailyReport(report.path("date").asText(today()), reportCharacterId, "pending");
        daily.put("requestId", requestId);
        state.set("dailyReport", daily);

        if (!reportQueueProperties.isEnabled()) {
            state.put("notice", "리포트가 저장됐어요. worker 큐가 켜지면 분석 요청이 전송됩니다.");
            save(userId, state);
            return response(state, daily);
        }

        ReportOutboxDispatcher.DispatchResult dispatchResult =
                reportOutboxDispatcher.dispatchRequest(requestId, Instant.now());
        if (dispatchResult.sentCount() > 0) {
            state.put("notice", "리포트 분석 요청을 worker에 전달했어요. 결과가 도착하면 갱신됩니다.");
        } else if (dispatchResult.retryCount() > 0) {
            state.put("notice", "리포트 분석 요청 전송이 지연됐어요. 잠시 후 다시 시도해 주세요.");
        } else if (dispatchResult.failedCount() > 0) {
            state.put("notice", "리포트 분석 요청 전송에 실패했어요. 설정을 확인한 뒤 다시 시도해 주세요.");
        } else {
            state.put("notice", "이미 리포트 분석 요청이 전송됐어요. 결과가 도착하면 갱신됩니다.");
        }
        save(userId, state);
        return response(state, daily);
    }

    @Transactional
    public GameMutationResponse runReportNow(long userId, JsonNode request) {
        return deliverDailyReport(userId, request);
    }

    @Transactional
    public GameMutationResponse createBoardPost(long userId, JsonNode request) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode character = activeCharacter(state);
        String desc = requiredText(request, "desc");
        if (character == null) {
            return response(state, NullNode.instance);
        }
        ObjectNode post = objectMapper.createObjectNode()
                .put("id", nextId(state, "b"))
                .put("characterId", idOf(character))
                .put("name", character.path("name").asText())
                .put("owner", CURRENT_OWNER)
                .put("emotion", character.path("emotion").asText("joy"))
                .put("isEvolved", character.path("isEvolved").asBoolean(false))
                .put("score", nurtureScore(character))
                .put("desc", desc)
                .put("rating", 0);
        post.set("reviews", objectMapper.createArrayNode());
        array(state, "boardPosts").insert(0, post);
        save(userId, state);
        return response(state, post);
    }

    @Transactional
    public GameMutationResponse updateBoardPost(long userId, String id, JsonNode request) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode post = findObject(array(state, "boardPosts"), id);
        if (post != null && CURRENT_OWNER.equals(post.path("owner").asText())) {
            post.put("desc", requiredText(request, "desc"));
            save(userId, state);
        }
        return response(state, post == null ? NullNode.instance : post);
    }

    @Transactional
    public GameMutationResponse deleteBoardPost(long userId, String id) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode removed = removeOwned(array(state, "boardPosts"), id, "owner");
        save(userId, state);
        return response(state, removed == null ? NullNode.instance : removed);
    }

    @Transactional
    public GameMutationResponse addReview(long userId, String postId, JsonNode request) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode post = findObject(array(state, "boardPosts"), postId);
        if (post == null) {
            return response(state, NullNode.instance);
        }
        ObjectNode review = objectMapper.createObjectNode()
                .put("id", nextId(state, "rv"))
                .put("author", CURRENT_OWNER)
                .put("stars", Math.max(1, Math.min(5, request.path("stars").asInt(5))))
                .put("text", requiredText(request, "text"))
                .put("createdAt", Instant.now().toString());
        array(post, "reviews").insert(0, review);
        recalculateRating(post);
        save(userId, state);
        return response(state, review);
    }

    @Transactional
    public GameMutationResponse updateReview(long userId, String postId, String reviewId, JsonNode request) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode post = findObject(array(state, "boardPosts"), postId);
        ObjectNode review = post == null ? null : findObject(array(post, "reviews"), reviewId);
        if (review != null && CURRENT_OWNER.equals(review.path("author").asText())) {
            review.put("stars", Math.max(1, Math.min(5, request.path("stars").asInt(5))));
            review.put("text", requiredText(request, "text"));
            recalculateRating(post);
            save(userId, state);
        }
        return response(state, review == null ? NullNode.instance : review);
    }

    @Transactional
    public GameMutationResponse deleteReview(long userId, String postId, String reviewId) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode post = findObject(array(state, "boardPosts"), postId);
        ObjectNode removed = post == null ? null : removeOwned(array(post, "reviews"), reviewId, "author");
        if (removed != null) {
            recalculateRating(post);
            save(userId, state);
        }
        return response(state, removed == null ? NullNode.instance : removed);
    }

    private ObjectNode loadOrCreate(long userId) {
        return repository.findById(userId)
                .map(entity -> parse(entity.getStateJson()))
                .orElseGet(() -> {
                    ObjectNode state = defaultState();
                    repository.save(GameState.create(userId, stringify(state)));
                    return state;
                });
    }

    private ObjectNode loadWithCharacterProjection(long userId) {
        ObjectNode state = loadOrCreate(userId);
        applyCharacterProjection(userId, state);
        return state;
    }

    private ObjectNode loadWithCharacterProjectionForUpdate(long userId) {
        ObjectNode state = loadOrCreateForUpdate(userId);
        applyCharacterProjection(userId, state);
        return state;
    }

    private void applyCharacterProjection(long userId, ObjectNode state) {
        state.set("characters", characterProjectionService.projectCharacters(userId));
        dailyReportProjectionService.overlay(userId, state);
    }

    private ObjectNode loadOrCreateForUpdate(long userId) {
        return repository.findByIdForUpdate(userId)
                .map(entity -> parse(entity.getStateJson()))
                .orElseGet(() -> {
                    ObjectNode state = defaultState();
                    repository.saveAndFlush(GameState.create(userId, stringifyForPersistence(state)));
                    return state;
                });
    }

    private void save(long userId, ObjectNode state) {
        GameState entity = repository.findById(userId)
                .orElseGet(() -> GameState.create(userId, stringifyForPersistence(state)));
        entity.updateStateJson(stringifyForPersistence(state));
        repository.save(entity);
    }

    private String stringifyForPersistence(ObjectNode state) {
        ObjectNode persisted = state.deepCopy();
        persisted.set("characters", objectMapper.createArrayNode());
        return stringify(persisted);
    }

    private ObjectNode defaultState() {
        ObjectNode state = objectMapper.createObjectNode();
        state.put("nextId", 100);
        state.set("characters", objectMapper.createArrayNode());
        state.set("reports", objectMapper.createArrayNode());
        state.set("quizzes", objectMapper.createArrayNode());
        state.set("dailyReport", dailyReport(today(), null, "pending"));
        state.set("notice", NullNode.instance);
        ArrayNode boardPosts = objectMapper.createArrayNode();
        boardPosts.add(boardPost("b1", "코드몬", "jimin", "joy", true, 1840,
                "6개월 키운 알고리즘 특화 분신. 그리디/DP 위주로 먹였어요.", 4.6,
                review("r1", "sora", 5, "진화 연출 너무 귀엽다 ㅠㅠ"),
                review("r2", "minho", 4, "스탯 밸런스가 좋네요.")));
        boardPosts.add(boardPost("b2", "쿼리냥", "sora", "sad", false, 760,
                "DB만 파고든 외길 분신. 인덱스 튜닝 리포트가 많아요.", 4.0,
                review("r3", "hana", 4, "DB 리포트 참고됐어요!")));
        boardPosts.add(boardPost("b3", "패킷이", "minho", "angry", false, 540,
                "네트워크 위주. TCP/IP 정주행 기록.", 0));
        state.set("boardPosts", boardPosts);
        return state;
    }

    private ObjectNode boardPost(String id, String name, String owner, String emotion, boolean evolved,
                                 int score, String desc, double rating, ObjectNode... reviews) {
        ObjectNode post = objectMapper.createObjectNode()
                .put("id", id)
                .put("name", name)
                .put("owner", owner)
                .put("emotion", emotion)
                .put("isEvolved", evolved)
                .put("score", score)
                .put("desc", desc)
                .put("rating", rating);
        ArrayNode reviewList = objectMapper.createArrayNode();
        for (ObjectNode review : reviews) {
            reviewList.add(review);
        }
        post.set("reviews", reviewList);
        return post;
    }

    private ObjectNode review(String id, String author, int stars, String text) {
        return objectMapper.createObjectNode()
                .put("id", id)
                .put("author", author)
                .put("stars", stars)
                .put("text", text)
                .put("createdAt", Instant.now().toString());
    }

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored game state is not valid JSON.", e);
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize game state.", e);
        }
    }

    private GameMutationResponse response(JsonNode state, JsonNode item) {
        return new GameMutationResponse(state, item == null ? NullNode.instance : item);
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

    private ArrayNode copyArray(JsonNode node) {
        ArrayNode copy = objectMapper.createArrayNode();
        if (node != null && node.isArray()) {
            node.forEach(copy::add);
        }
        return copy;
    }

    private String nextId(ObjectNode state, String prefix) {
        int next = state.path("nextId").asInt(100) + 1;
        state.put("nextId", next);
        return prefix + next;
    }

    private String today() {
        return todayDate().toString();
    }

    private LocalDate todayDate() {
        return LocalDate.now(APP_ZONE);
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

    private String requiredText(JsonNode request, String field) {
        String value = text(request, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value;
    }

    private String text(JsonNode request, String field) {
        return request == null ? "" : request.path(field).asText("").trim();
    }

    private String defaultText(JsonNode request, String field, String fallback) {
        String value = text(request, field);
        return value.isBlank() ? fallback : value;
    }

    private boolean bool(JsonNode request, String field) {
        return request != null && request.path(field).asBoolean(false);
    }

    private ObjectNode findObject(ArrayNode array, String id) {
        for (JsonNode node : array) {
            if (sameId(node, id) && node instanceof ObjectNode object) {
                return object;
            }
        }
        return null;
    }

    private boolean sameId(JsonNode node, String id) {
        return id != null && id.equals(node.path("id").asText());
    }

    private String idOf(JsonNode node) {
        return node.path("id").asText();
    }

    private Long parseCharacterId(String id) {
        try {
            return id == null ? null : Long.valueOf(id);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private long parseCharacterIdOrThrow(String id) {
        Long characterId = parseCharacterId(id);
        if (characterId == null) {
            throw new CharacterNotFoundException();
        }
        return characterId;
    }

    private ObjectNode activeCharacter(ObjectNode state) {
        for (JsonNode node : array(state, "characters")) {
            if (node.path("active").asBoolean(false) && node instanceof ObjectNode object) {
                return object;
            }
        }
        return null;
    }

    private void clearActiveCharacter(ObjectNode state) {
        array(state, "characters").forEach(node -> ((ObjectNode) node).put("active", false));
        JsonNode existing = state.path("dailyReport");
        ObjectNode daily;
        if (existing instanceof ObjectNode object) {
            daily = object;
        } else {
            daily = dailyReport(today(), null, "pending");
            state.set("dailyReport", daily);
        }
        daily.set("characterId", NullNode.instance);
    }

    private void syncDailyReportCharacter(ObjectNode state, String characterId) {
        JsonNode existing = state.path("dailyReport");
        if (!(existing instanceof ObjectNode)) {
            ObjectNode daily = dailyReport(today(), characterId, "pending");
            state.set("dailyReport", daily);
            return;
        }
        ObjectNode daily = (ObjectNode) existing;
        if (daily.path("characterId").isMissingNode() || daily.path("characterId").isNull()) {
            daily.put("characterId", characterId);
        }
    }

    private void syncDailyReportCharacter(ObjectNode state, Long characterId) {
        JsonNode existing = state.path("dailyReport");
        if (!(existing instanceof ObjectNode)) {
            ObjectNode daily = dailyReport(today(), null, "pending");
            daily.put("characterId", characterId);
            state.set("dailyReport", daily);
            return;
        }
        ObjectNode daily = (ObjectNode) existing;
        if (daily.path("characterId").isMissingNode() || daily.path("characterId").isNull()) {
            daily.put("characterId", characterId);
        }
    }

    private void replaceDailyReportCharacter(ObjectNode state, Long characterId) {
        JsonNode existing = state.path("dailyReport");
        ObjectNode daily;
        if (existing instanceof ObjectNode object) {
            daily = object;
        } else {
            daily = dailyReport(today(), null, "pending");
            state.set("dailyReport", daily);
        }
        daily.put("characterId", characterId);
    }

    private void syncDailyReportAfterDeletedReference(ObjectNode state, Long removedCharacterId) {
        Long currentCharacterId = parseCharacterId(state.path("dailyReport").path("characterId"));
        if (currentCharacterId == null || !currentCharacterId.equals(removedCharacterId)) {
            return;
        }
        ObjectNode active = activeCharacter(state);
        Long activeId = active == null ? null : parseCharacterId(idOf(active));
        if (activeId == null) {
            clearActiveCharacter(state);
            return;
        }
        replaceDailyReportCharacter(state, activeId);
    }

    private void syncPendingReportsAfterDeletedReference(ObjectNode state, Long removedCharacterId) {
        for (JsonNode node : array(state, "reports")) {
            if (node instanceof ObjectNode report
                    && "analyzing".equals(report.path("status").asText())
                    && removedCharacterId.equals(parseCharacterId(report.path("characterId")))) {
                String replacementId = activeCharacterId(state);
                if (replacementId == null) {
                    report.set("characterId", NullNode.instance);
                } else {
                    report.put("characterId", replacementId);
                }
            }
        }
    }

    private void syncUnscoredQuizzesAfterDeletedReference(ObjectNode state, Long removedCharacterId) {
        String replacementId = activeCharacterId(state);
        for (JsonNode node : array(state, "quizzes")) {
            if (node instanceof ObjectNode quiz
                    && !quiz.path("scored").asBoolean(false)
                    && removedCharacterId.equals(parseCharacterId(quiz.path("characterId")))) {
                if (replacementId == null) {
                    quiz.set("characterId", NullNode.instance);
                } else {
                    quiz.put("characterId", replacementId);
                }
            }
        }
    }

    private String activeCharacterId(ObjectNode state) {
        ObjectNode active = activeCharacter(state);
        if (active == null) {
            return null;
        }
        String activeId = idOf(active);
        return activeId.isBlank() ? null : activeId;
    }

    private Long parseCharacterId(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        return parseCharacterId(node.asText());
    }

    private ObjectNode dailyReport(String date, String characterId, String status) {
        ObjectNode daily = objectMapper.createObjectNode();
        daily.put("status", status);
        daily.put("date", date);
        if (characterId == null) {
            daily.set("characterId", NullNode.instance);
        } else {
            daily.put("characterId", characterId);
        }
        daily.set("summary", NullNode.instance);
        daily.set("deltas", NullNode.instance);
        daily.set("quizComment", NullNode.instance);
        daily.set("nextRecommendation", NullNode.instance);
        return daily;
    }

    private ObjectNode findReportForDate(ObjectNode state, String date, String characterId) {
        for (JsonNode node : array(state, "reports")) {
            if (node instanceof ObjectNode report
                    && date.equals(report.path("date").asText())
                    && characterId.equals(report.path("characterId").asText())) {
                return report;
            }
        }
        return null;
    }

    private ObjectNode pendingReport(ObjectNode state) {
        for (JsonNode node : array(state, "reports")) {
            if (node instanceof ObjectNode report && "analyzing".equals(report.path("status").asText())) {
                return report;
            }
        }
        return null;
    }

    private String submissionIdFor(ObjectNode quiz) {
        String existing = quiz.path("submissionId").asText("").trim();
        return existing.isBlank() ? "quiz-" + UUID.randomUUID() : existing;
    }

    private long positiveQuizId(String id, ObjectNode quiz) {
        long explicit = quiz.path("quizId").asLong(0L);
        if (explicit > 0) {
            return explicit;
        }
        String digits = id == null ? "" : id.replaceAll("\\D+", "");
        if (!digits.isBlank()) {
            try {
                long parsed = Long.parseLong(digits);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to a stable hash for non-numeric quiz ids.
            }
        }
        long hash = Integer.toUnsignedLong(String.valueOf(id).hashCode());
        return hash == 0 ? 1 : hash;
    }

    private String problemIdFor(ObjectNode quiz) {
        JsonNode problemId = quiz.path("problemId");
        if (problemId.isMissingNode() || problemId.isNull()) {
            return null;
        }
        String value = problemId.asText("").trim();
        return value.isBlank() ? null : value;
    }

    private FastApiScoreDelta scoreAllocationFor(ObjectNode quiz) {
        JsonNode allocation = quiz.path("scoreAllocation");
        if (allocation.isObject()) {
            return new FastApiScoreDelta(
                    boundedScore(allocation.path("db").asInt(0)),
                    boundedScore(allocation.path("algorithm").asInt(0)),
                    boundedScore(allocation.path("cs").asInt(0)),
                    boundedScore(allocation.path("network").asInt(0)),
                    boundedScore(allocation.path("framework").asInt(0))
            );
        }

        int amount = boundedScore(quiz.path("maxScore").asInt(10));
        String stat = quiz.path("deltaStat").asText("algo");
        return switch (stat) {
            case "db" -> new FastApiScoreDelta(amount, 0, 0, 0, 0);
            case "cs" -> new FastApiScoreDelta(0, 0, amount, 0, 0);
            case "net" -> new FastApiScoreDelta(0, 0, 0, amount, 0);
            case "fw" -> new FastApiScoreDelta(0, 0, 0, 0, amount);
            default -> new FastApiScoreDelta(0, amount, 0, 0, 0);
        };
    }

    private int boundedScore(int value) {
        return Math.max(0, Math.min(10, value));
    }

    private ObjectNode characterMetadata(ObjectNode character) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("personality", character.path("personality").asText(""));
        JsonNode stats = character.path("stats");
        ObjectNode currentStats = objectMapper.createObjectNode();
        currentStats.put("db", stats.path("db").asInt(0));
        currentStats.put("algorithm", stats.path("algo").asInt(0));
        currentStats.put("cs", stats.path("cs").asInt(0));
        currentStats.put("network", stats.path("net").asInt(0));
        currentStats.put("framework", stats.path("fw").asInt(0));
        metadata.set("currentStats", currentStats);
        return metadata;
    }

    private QuizEvaluation evaluateQuizAnswer(ObjectNode quiz, String userAnswer) {
        JsonNode keywords = quiz.path("answerKeywords");
        int keywordCount = keywords.isArray() ? keywords.size() : 0;
        int matched = 0;
        if (keywordCount > 0) {
            for (JsonNode keyword : keywords) {
                if (containsKeyword(userAnswer, keyword.asText())) {
                    matched++;
                }
            }
            int requiredMatches = Math.max(1, (int) Math.ceil(keywordCount * 0.5));
            boolean correct = matched >= requiredMatches;
            return quizEvaluation(quiz, correct);
        }

        String modelAnswer = modelAnswerFor(quiz);
        boolean correct = containsKeyword(userAnswer, modelAnswer);
        return quizEvaluation(quiz, correct);
    }

    private QuizEvaluation quizEvaluation(ObjectNode quiz, boolean correct) {
        String modelAnswer = modelAnswerFor(quiz);
        String feedback = correct
                ? "좋은 답변이에요. 핵심 근거가 충분히 들어 있어요. 모범답안: " + modelAnswer
                : "핵심이 조금 부족해요. 문제의 원인이나 역할을 더 구체적으로 적어 보세요. 모범답안: " + modelAnswer;
        return new QuizEvaluation(correct, correct ? 12 : 3, feedback);
    }

    private String modelAnswerFor(ObjectNode quiz) {
        String modelAnswer = quiz.path("modelAnswer").asText("").trim();
        if (!modelAnswer.isBlank()) {
            return modelAnswer;
        }
        JsonNode options = quiz.path("options");
        int answer = quiz.path("answer").asInt(-1);
        if (options.isArray() && answer >= 0 && answer < options.size()) {
            return options.get(answer).asText("").trim();
        }
        return "";
    }

    private boolean containsKeyword(String answer, String keyword) {
        String normalizedAnswer = normalizeAnswerText(answer);
        String normalizedKeyword = normalizeAnswerText(keyword);
        return !normalizedKeyword.isBlank() && normalizedAnswer.contains(normalizedKeyword);
    }

    private String normalizeAnswerText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9가-힣+#]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private record QuizEvaluation(boolean correct, int deltaAmount, String feedback) {
    }

    private String reactionFor(String mood) {
        if ("joy".equals(mood)) {
            return "오늘도 한 발 나아갔다! 뿌듯해.";
        }
        if ("sad".equals(mood)) {
            return "힘든 날도 있지. 기록한 것만으로 충분해.";
        }
        return "answer가 막혔구나. 내일 다시 도전하자!";
    }

    private CharacterEmotion emotionFor(String mood) {
        if ("sad".equals(mood)) {
            return CharacterEmotion.SAD;
        }
        if ("angry".equals(mood)) {
            return CharacterEmotion.ANGRY;
        }
        return CharacterEmotion.JOY;
    }

    private String displayStat(String stat) {
        return switch (stat) {
            case "db" -> "DB";
            case "cs" -> "CS";
            case "net" -> "네트워크";
            case "fw" -> "프레임워크";
            default -> "알고리즘";
        };
    }

    private void maybeEvolve(ObjectNode state, ObjectNode character) {
        if (!character.path("isEvolved").asBoolean(false) && nurtureScore(character) >= EVOLVE_THRESHOLD) {
            character.put("isEvolved", true);
            state.put("notice", "🎉 " + character.path("name").asText() + " 진화! 육아점수 " + EVOLVE_THRESHOLD + " 돌파.");
        }
    }

    private int nurtureScore(JsonNode character) {
        JsonNode stats = character.path("stats");
        int sum = 0;
        for (String key : STAT_KEYS) {
            sum += stats.path(key).asInt(0);
        }
        return sum;
    }

    private void recalculateRating(ObjectNode post) {
        ArrayNode reviews = array(post, "reviews");
        if (reviews.isEmpty()) {
            post.put("rating", 0);
            return;
        }
        int sum = 0;
        for (JsonNode review : reviews) {
            sum += review.path("stars").asInt(0);
        }
        post.put("rating", Math.round((sum * 10.0) / reviews.size()) / 10.0);
    }

    private ObjectNode removeOwned(ArrayNode array, String id, String ownerField) {
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            if (sameId(node, id) && CURRENT_OWNER.equals(node.path(ownerField).asText())) {
                return (ObjectNode) array.remove(i);
            }
        }
        return null;
    }
}
