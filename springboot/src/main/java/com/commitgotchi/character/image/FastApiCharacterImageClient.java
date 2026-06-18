package com.commitgotchi.character.image;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Locale;

@Component
public class FastApiCharacterImageClient implements CharacterImageClient {

    private static final Logger log = LoggerFactory.getLogger(FastApiCharacterImageClient.class);

    private final CharacterImageProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public FastApiCharacterImageClient(
            RestClient.Builder restClientBuilder,
            CharacterImageProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        RestClient.Builder imageClientBuilder = restClientBuilder.clone()
                .requestFactory(requestFactory(properties));
        if (properties.hasBaseUrl()) {
            imageClientBuilder.baseUrl(properties.normalizedBaseUrl());
        }
        this.restClient = imageClientBuilder.build();
    }

    @Override
    public CharacterImageGenerationResult generate(CharacterImageGenerationRequest request) {
        if (!properties.isEnabled()) {
            return CharacterImageGenerationResult.failure("DISABLED");
        }
        if (!properties.hasBaseUrl()) {
            return CharacterImageGenerationResult.failure("BASE_URL_MISSING");
        }

        try {
            FastApiResponse response = restClient.post()
                    .uri("/api/ai/commitgotchi")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new FastApiRequest(request.userId(), request.s3ObjectUrl(), request.prompt()))
                    .retrieve()
                    .body(FastApiResponse.class);
            return toResult(response, request.characterId());
        } catch (RestClientResponseException exception) {
            String reason = "HTTP_" + exception.getStatusCode().value();
            logFailure(reason, request.characterId());
            return CharacterImageGenerationResult.failure(reason);
        } catch (RestClientException exception) {
            String reason = "HTTP_CLIENT_ERROR";
            logFailure(reason, request.characterId());
            return CharacterImageGenerationResult.failure(reason);
        }
    }

    private CharacterImageGenerationResult toResult(FastApiResponse response, long characterId) {
        if (response == null) {
            logFailure("EMPTY_RESPONSE", characterId);
            return CharacterImageGenerationResult.failure("EMPTY_RESPONSE");
        }
        if (!"OK".equalsIgnoreCase(response.status())) {
            String reason = statusReason(response.status());
            logFailure(reason, characterId);
            return CharacterImageGenerationResult.failure(reason);
        }
        if (!StringUtils.hasText(response.spriteSheetUrl())) {
            logFailure("SPRITE_URL_MISSING", characterId);
            return CharacterImageGenerationResult.failure("SPRITE_URL_MISSING");
        }
        if (response.spriteMeta() == null || response.spriteMeta().isNull() || response.spriteMeta().isMissingNode()) {
            logFailure("SPRITE_META_MISSING", characterId);
            return CharacterImageGenerationResult.failure("SPRITE_META_MISSING");
        }
        if (!hasSpriteFrameMap(response.spriteMeta())) {
            logFailure("SPRITE_META_INVALID", characterId);
            return CharacterImageGenerationResult.failure("SPRITE_META_INVALID");
        }

        try {
            return CharacterImageGenerationResult.success(
                    response.spriteSheetUrl(),
                    objectMapper.writeValueAsString(response.spriteMeta())
            );
        } catch (JsonProcessingException exception) {
            logFailure("SPRITE_META_MAPPING_FAILED", characterId);
            return CharacterImageGenerationResult.failure("SPRITE_META_MAPPING_FAILED");
        }
    }

    private boolean hasSpriteFrameMap(JsonNode spriteMeta) {
        JsonNode frameMap = spriteMeta.path("frameMap");
        return spriteMeta.isObject()
                && hasFrameRow(frameMap.path("baby"))
                && hasFrameRow(frameMap.path("mature"));
    }

    private boolean hasFrameRow(JsonNode frameRow) {
        return frameRow.isObject()
                && isCoordinate(frameRow.path("joy"))
                && isCoordinate(frameRow.path("sad"))
                && isCoordinate(frameRow.path("angry"));
    }

    private boolean isCoordinate(JsonNode coordinate) {
        return coordinate.isArray()
                && coordinate.size() == 2
                && coordinate.get(0).isInt()
                && coordinate.get(1).isInt();
    }

    private String statusReason(String status) {
        if (!StringUtils.hasText(status)) {
            return "STATUS_MISSING";
        }
        String normalized = status.strip()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
        if (normalized.length() > 40) {
            normalized = normalized.substring(0, 40);
        }
        return "STATUS_" + normalized;
    }

    private void logFailure(String reason, long characterId) {
        log.warn(
                "Character image generation failed reason={} characterId={} traceId={}",
                reason,
                characterId,
                MDC.get("traceId")
        );
    }

    private SimpleClientHttpRequestFactory requestFactory(CharacterImageProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return requestFactory;
    }

    private record FastApiRequest(long userId, String s3ObjectUrl, String prompt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FastApiResponse(String status, String spriteSheetUrl, JsonNode spriteMeta) {
    }
}
