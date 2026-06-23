package com.commitgotchi.character.application;

import com.commitgotchi.character.domain.CharacterEmotion;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CharacterCommandService {

    private final UserRepository userRepository;
    private final LearningCharacterRepository characterRepository;

    public CharacterCommandService(
            UserRepository userRepository,
            LearningCharacterRepository characterRepository
    ) {
        this.userRepository = userRepository;
        this.characterRepository = characterRepository;
    }

    @Transactional
    public Optional<LearningCharacter> update(
            long userId,
            long characterId,
            String name,
            String keyword,
            String personality
    ) {
        return characterRepository.findByIdAndUserIdForUpdate(characterId, userId)
                .map(character -> {
                    character.rename(name);
                    character.changeDesignKeyword(keyword);
                    character.changePersonality(personality);
                    return characterRepository.saveAndFlush(character);
                });
    }

    @Transactional(readOnly = true)
    public Optional<LearningCharacter> findOwned(long userId, long characterId) {
        return characterRepository.findByIdAndUserId(characterId, userId);
    }

    @Transactional
    public Optional<LearningCharacter> activate(long userId, long characterId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user does not exist."));
        Optional<LearningCharacter> target = characterRepository.findByIdAndUserIdForUpdate(characterId, userId);
        if (target.isEmpty()) {
            return Optional.empty();
        }

        LearningCharacter character = target.get();
        List<LearningCharacter> existingCharacters = characterRepository.findAllByUserIdForUpdateOrderByCreatedAtDesc(userId);
        boolean deactivated = false;
        for (LearningCharacter existing : existingCharacters) {
            if (existing.isActive() && !existing.getId().equals(character.getId())) {
                existing.deactivate();
                characterRepository.save(existing);
                deactivated = true;
            }
        }
        if (deactivated) {
            characterRepository.flush();
        }

        if (character.isActive()) {
            return Optional.of(character);
        }

        character.activate();
        return Optional.of(characterRepository.saveAndFlush(character));
    }

    @Transactional
    public Optional<CharacterDeletionResult> delete(long userId, long characterId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user does not exist."));
        Optional<LearningCharacter> target = characterRepository.findByIdAndUserIdForUpdate(characterId, userId);
        if (target.isEmpty()) {
            return Optional.empty();
        }

        LearningCharacter character = target.get();
        boolean wasActive = character.isActive();
        characterRepository.delete(character);
        characterRepository.flush();

        LearningCharacter newActive = null;
        if (wasActive) {
            List<LearningCharacter> remainingCharacters = characterRepository.findAllByUserIdForUpdateOrderByCreatedAtDesc(userId);
            if (!remainingCharacters.isEmpty()) {
                newActive = remainingCharacters.get(0);
                newActive.activate();
                newActive = characterRepository.saveAndFlush(newActive);
            }
        }

        return Optional.of(new CharacterDeletionResult(character, newActive));
    }

    @Transactional
    public Optional<LearningCharacter> reactActive(long userId, CharacterEmotion emotion, String statusMessage) {
        return characterRepository.findActiveByUserIdForUpdate(userId)
                .map(character -> {
                    character.react(emotion, statusMessage);
                    return characterRepository.saveAndFlush(character);
                });
    }

    @Transactional
    public Optional<LearningCharacter> applyScoreDelta(
            long userId,
            long characterId,
            String stat,
            int amount,
            CharacterEmotion emotion,
            String statusMessage
    ) {
        return characterRepository.findByIdAndUserIdForUpdate(characterId, userId)
                .map(character -> {
                    applyStatDelta(character, stat, amount);
                    character.react(emotion, statusMessage);
                    return characterRepository.saveAndFlush(character);
                });
    }

    @Transactional
    public Optional<LearningCharacter> applyScoreDeltas(
            long userId,
            long characterId,
            int dbDelta,
            int algorithmDelta,
            int csDelta,
            int networkDelta,
            int frameworkDelta,
            CharacterEmotion emotion,
            String statusMessage
    ) {
        return characterRepository.findByIdAndUserIdForUpdate(characterId, userId)
                .map(character -> {
                    character.applyScoreDelta(dbDelta, algorithmDelta, csDelta, networkDelta, frameworkDelta);
                    character.react(emotion, statusMessage);
                    return characterRepository.saveAndFlush(character);
                });
    }

    @Transactional
    public Optional<LearningCharacter> applyScoreDeltasToActive(
            long userId,
            int dbDelta,
            int algorithmDelta,
            int csDelta,
            int networkDelta,
            int frameworkDelta,
            CharacterEmotion emotion,
            String statusMessage
    ) {
        return characterRepository.findActiveByUserIdForUpdate(userId)
                .map(character -> {
                    character.applyScoreDelta(dbDelta, algorithmDelta, csDelta, networkDelta, frameworkDelta);
                    character.react(emotion, statusMessage);
                    return characterRepository.saveAndFlush(character);
                });
    }

    private void applyStatDelta(LearningCharacter character, String stat, int amount) {
        switch (stat) {
            case "db" -> character.applyScoreDelta(amount, 0, 0, 0, 0);
            case "algo" -> character.applyScoreDelta(0, amount, 0, 0, 0);
            case "cs" -> character.applyScoreDelta(0, 0, amount, 0, 0);
            case "net" -> character.applyScoreDelta(0, 0, 0, amount, 0);
            case "fw" -> character.applyScoreDelta(0, 0, 0, 0, amount);
            default -> throw new IllegalArgumentException("Unknown stat: " + stat);
        }
    }
}
