package com.commitgotchi.report.application;

import com.commitgotchi.character.application.CharacterGameProjectionService;
import com.commitgotchi.game.domain.GameState;
import com.commitgotchi.game.domain.GameStateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ReportEventService {

    private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final String SNAPSHOT_EVENT = "report.snapshot";
    private static final String READY_EVENT = "report.ready";
    private static final String FAILED_EVENT = "report.failed";

    private final GameStateRepository gameStateRepository;
    private final CharacterGameProjectionService characterProjectionService;
    private final DailyReportProjectionService dailyReportProjectionService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, CopyOnWriteArraySet<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicLong eventIds = new AtomicLong();

    public ReportEventService(
            GameStateRepository gameStateRepository,
            CharacterGameProjectionService characterProjectionService,
            DailyReportProjectionService dailyReportProjectionService,
            ObjectMapper objectMapper
    ) {
        this.gameStateRepository = gameStateRepository;
        this.characterProjectionService = characterProjectionService;
        this.dailyReportProjectionService = dailyReportProjectionService;
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(long userId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitters.computeIfAbsent(userId, ignored -> new CopyOnWriteArraySet<>()).add(emitter);

        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        if (!send(emitter, SNAPSHOT_EVENT, snapshot(userId))) {
            cleanup.run();
        }
        return emitter;
    }

    public void publishReportReadyAfterCommit(long userId) {
        publishAfterCommit(() -> publish(userId, READY_EVENT, snapshot(userId)));
    }

    public void publishReportFailedAfterCommit(long userId) {
        publishAfterCommit(() -> publish(userId, FAILED_EVENT, snapshot(userId)));
    }

    private void publishAfterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
            return;
        }
        publish.run();
    }

    private ObjectNode snapshot(long userId) {
        ObjectNode state = gameStateRepository.findById(userId)
                .map(GameState::getStateJson)
                .map(this::parse)
                .orElseGet(this::emptyState);
        state.set("characters", characterProjectionService.projectCharacters(userId));
        dailyReportProjectionService.overlay(userId, state);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("dailyReport", state.path("dailyReport").deepCopy());
        payload.set("reports", state.path("reports").deepCopy());
        payload.set("quizzes", state.path("quizzes").deepCopy());
        payload.set("characters", state.path("characters").deepCopy());
        return payload;
    }

    private ObjectNode parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node instanceof ObjectNode object) {
                return object;
            }
            return emptyState();
        } catch (JsonProcessingException exception) {
            return emptyState();
        }
    }

    private ObjectNode emptyState() {
        ObjectNode state = objectMapper.createObjectNode();
        state.set("reports", objectMapper.createArrayNode());
        state.set("quizzes", objectMapper.createArrayNode());
        state.set("characters", objectMapper.createArrayNode());
        ObjectNode daily = objectMapper.createObjectNode();
        daily.put("status", "pending");
        daily.set("date", NullNode.instance);
        daily.set("characterId", NullNode.instance);
        state.set("dailyReport", daily);
        return state;
    }

    int subscriberCount(long userId) {
        CopyOnWriteArraySet<SseEmitter> subscribers = emitters.get(userId);
        return subscribers == null ? 0 : subscribers.size();
    }

    private void publish(long userId, String eventName, JsonNode payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        CopyOnWriteArraySet<SseEmitter> subscribers = emitters.get(userId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : subscribers) {
            if (!send(emitter, eventName, payload)) {
                remove(userId, emitter);
            }
        }
    }

    private boolean send(SseEmitter emitter, String eventName, JsonNode payload) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(eventIds.incrementAndGet()))
                    .name(eventName)
                    .data(payload));
            return true;
        } catch (IOException | IllegalStateException exception) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // Already completed.
            }
            return false;
        }
    }

    private void remove(long userId, SseEmitter emitter) {
        CopyOnWriteArraySet<SseEmitter> subscribers = emitters.get(userId);
        if (subscribers == null) {
            return;
        }
        subscribers.remove(emitter);
        if (subscribers.isEmpty()) {
            emitters.remove(userId, subscribers);
        }
    }
}
