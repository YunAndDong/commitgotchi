package com.commitgotchi.character.image;

import org.springframework.util.StringUtils;

public record CharacterImageGenerationRequest(
        long userId,
        long characterId,
        String designKeyword,
        String s3ObjectUrl,
        String prompt
) {

    public CharacterImageGenerationRequest {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (characterId <= 0) {
            throw new IllegalArgumentException("characterId must be positive");
        }
        designKeyword = requireText(designKeyword, "designKeyword");
        s3ObjectUrl = requireText(s3ObjectUrl, "s3ObjectUrl");
        prompt = requireText(prompt, "prompt");
    }

    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
