package com.commitgotchi.character.domain;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class LearningCharacterRepository {

    private final LearningCharacterMapper mapper;

    public LearningCharacterRepository(LearningCharacterMapper mapper) {
        this.mapper = mapper;
    }

    public LearningCharacter save(LearningCharacter character) {
        if (character.getId() == null) {
            mapper.insert(character);
            return character;
        }
        mapper.update(character);
        return mapper.findById(character.getId());
    }

    public LearningCharacter saveAndFlush(LearningCharacter character) {
        return save(character);
    }

    public void flush() {
        // MyBatis executes mapper statements immediately within the current transaction.
    }

    public void delete(LearningCharacter character) {
        if (character.getId() != null) {
            mapper.update(character);
        }
    }

    public Optional<LearningCharacter> findById(Long id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    public List<LearningCharacter> findAllByUserIdOrderByCreatedAtDesc(long userId) {
        return mapper.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    public long countByUserId(long userId) {
        return mapper.countByUserId(userId);
    }

    public int softDeleteAllByUserId(long userId, Instant deletedAt) {
        return mapper.softDeleteAllByUserId(userId, deletedAt);
    }

    public Optional<LearningCharacter> findByIdAndUserId(Long id, long userId) {
        return Optional.ofNullable(mapper.findByIdAndUserId(id, userId));
    }

    public Optional<LearningCharacter> findActiveByUserId(long userId) {
        return Optional.ofNullable(mapper.findActiveByUserId(userId));
    }

    public Optional<LearningCharacter> findByIdAndUserIdForUpdate(Long id, long userId) {
        return Optional.ofNullable(mapper.findByIdAndUserIdForUpdate(id, userId));
    }

    public List<LearningCharacter> findAllByUserIdForUpdateOrderByCreatedAtDesc(long userId) {
        return mapper.findAllByUserIdForUpdateOrderByCreatedAtDesc(userId);
    }

    public Optional<LearningCharacter> findActiveByUserIdForUpdate(long userId) {
        return Optional.ofNullable(mapper.findActiveByUserIdForUpdate(userId));
    }
}
