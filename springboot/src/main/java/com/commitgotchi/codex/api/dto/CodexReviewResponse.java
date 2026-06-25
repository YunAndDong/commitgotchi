package com.commitgotchi.codex.api.dto;

import java.time.Instant;

public record CodexReviewResponse(
        Long id,
        int stars,
        String text,
        Instant createdAt,
        boolean mine
) {
}
