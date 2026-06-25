package com.commitgotchi.codex.api.dto;

import java.util.List;

public record CodexCharactersPageResponse(
        List<CodexCharacterSummaryResponse> items,
        Long nextCursor,
        boolean hasMore
) {
}
