package com.commitgotchi.character.image;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Builds the S3 presigner used to derive read-time GET URLs for character
 * sprite sheets. Credentials follow the same chain as the SQS client: explicit
 * static keys only if provided, otherwise DefaultCredentialsProvider
 * (local AWS profile / prod EC2 instance role). No static key is required.
 */
@Configuration(proxyBeanMethods = false)
public class CharacterS3Configuration {

    @Bean
    @ConditionalOnProperty(
            prefix = "commitgotchi.character.image",
            name = "s3-presigned-get-enabled",
            havingValue = "true"
    )
    public S3Presigner characterImageS3Presigner(CharacterImageProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.normalizedS3Region()))
                .credentialsProvider(credentialsProvider(properties));
        String endpoint = properties.normalizedS3EndpointUrl();
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    private AwsCredentialsProvider credentialsProvider(CharacterImageProperties properties) {
        String accessKeyId = properties.normalizedS3AccessKeyId();
        String secretAccessKey = properties.normalizedS3SecretAccessKey();
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(secretAccessKey)) {
            return DefaultCredentialsProvider.builder().build();
        }
        String sessionToken = properties.normalizedS3SessionToken();
        if (StringUtils.hasText(sessionToken)) {
            return StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken));
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey));
    }
}
