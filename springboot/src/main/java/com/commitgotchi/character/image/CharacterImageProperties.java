package com.commitgotchi.character.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "commitgotchi.character.image")
public class CharacterImageProperties {

    private boolean enabled = false;
    private String baseUrl = "";
    private String fallbackSpriteSheetUrl = "https://cdn.commitgotchi.local/sprites/fallback-default.png";
    private String s3ObjectPrefix = "s3://commitgotchi-character-images/sprites";
    private boolean s3PresignedUrlEnabled = false;
    private String s3Region = "ap-northeast-2";
    private String s3AccessKeyId = "";
    private String s3SecretAccessKey = "";
    private String s3SessionToken = "";
    private String s3EndpointUrl = "";
    private Duration s3PresignedUrlTtl = Duration.ofMinutes(10);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getFallbackSpriteSheetUrl() {
        return fallbackSpriteSheetUrl;
    }

    public void setFallbackSpriteSheetUrl(String fallbackSpriteSheetUrl) {
        this.fallbackSpriteSheetUrl = fallbackSpriteSheetUrl;
    }

    public String getS3ObjectPrefix() {
        return s3ObjectPrefix;
    }

    public void setS3ObjectPrefix(String s3ObjectPrefix) {
        this.s3ObjectPrefix = s3ObjectPrefix;
    }

    public boolean isS3PresignedUrlEnabled() {
        return s3PresignedUrlEnabled;
    }

    public void setS3PresignedUrlEnabled(boolean s3PresignedUrlEnabled) {
        this.s3PresignedUrlEnabled = s3PresignedUrlEnabled;
    }

    public String getS3Region() {
        return s3Region;
    }

    public void setS3Region(String s3Region) {
        this.s3Region = s3Region;
    }

    public String getS3AccessKeyId() {
        return s3AccessKeyId;
    }

    public void setS3AccessKeyId(String s3AccessKeyId) {
        this.s3AccessKeyId = s3AccessKeyId;
    }

    public String getS3SecretAccessKey() {
        return s3SecretAccessKey;
    }

    public void setS3SecretAccessKey(String s3SecretAccessKey) {
        this.s3SecretAccessKey = s3SecretAccessKey;
    }

    public String getS3SessionToken() {
        return s3SessionToken;
    }

    public void setS3SessionToken(String s3SessionToken) {
        this.s3SessionToken = s3SessionToken;
    }

    public String getS3EndpointUrl() {
        return s3EndpointUrl;
    }

    public void setS3EndpointUrl(String s3EndpointUrl) {
        this.s3EndpointUrl = s3EndpointUrl;
    }

    public Duration getS3PresignedUrlTtl() {
        return s3PresignedUrlTtl;
    }

    public void setS3PresignedUrlTtl(Duration s3PresignedUrlTtl) {
        this.s3PresignedUrlTtl = s3PresignedUrlTtl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public boolean hasBaseUrl() {
        return StringUtils.hasText(baseUrl);
    }

    public String normalizedBaseUrl() {
        return stripTrailingSlash(baseUrl);
    }

    public String normalizedFallbackSpriteSheetUrl() {
        return StringUtils.hasText(fallbackSpriteSheetUrl) ? fallbackSpriteSheetUrl.strip() : "";
    }

    public String normalizedS3ObjectPrefix() {
        if (!StringUtils.hasText(s3ObjectPrefix)) {
            return "s3://commitgotchi-character-images/sprites";
        }
        return stripTrailingSlash(s3ObjectPrefix);
    }

    public String normalizedS3Region() {
        return StringUtils.hasText(s3Region) ? s3Region.strip() : "";
    }

    public String normalizedS3AccessKeyId() {
        return StringUtils.hasText(s3AccessKeyId) ? s3AccessKeyId.strip() : "";
    }

    public String normalizedS3SecretAccessKey() {
        return StringUtils.hasText(s3SecretAccessKey) ? s3SecretAccessKey.strip() : "";
    }

    public String normalizedS3SessionToken() {
        return StringUtils.hasText(s3SessionToken) ? s3SessionToken.strip() : "";
    }

    public String normalizedS3EndpointUrl() {
        return stripTrailingSlash(s3EndpointUrl);
    }

    public Duration normalizedS3PresignedUrlTtl() {
        return s3PresignedUrlTtl == null ? Duration.ofMinutes(10) : s3PresignedUrlTtl;
    }

    private String stripTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
