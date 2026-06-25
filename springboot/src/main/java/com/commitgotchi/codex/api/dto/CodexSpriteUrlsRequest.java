package com.commitgotchi.codex.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CodexSpriteUrlsRequest(
        @NotNull
        @Size(max = 7)
        List<@NotNull Long> ids
) {
}
