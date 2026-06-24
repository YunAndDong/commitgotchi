package com.commitgotchi.character.image;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterImagePresignServiceTest {

    private static final String S3_KEY = "s3://commitgotchi-character-images-491013322019/dev/characters/42/sprite-sheet.png";

    @Test
    void presignsS3LocationIntoGetUrlWhenEnabled() {
        CharacterImagePresignService service = service(enabledProperties(), realPresigner());

        String url = service.toDisplayUrl(S3_KEY);

        assertThat(url).startsWith("https://");
        assertThat(url).contains("commitgotchi-character-images-491013322019");
        assertThat(url).contains("dev/characters/42/sprite-sheet.png");
        // SigV4 presigned GET markers
        assertThat(url).contains("X-Amz-Signature=");
        assertThat(url).contains("X-Amz-Expires=");
        assertThat(url).doesNotStartWith("s3://");
    }

    @Test
    void passesThroughNonS3Values() {
        CharacterImagePresignService service = service(enabledProperties(), realPresigner());

        assertThat(service.toDisplayUrl("/character-assets/default_image1.png"))
                .isEqualTo("/character-assets/default_image1.png");
        assertThat(service.toDisplayUrl(null)).isNull();
        assertThat(service.toDisplayUrl("")).isEqualTo("");
    }

    @Test
    void passesThroughWhenPresignedGetDisabled() {
        CharacterImageProperties properties = enabledProperties();
        properties.setS3PresignedGetEnabled(false);
        CharacterImagePresignService service = service(properties, realPresigner());

        assertThat(service.toDisplayUrl(S3_KEY)).isEqualTo(S3_KEY);
    }

    @Test
    void passesThroughWhenNoPresignerAvailable() {
        CharacterImagePresignService service = service(enabledProperties(), null);

        assertThat(service.toDisplayUrl(S3_KEY)).isEqualTo(S3_KEY);
    }

    private CharacterImageProperties enabledProperties() {
        CharacterImageProperties properties = new CharacterImageProperties();
        properties.setS3PresignedGetEnabled(true);
        properties.setS3Region("ap-northeast-2");
        properties.setS3PresignedUrlTtl(Duration.ofMinutes(10));
        return properties;
    }

    private S3Presigner realPresigner() {
        return S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIATESTTESTTESTTEST", "test-secret-key")))
                .build();
    }

    @SuppressWarnings("unchecked")
    private CharacterImagePresignService service(CharacterImageProperties properties, S3Presigner presigner) {
        ObjectProvider<S3Presigner> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(presigner);
        return new CharacterImagePresignService(properties, provider);
    }
}
