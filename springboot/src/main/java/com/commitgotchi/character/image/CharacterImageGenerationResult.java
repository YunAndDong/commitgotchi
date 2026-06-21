package com.commitgotchi.character.image;

import org.springframework.util.StringUtils;

public record CharacterImageGenerationResult(
        boolean success,
        String spriteSheetUrl,
        String spriteMeta,
        String failureReason
) {

    public static CharacterImageGenerationResult success(String spriteSheetUrl, String spriteMeta) {
        if (!StringUtils.hasText(spriteSheetUrl)) {
            throw new IllegalArgumentException("spriteSheetUrl must not be blank for a successful image result");
        }
        if (!StringUtils.hasText(spriteMeta)) {
            throw new IllegalArgumentException("spriteMeta must not be blank for a successful image result");
        }
        return new CharacterImageGenerationResult(true, spriteSheetUrl.strip(), spriteMeta.strip(), null);
    }

    public static CharacterImageGenerationResult failure(String reason) {
        String normalizedReason = StringUtils.hasText(reason) ? reason.strip() : "UNKNOWN";
        return new CharacterImageGenerationResult(false, null, null, normalizedReason);
    }
}
