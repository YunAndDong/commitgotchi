package com.commitgotchi.codex.application;

import com.commitgotchi.character.application.CharacterNotFoundException;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.codex.api.dto.CodexCharacterReviewsResponse;
import com.commitgotchi.codex.api.dto.CodexReviewRequest;
import com.commitgotchi.codex.api.dto.CodexReviewResponse;
import com.commitgotchi.codex.domain.CodexReview;
import com.commitgotchi.codex.domain.CodexReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CodexReviewService {

    private static final int DEFAULT_PAGE_SIZE = 3;
    private static final int MAX_PAGE_SIZE = 20;

    private final LearningCharacterRepository characterRepository;
    private final CodexReviewRepository reviewRepository;

    public CodexReviewService(
            LearningCharacterRepository characterRepository,
            CodexReviewRepository reviewRepository
    ) {
        this.characterRepository = characterRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public CodexCharacterReviewsResponse reviews(long userId, long characterId, Integer page, Integer size) {
        ensureCodexCharacter(characterId);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        int limit = normalizedSize + 1;
        int offset = normalizedPage * normalizedSize;

        boolean raisedByMe = characterRepository.existsUserCharacterByUserIdAndCatalogCharacterId(userId, characterId);
        CodexReview myReview = reviewRepository.findByCharacterIdAndUserId(characterId, userId)
                .orElse(null);
        List<CodexReview> rows = reviewRepository.findPageByCharacterIdExcludingUser(
                characterId,
                userId,
                limit,
                offset
        );
        boolean hasMore = rows.size() > normalizedSize;
        List<CodexReviewResponse> reviews = (hasMore ? rows.subList(0, normalizedSize) : rows).stream()
                .map(review -> toResponse(review, false))
                .toList();

        return new CodexCharacterReviewsResponse(
                characterId,
                reviewRepository.averageStarsByCharacterId(characterId),
                reviewRepository.countByCharacterId(characterId),
                raisedByMe,
                raisedByMe && myReview == null,
                myReview == null ? null : toResponse(myReview, true),
                reviews,
                normalizedPage,
                normalizedSize,
                hasMore
        );
    }

    @Transactional
    public CodexCharacterReviewsResponse createReview(long userId, long characterId, CodexReviewRequest request) {
        ensureCodexCharacter(characterId);
        if (!characterRepository.existsUserCharacterByUserIdAndCatalogCharacterId(userId, characterId)) {
            throw new IllegalArgumentException("Only users who raised this commitgotchi can review it.");
        }
        if (reviewRepository.findByCharacterIdAndUserId(characterId, userId).isPresent()) {
            throw new IllegalArgumentException("A review already exists.");
        }

        reviewRepository.insert(userId, characterId, request.stars(), request.text().strip());
        return reviews(userId, characterId, 0, DEFAULT_PAGE_SIZE);
    }

    @Transactional
    public CodexCharacterReviewsResponse updateReview(
            long userId,
            long characterId,
            long reviewId,
            CodexReviewRequest request
    ) {
        ensureCodexCharacter(characterId);
        boolean updated = reviewRepository.update(
                userId,
                characterId,
                reviewId,
                request.stars(),
                request.text().strip()
        );
        if (!updated) {
            throw new IllegalArgumentException("Review not found.");
        }
        return reviews(userId, characterId, 0, DEFAULT_PAGE_SIZE);
    }

    @Transactional
    public CodexCharacterReviewsResponse deleteReview(long userId, long characterId, long reviewId) {
        ensureCodexCharacter(characterId);
        boolean deleted = reviewRepository.delete(userId, characterId, reviewId);
        if (!deleted) {
            throw new IllegalArgumentException("Review not found.");
        }
        return reviews(userId, characterId, 0, DEFAULT_PAGE_SIZE);
    }

    private void ensureCodexCharacter(long characterId) {
        characterRepository.findCodexCharacterById(characterId)
                .orElseThrow(CharacterNotFoundException::new);
    }

    private int normalizePage(Integer page) {
        if (page == null) {
            return 0;
        }
        return Math.max(0, page);
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    }

    private CodexReviewResponse toResponse(CodexReview review, boolean mine) {
        return new CodexReviewResponse(
                review.getId(),
                review.getStars(),
                review.getText(),
                review.getCreatedAt(),
                mine
        );
    }
}
