package com.commitgotchi.codex.application;

import com.commitgotchi.character.domain.CodexCharacterProjection;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.character.image.CharacterImagePresignService;
import com.commitgotchi.character.image.CharacterImageProperties;
import com.commitgotchi.codex.api.dto.CodexCharacterSummaryResponse;
import com.commitgotchi.codex.api.dto.CodexCharactersPageResponse;
import com.commitgotchi.codex.api.dto.CodexSpriteUrlResponse;
import com.commitgotchi.codex.api.dto.CodexSpriteUrlsResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class CodexCharacterService {

    private static final int DEFAULT_PAGE_SIZE = 12;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_SPRITE_URL_IDS = 7;
    private static final String S3_SCHEME_PREFIX = "s3://";

    private final LearningCharacterRepository characterRepository;
    private final ObjectMapper objectMapper;
    private final CharacterImagePresignService presignService;
    private final CharacterImageProperties imageProperties;

    public CodexCharacterService(
            LearningCharacterRepository characterRepository,
            ObjectMapper objectMapper,
            CharacterImagePresignService presignService,
            CharacterImageProperties imageProperties
    ) {
        this.characterRepository = characterRepository;
        this.objectMapper = objectMapper;
        this.presignService = presignService;
        this.imageProperties = imageProperties;
    }

    @Transactional(readOnly = true)
    public CodexCharactersPageResponse listCharacters(Long afterId, Integer limit) {
        int pageSize = normalizePageSize(limit);
        List<CodexCharacterProjection> rows = characterRepository.findCodexCharactersAfterId(afterId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<CodexCharacterProjection> page = hasMore ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
        return new CodexCharactersPageResponse(
                page.stream().map(this::toSummaryResponse).toList(),
                nextCursor,
                hasMore
        );
    }

    @Transactional(readOnly = true)
    public CodexSpriteUrlsResponse spriteUrls(List<Long> ids) {
        List<Long> uniqueIds = uniqueIds(ids);
        if (uniqueIds.isEmpty()) {
            return new CodexSpriteUrlsResponse(List.of());
        }
        if (uniqueIds.size() > MAX_SPRITE_URL_IDS) {
            throw new IllegalArgumentException("ids must contain at most " + MAX_SPRITE_URL_IDS + " unique values");
        }

        Instant now = Instant.now();
        List<CodexSpriteUrlResponse> items = characterRepository.findCodexCharactersByIds(uniqueIds).stream()
                .map(character -> toSpriteUrlResponse(character, now))
                .toList();
        return new CodexSpriteUrlsResponse(items);
    }

    private int normalizePageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(MAX_PAGE_SIZE, Math.max(1, limit));
    }

    private List<Long> uniqueIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return new LinkedHashSet<>(ids).stream().toList();
    }

    private CodexCharacterSummaryResponse toSummaryResponse(CodexCharacterProjection character) {
        return new CodexCharacterSummaryResponse(
                character.getId(),
                character.getPersonality(),
                character.getDesignKeyword(),
                statusName(character),
                spriteMeta(character.getSpriteMeta())
        );
    }

    private CodexSpriteUrlResponse toSpriteUrlResponse(CodexCharacterProjection character, Instant now) {
        String storedUrl = character.getSpriteSheetUrl();
        String displayUrl = presignService.toDisplayUrl(storedUrl);
        return new CodexSpriteUrlResponse(
                character.getId(),
                displayUrl,
                spriteMeta(character.getSpriteMeta()),
                statusName(character),
                expiresAt(storedUrl, displayUrl, now)
        );
    }

    private String statusName(CodexCharacterProjection character) {
        if (character.getImageStatus() == null) {
            return null;
        }
        return character.getImageStatus().name().toUpperCase(Locale.ROOT);
    }

    private JsonNode spriteMeta(String spriteMeta) {
        if (!StringUtils.hasText(spriteMeta)) {
            return NullNode.instance;
        }
        try {
            return objectMapper.readTree(spriteMeta);
        } catch (JsonProcessingException exception) {
            return NullNode.instance;
        }
    }

    private Instant expiresAt(String storedUrl, String displayUrl, Instant now) {
        if (!StringUtils.hasText(storedUrl)
                || !storedUrl.startsWith(S3_SCHEME_PREFIX)
                || !StringUtils.hasText(displayUrl)
                || storedUrl.equals(displayUrl)
                || !imageProperties.isS3PresignedGetEnabled()) {
            return null;
        }
        return now.plus(imageProperties.normalizedS3PresignedUrlTtl());
    }
}
