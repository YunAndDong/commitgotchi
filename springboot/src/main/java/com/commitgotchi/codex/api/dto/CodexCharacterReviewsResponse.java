package com.commitgotchi.codex.api.dto;

import java.util.List;

public record CodexCharacterReviewsResponse(
        Long characterId,
        double averageStars,
        long totalReviews,
        boolean raisedByMe,
        boolean canReview,
        CodexReviewResponse myReview,
        List<CodexReviewResponse> reviews,
        int page,
        int size,
        boolean hasMore
) {
}
