package com.commitgotchi.codex.domain;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class CodexReviewRepository {

    private final CodexReviewMapper mapper;

    public CodexReviewRepository(CodexReviewMapper mapper) {
        this.mapper = mapper;
    }

    public List<CodexReview> findPageByCharacterIdExcludingUser(
            long characterId,
            long userId,
            int limit,
            int offset
    ) {
        return mapper.findPageByCharacterIdExcludingUser(characterId, userId, limit, offset);
    }

    public Optional<CodexReview> findByCharacterIdAndUserId(long characterId, long userId) {
        return Optional.ofNullable(mapper.findByCharacterIdAndUserId(characterId, userId));
    }

    public long countByCharacterId(long characterId) {
        return mapper.countByCharacterId(characterId);
    }

    public double averageStarsByCharacterId(long characterId) {
        Double average = mapper.averageStarsByCharacterId(characterId);
        if (average == null) {
            return 0.0;
        }
        return Math.round(average * 10.0) / 10.0;
    }

    public void insert(long userId, long characterId, int stars, String text) {
        mapper.insert(userId, characterId, stars, text, Instant.now());
    }

    public boolean update(long userId, long characterId, long reviewId, int stars, String text) {
        return mapper.updateByIdAndCharacterIdAndUserId(
                reviewId,
                characterId,
                userId,
                stars,
                text,
                Instant.now()
        ) > 0;
    }

    public boolean delete(long userId, long characterId, long reviewId) {
        return mapper.deleteByIdAndCharacterIdAndUserId(reviewId, characterId, userId) > 0;
    }
}
