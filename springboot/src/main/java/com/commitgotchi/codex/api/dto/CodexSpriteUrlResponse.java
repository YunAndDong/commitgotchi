package com.commitgotchi.codex.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record CodexSpriteUrlResponse(
        Long id,
        String spriteSheetUrl,
        JsonNode spriteMeta,
        String imageStatus,
        Instant expiresAt
) {
}
