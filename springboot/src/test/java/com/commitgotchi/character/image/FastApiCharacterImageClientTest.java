package com.commitgotchi.character.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FastApiCharacterImageClientTest {

    private static final String SPRITE_URL = "https://cdn.example.com/sprites/commitgotchi.png";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsSuccessWhenSpriteMetaHasExpectedFrameMap() throws Exception {
        CharacterImageGenerationResult result = generateWithResponse("""
                {
                  "status": "OK",
                  "spriteSheetUrl": "%s",
                  "spriteMeta": {
                    "frameMap": {
                      "baby": { "joy": [0, 0], "sad": [0, 1], "angry": [0, 2] },
                      "mature": { "joy": [1, 0], "sad": [1, 1], "angry": [1, 2] }
                    }
                  }
                }
                """.formatted(SPRITE_URL));

        assertThat(result.success()).isTrue();
        assertThat(result.spriteSheetUrl()).isEqualTo(SPRITE_URL);
    }

    @Test
    void sendsExpectedFastApiRequestShape() throws Exception {
        GeneratedResult generated = generateWithCapturedResponse("""
                {
                  "status": "OK",
                  "spriteSheetUrl": "%s",
                  "spriteMeta": {
                    "frameMap": {
                      "baby": { "joy": [0, 0], "sad": [0, 1], "angry": [0, 2] },
                      "mature": { "joy": [1, 0], "sad": [1, 1], "angry": [1, 2] }
                    }
                  }
                }
                """.formatted(SPRITE_URL));

        assertThat(generated.result().success()).isTrue();
        assertThat(generated.request().method()).isEqualTo("POST");
        assertThat(generated.request().path()).isEqualTo("/api/ai/commitgotchi");
        assertThat(generated.request().contentType()).contains("application/json");
        assertThat(objectMapper.readTree(generated.request().body()).path("userId").asLong()).isEqualTo(1L);
        assertThat(objectMapper.readTree(generated.request().body()).path("s3ObjectUrl").asText())
                .isEqualTo("s3://commitgotchi-character-images/sprites/users/1/characters/10/commitgotchi.png");
        assertThat(objectMapper.readTree(generated.request().body()).path("prompt").asText())
                .isEqualTo("Create a 2x3 sprite sheet");
    }

    @Test
    void rejectsOkResponseWithScalarSpriteMeta() throws Exception {
        CharacterImageGenerationResult result = generateWithResponse("""
                {
                  "status": "OK",
                  "spriteSheetUrl": "%s",
                  "spriteMeta": "not-a-frame-map"
                }
                """.formatted(SPRITE_URL));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("SPRITE_META_INVALID");
    }

    @Test
    void rejectsOkResponseWithIncompleteFrameMap() throws Exception {
        CharacterImageGenerationResult result = generateWithResponse("""
                {
                  "status": "OK",
                  "spriteSheetUrl": "%s",
                  "spriteMeta": {
                    "frameMap": {
                      "baby": { "joy": [0, 0], "sad": [0, 1] }
                    }
                  }
                }
                """.formatted(SPRITE_URL));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("SPRITE_META_INVALID");
    }

    private CharacterImageGenerationResult generateWithResponse(String responseBody) throws IOException {
        return generateWithCapturedResponse(responseBody).result();
    }

    private GeneratedResult generateWithCapturedResponse(String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();
        server.createContext("/api/ai/commitgotchi", exchange -> {
            capturedRequest.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
            ));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            FastApiCharacterImageClient client = new FastApiCharacterImageClient(
                    RestClient.builder(),
                    imageProperties("http://127.0.0.1:" + server.getAddress().getPort()),
                    objectMapper
            );
            CharacterImageGenerationResult result = client.generate(new CharacterImageGenerationRequest(
                    1L,
                    10L,
                    "green study slime",
                    "s3://commitgotchi-character-images/sprites/users/1/characters/10/commitgotchi.png",
                    "Create a 2x3 sprite sheet"
            ));
            return new GeneratedResult(result, capturedRequest.get());
        } finally {
            server.stop(0);
        }
    }

    private CharacterImageProperties imageProperties(String baseUrl) {
        CharacterImageProperties properties = new CharacterImageProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        return properties;
    }

    private record GeneratedResult(CharacterImageGenerationResult result, CapturedRequest request) {
    }

    private record CapturedRequest(String method, String path, String contentType, String body) {
    }
}
