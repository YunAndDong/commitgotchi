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
