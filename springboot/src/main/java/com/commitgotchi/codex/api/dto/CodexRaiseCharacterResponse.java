package com.commitgotchi.codex.api.dto;

public record CodexRaiseCharacterResponse(
        Long userCharacterId,
        boolean created
) {
}
