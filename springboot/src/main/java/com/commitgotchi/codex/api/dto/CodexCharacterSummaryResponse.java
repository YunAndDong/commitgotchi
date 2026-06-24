package com.commitgotchi.codex.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record CodexCharacterSummaryResponse(
        Long id,
        String personality,
        String designKeyword,
        String imageStatus,
        JsonNode spriteMeta
) {
}
