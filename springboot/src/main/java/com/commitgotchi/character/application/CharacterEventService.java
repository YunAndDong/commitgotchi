package com.commitgotchi.character.application;

import com.commitgotchi.character.domain.LearningCharacter;
import com.fasterxml.jackson.databind.JsonNode;
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
public class CharacterEventService {

    private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final String SNAPSHOT_EVENT = "character.snapshot";
    private static final String UPDATED_EVENT = "character.updated";
    private static final String QUIZ_GRADED_EVENT = "quiz.graded";

    private final CharacterCommandService characterCommandService;
    private final CharacterGameProjectionService characterProjectionService;
    private final ConcurrentHashMap<CharacterChannel, CopyOnWriteArraySet<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicLong eventIds = new AtomicLong();

    public CharacterEventService(
            CharacterCommandService characterCommandService,
            CharacterGameProjectionService characterProjectionService
    ) {
        this.characterCommandService = characterCommandService;
        this.characterProjectionService = characterProjectionService;
    }

    public SseEmitter subscribe(long userId, long characterId) {
        LearningCharacter character = characterCommandService.findOwned(userId, characterId)
                .orElseThrow(CharacterNotFoundException::new);
        CharacterChannel channel = new CharacterChannel(userId, characterId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitters.computeIfAbsent(channel, ignored -> new CopyOnWriteArraySet<>()).add(emitter);

        Runnable cleanup = () -> remove(channel, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        if (!send(emitter, SNAPSHOT_EVENT, characterProjectionService.project(character))) {
            cleanup.run();
        }
        return emitter;
    }

    public void publishCharacterUpdatedAfterCommit(long userId, LearningCharacter character) {
        Objects.requireNonNull(character, "character must not be null");
        Runnable publish = () -> publish(userId, character.getId(), UPDATED_EVENT, characterProjectionService.project(character));
        publishAfterCommit(publish);
    }

    public void publishQuizGradedAfterCommit(long userId, long characterId, JsonNode quiz) {
        Objects.requireNonNull(quiz, "quiz must not be null");
        Runnable publish = () -> publish(userId, characterId, QUIZ_GRADED_EVENT, quiz);
        publishAfterCommit(publish);
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

    int subscriberCount(long userId, long characterId) {
        CopyOnWriteArraySet<SseEmitter> subscribers = emitters.get(new CharacterChannel(userId, characterId));
        return subscribers == null ? 0 : subscribers.size();
    }

    private void publish(long userId, long characterId, String eventName, JsonNode payload) {
        CharacterChannel channel = new CharacterChannel(userId, characterId);
        CopyOnWriteArraySet<SseEmitter> subscribers = emitters.get(channel);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : subscribers) {
            if (!send(emitter, eventName, payload)) {
                remove(channel, emitter);
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

    private void remove(CharacterChannel channel, SseEmitter emitter) {
        CopyOnWriteArraySet<SseEmitter> subscribers = emitters.get(channel);
        if (subscribers == null) {
            return;
        }
        subscribers.remove(emitter);
        if (subscribers.isEmpty()) {
            emitters.remove(channel, subscribers);
        }
    }

    private record CharacterChannel(long userId, long characterId) {
    }
}
