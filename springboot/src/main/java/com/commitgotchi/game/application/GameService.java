package com.commitgotchi.game.application;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.api.dto.CharacterUpdateRequest;
import com.commitgotchi.character.application.CharacterCommandService;
import com.commitgotchi.character.application.CharacterCreationService;
import com.commitgotchi.character.application.CharacterDeletionResult;
import com.commitgotchi.character.application.CharacterGameProjectionService;
import com.commitgotchi.character.application.CharacterImageService;
import com.commitgotchi.character.application.CharacterNotFoundException;
import com.commitgotchi.character.domain.CharacterEmotion;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.game.api.dto.GameMutationResponse;
import com.commitgotchi.game.domain.GameState;
import com.commitgotchi.game.domain.GameStateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class GameService {

    private static final int EVOLVE_THRESHOLD = 1000;
    private static final String CURRENT_OWNER = "나";
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<String> STAT_KEYS = List.of("algo", "cs", "db", "net", "fw");

    private final GameStateRepository repository;
    private final ObjectMapper objectMapper;
    private final CharacterCommandService characterCommandService;
    private final CharacterCreationService characterCreationService;
    private final CharacterGameProjectionService characterProjectionService;
    private final CharacterImageService characterImageService;

    public GameService(
            GameStateRepository repository,
            ObjectMapper objectMapper,
            CharacterCommandService characterCommandService,
            CharacterCreationService characterCreationService,
            CharacterGameProjectionService characterProjectionService,
            CharacterImageService characterImageService
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.characterCommandService = characterCommandService;
        this.characterCreationService = characterCreationService;
        this.characterProjectionService = characterProjectionService;
        this.characterImageService = characterImageService;
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
        String characterId = character.getId().toString();
        ensureStarterQuizzes(state, characterId);
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
    public GameMutationResponse deleteCharacter(long userId, String id) {
        long characterId = parseCharacterIdOrThrow(id);
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        CharacterDeletionResult deletion = characterCommandService.delete(userId, characterId)
                .orElseThrow(CharacterNotFoundException::new);
        ObjectNode item = characterProjectionService.project(deletion.removed());
        applyCharacterProjection(userId, state);
        if (deletion.newActive().isPresent()) {
            replaceDailyReportCharacter(state, deletion.newActive().get().getId());
        } else if (deletion.removed().isActive()) {
            clearActiveCharacter(state);
        } else {
            syncDailyReportAfterDeletedReference(state, deletion.removed().getId());
        }
        syncPendingReportsAfterDeletedReference(state, deletion.removed().getId());
        syncStarterQuizzesAfterDeletedReference(state, deletion.removed().getId());
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
        String today = today();
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
                .put("characterId", characterId);
        report.set("tags", copyArray(request.path("tags")));
        ObjectNode dailyReport = dailyReport(today, characterId, "pending");
        state.set("dailyReport", dailyReport);
        applyCharacterProjection(userId, state);
        state.put("notice", "리포트 저장됨 - 자정에 분석돼요. 내일 오전 9시 도착.");
        save(userId, state);
        return response(state, report);
    }

    @Transactional
    public GameMutationResponse submitQuiz(long userId, String id, JsonNode request) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode quiz = findObject(array(state, "quizzes"), id);
        if (quiz == null || quiz.path("scored").asBoolean(false)) {
            return response(state, quiz == null ? NullNode.instance : quiz);
        }
        int selected = request.path("selected").asInt(-1);
        quiz.put("submitted", true);
        quiz.put("selected", selected);
        quiz.put("scored", false);
        quiz.put("gradeFailed", false);
        if (bool(request, "fail")) {
            quiz.put("submitted", false);
            quiz.put("gradeFailed", true);
            save(userId, state);
            return response(state, objectMapper.createObjectNode().put("ok", false).set("quiz", quiz));
        }
        boolean correct = selected == quiz.path("answer").asInt();
        quiz.put("correct", correct);
        quiz.put("deltaAmount", correct ? 12 : 3);
        quiz.put("feedback", correct
                ? "정답! 핵심을 정확히 짚었어요. 한 번 확정한 최단거리를 다시 보지 않는 그리디 전제가 음수 간선과 충돌하죠."
                : "아쉬워요. 다익스트라는 확정한 노드를 다시 갱신하지 않는 그리디라 음수 간선을 놓칠 수 있어요. 음수 간선엔 벨만-포드를 씁니다.");
        ObjectNode character = findObject(array(state, "characters"), quiz.path("characterId").asText());
        Long characterId = parseCharacterId(quiz.path("characterId").asText());
        if (character == null || characterId == null) {
            quiz.put("scored", false);
            quiz.put("gradeFailed", true);
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
                correct ? "오 정답! 똑똑한데?" : "괜찮아, 틀리면서 크는 거지."
        ).orElse(null);
        if (updated == null) {
            quiz.put("scored", false);
            quiz.put("gradeFailed", true);
            quiz.put("deltaAmount", 0);
            quiz.put("feedback", "채점 대상 캐릭터를 찾을 수 없어 점수 반영을 건너뛰었어요.");
            save(userId, state);
            return response(state, quiz);
        }

        quiz.put("scored", true);
        quiz.put("gradeFailed", false);
        quiz.put("_evolvedNow", !wasEvolved && updated.isEvolved());
        applyCharacterProjection(userId, state);
        save(userId, state);
        return response(state, quiz);
    }

    @Transactional
    public GameMutationResponse deliverDailyReport(long userId, JsonNode request) {
        ObjectNode state = loadWithCharacterProjectionForUpdate(userId);
        ObjectNode report = pendingReport(state);
        if (report == null) {
            return response(state, state.path("dailyReport"));
        }
        String reportCharacterId = syncReportCharacterToExistingOrActive(state, report);
        if (bool(request, "fail")) {
            state.set("dailyReport", dailyReport(report.path("date").asText(), reportCharacterId, "failed"));
            state.put("notice", "AI가 잠깐 쉬는 중 - 학습은 저장됐어요. 점수 변화는 다음 분석에 반영돼요.");
            save(userId, state);
            return response(state, state.path("dailyReport"));
        }
        ObjectNode daily = dailyReport(report.path("date").asText(), reportCharacterId, "ready");
        daily.put("summary", "어제 학습 리포트를 분석했어요. 핵심 개념을 정리하고 다음 주제로 이어갈 준비가 잘 되어 있습니다.");
        ObjectNode deltas = objectMapper.createObjectNode().put("algo", 3).put("net", 1);
        daily.set("deltas", deltas);
        daily.put("quizComment", "추천 퀴즈를 풀며 개념을 한 번 더 확인해 보세요.");
        daily.put("nextRecommendation", "다음 학습: 오늘 기록한 태그와 연결되는 대표 문제를 하나 더 풀어보기.");
        state.set("dailyReport", daily);
        ObjectNode character = reportCharacterId == null ? null : findObject(array(state, "characters"), reportCharacterId);
        boolean evolvedNow = false;
        Long characterId = parseCharacterId(reportCharacterId);
        if (character != null && characterId != null && !report.path("scoreApplied").asBoolean(false)) {
            boolean wasEvolved = character.path("isEvolved").asBoolean(false);
            LearningCharacter updated = characterCommandService.applyScoreDeltas(
                    userId,
                    characterId,
                    0,
                    3,
                    0,
                    1,
                    0,
                    CharacterEmotion.JOY,
                    "어제 공부가 몸에 스며들었어! 레이더가 차오르는 게 느껴져."
            ).orElse(null);
            if (updated == null) {
                ObjectNode failed = dailyReport(report.path("date").asText(), null, "failed");
                state.set("dailyReport", failed);
                report.put("scoreApplied", false);
                state.put("notice", "활성 캐릭터를 찾지 못해 점수 반영을 건너뛰었어요. 캐릭터를 선택한 뒤 다시 시도해 주세요.");
                save(userId, state);
                return response(state, failed);
            }
            evolvedNow = !wasEvolved && updated.isEvolved();
        } else if ((character == null || characterId == null) && !report.path("scoreApplied").asBoolean(false)) {
            ObjectNode failed = dailyReport(report.path("date").asText(), null, "failed");
            state.set("dailyReport", failed);
            report.put("scoreApplied", false);
            state.put("notice", "활성 캐릭터를 찾지 못해 점수 반영을 건너뛰었어요. 캐릭터를 선택한 뒤 다시 시도해 주세요.");
            save(userId, state);
            return response(state, failed);
        }
        report.put("status", "reflected");
        report.put("scoreApplied", true);
        applyCharacterProjection(userId, state);
        state.put("notice", evolvedNow && character != null
                ? "🎉 " + character.path("name").asText() + " 진화! 육아점수 " + EVOLVE_THRESHOLD + " 돌파."
                : "어제의 레포트 도착 - 학습이 반영됐어요.");
        save(userId, state);
        return response(state, daily);
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
                .put("text", requiredText(request, "text"));
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
                .put("text", text);
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
        return LocalDate.now(APP_ZONE).toString();
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

    private void syncStarterQuizzesAfterDeletedReference(ObjectNode state, Long removedCharacterId) {
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

    private String syncReportCharacterToExistingOrActive(ObjectNode state, ObjectNode report) {
        String reportCharacterId = report.path("characterId").isNull()
                ? null
                : report.path("characterId").asText(null);
        String replacementId = activeCharacterId(state);
        if (replacementId != null) {
            report.put("characterId", replacementId);
            return replacementId;
        }

        if (reportCharacterId != null && findObject(array(state, "characters"), reportCharacterId) != null) {
            return reportCharacterId;
        }

        report.set("characterId", NullNode.instance);
        return null;
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

    private void ensureStarterQuizzes(ObjectNode state, String characterId) {
        ArrayNode quizzes = array(state, "quizzes");
        String date = today();
        quizzes.add(quiz(nextId(state, "q"), date, "algo", characterId,
                "다익스트라 알고리즘이 음의 가중치 간선을 처리하지 못하는 이유로 가장 적절한 것은?",
                List.of("우선순위 큐를 쓰기 때문에", "한 번 확정한 최단거리를 다시 갱신하지 않는 그리디 전제 때문에", "인접 리스트를 사용하기 때문에", "시간복잡도가 높기 때문에"),
                1, "algo"));
        quizzes.add(quiz(nextId(state, "q"), date, "net", characterId,
                "TCP 3-way handshake에서 클라이언트가 마지막으로 보내는 세그먼트는?",
                List.of("SYN", "SYN-ACK", "ACK", "FIN"),
                2, "net"));
    }

    private ObjectNode quiz(String id, String date, String tag, String characterId, String question,
                            List<String> options, int answer, String deltaStat) {
        ObjectNode quiz = objectMapper.createObjectNode()
                .put("id", id)
                .put("date", date)
                .put("tag", tag)
                .put("question", question)
                .put("answer", answer)
                .put("submitted", false)
                .put("selected", NullNode.instance.asText())
                .put("correct", NullNode.instance.asText())
                .put("scored", false)
                .put("gradeFailed", false)
                .put("feedback", NullNode.instance.asText())
                .put("deltaStat", deltaStat)
                .put("deltaAmount", 0)
                .put("characterId", characterId);
        ArrayNode optionNodes = objectMapper.createArrayNode();
        options.forEach(optionNodes::add);
        quiz.set("options", optionNodes);
        quiz.set("selected", NullNode.instance);
        quiz.set("correct", NullNode.instance);
        quiz.set("feedback", NullNode.instance);
        return quiz;
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
