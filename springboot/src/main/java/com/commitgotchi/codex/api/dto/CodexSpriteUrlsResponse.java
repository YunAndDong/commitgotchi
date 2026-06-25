package com.commitgotchi.codex.api.dto;

import java.util.List;

public record CodexSpriteUrlsResponse(
        List<CodexSpriteUrlResponse> items
) {
}
