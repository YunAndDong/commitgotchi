package com.commitgotchi.character.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;

/**
 * Read-side helper that turns a persisted S3 object location (s3://bucket/key)
 * into a short-lived presigned GET URL at projection time.
 *
 * <p>The persisted value is a stable key, never an expiring URL. A fresh
 * presigned URL is minted on every read so it cannot go stale in the database.
 * Non-S3 values (e.g. the bundled fallback sprite "/character-assets/...") pass
 * through unchanged.
 */
@Service
public class CharacterImagePresignService {

    private static final Logger log = LoggerFactory.getLogger(CharacterImagePresignService.class);
    private static final String S3_SCHEME_PREFIX = "s3://";

    private final CharacterImageProperties properties;
    private final ObjectProvider<S3Presigner> presignerProvider;

    public CharacterImagePresignService(
            CharacterImageProperties properties,
            ObjectProvider<S3Presigner> presignerProvider
    ) {
        this.properties = properties;
        this.presignerProvider = presignerProvider;
    }

    /**
     * If the stored value is an s3:// location and presigning is available,
     * return a freshly presigned GET URL; otherwise return the value unchanged.
     */
    public String toDisplayUrl(String storedValue) {
        if (!StringUtils.hasText(storedValue) || !storedValue.startsWith(S3_SCHEME_PREFIX)) {
            return storedValue;
        }
        if (!properties.isS3PresignedGetEnabled()) {
            return storedValue;
        }
        S3Presigner presigner = presignerProvider.getIfAvailable();
        if (presigner == null) {
            return storedValue;
        }

        try {
            URI uri = URI.create(storedValue);
            String bucket = uri.getHost();
            String key = uri.getPath();
            if (StringUtils.hasText(key) && key.startsWith("/")) {
                key = key.substring(1);
            }
            if (!StringUtils.hasText(bucket) || !StringUtils.hasText(key)) {
                return storedValue;
            }

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(properties.normalizedS3PresignedUrlTtl())
                    .getObjectRequest(getObjectRequest)
                    .build();
            return presigner.presignGetObject(presignRequest).url().toString();
        } catch (RuntimeException exception) {
            // Never break a character read because presigning failed; the value
            // simply will not render until the next successful read.
            log.warn("Failed to presign character sprite GET URL reason={}", exception.getClass().getSimpleName());
            return storedValue;
        }
    }
}
