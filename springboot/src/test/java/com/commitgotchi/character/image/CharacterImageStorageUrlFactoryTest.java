package com.commitgotchi.character.image;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CharacterImageStorageUrlFactoryTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-19T12:34:56Z"), ZoneOffset.UTC);

    @Test
    void returnsStableS3ObjectUrlWhenPresignedUrlIsDisabled() {
        CharacterImageProperties properties = imageProperties();

        String storageUrl = new CharacterImageStorageUrlFactory(properties, FIXED_CLOCK)
                .createStorageUrl(7L, 12L);

        assertThat(storageUrl)
                .isEqualTo("s3://commitgotchi-character-images/sprites/users/7/characters/12/commitgotchi.png");
    }

    @Test
    void createsPresignedPutUrlForConfiguredS3ObjectLocation() {
        CharacterImageProperties properties = imageProperties();
        properties.setS3PresignedUrlEnabled(true);

        String storageUrl = new CharacterImageStorageUrlFactory(properties, FIXED_CLOCK)
                .createStorageUrl(7L, 12L);

        URI uri = URI.create(storageUrl);
        Map<String, String> query = decodedQuery(uri);

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("commitgotchi-character-images.s3.ap-northeast-2.amazonaws.com");
        assertThat(uri.getPath()).isEqualTo("/sprites/users/7/characters/12/commitgotchi.png");
        assertThat(query)
                .containsEntry("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .containsEntry("X-Amz-Credential", "test-access-key/20260619/ap-northeast-2/s3/aws4_request")
                .containsEntry("X-Amz-Date", "20260619T123456Z")
                .containsEntry("X-Amz-Expires", "600")
                .containsEntry("X-Amz-SignedHeaders", "host");
        assertThat(query.get("X-Amz-Signature")).matches("[0-9a-f]{64}");
        assertThat(storageUrl).doesNotContain("test-secret-key");
    }

    @Test
    void createsPathStylePresignedUrlForCustomS3Endpoint() {
        CharacterImageProperties properties = imageProperties();
        properties.setS3PresignedUrlEnabled(true);
        properties.setS3EndpointUrl("http://localhost:9000/minio");
        properties.setS3SessionToken("session/token+value");

        String storageUrl = new CharacterImageStorageUrlFactory(properties, FIXED_CLOCK)
                .createStorageUrl(7L, 12L);

        URI uri = URI.create(storageUrl);
        Map<String, String> query = decodedQuery(uri);

        assertThat(uri.getScheme()).isEqualTo("http");
        assertThat(uri.getHost()).isEqualTo("localhost");
        assertThat(uri.getPort()).isEqualTo(9000);
        assertThat(uri.getPath())
                .isEqualTo("/minio/commitgotchi-character-images/sprites/users/7/characters/12/commitgotchi.png");
        assertThat(query).containsEntry("X-Amz-Security-Token", "session/token+value");
    }

    @Test
    void rejectsPresigningWhenCredentialsAreMissing() {
        CharacterImageProperties properties = imageProperties();
        properties.setS3PresignedUrlEnabled(true);
        properties.setS3AccessKeyId("");

        assertThatThrownBy(() -> new CharacterImageStorageUrlFactory(properties, FIXED_CLOCK)
                .createStorageUrl(7L, 12L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("s3AccessKeyId");
    }

    private CharacterImageProperties imageProperties() {
        CharacterImageProperties properties = new CharacterImageProperties();
        properties.setS3ObjectPrefix("s3://commitgotchi-character-images/sprites");
        properties.setS3Region("ap-northeast-2");
        properties.setS3AccessKeyId("test-access-key");
        properties.setS3SecretAccessKey("test-secret-key");
        properties.setS3PresignedUrlTtl(Duration.ofMinutes(10));
        return properties;
    }

    private Map<String, String> decodedQuery(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> decode(pair.length == 2 ? pair[1] : "")
                ));
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
