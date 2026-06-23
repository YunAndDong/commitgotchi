package com.commitgotchi.report.sqs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "commitgotchi.report.queue")
public class ReportQueueProperties {

    private boolean enabled = false;
    private String region = "ap-northeast-2";
    private String endpointUrl = "";
    private String accessKeyId = "";
    private String secretAccessKey = "";
    private String sessionToken = "";
    private String requestQueueName = "";
    private String requestQueueUrl = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getSecretAccessKey() {
        return secretAccessKey;
    }

    public void setSecretAccessKey(String secretAccessKey) {
        this.secretAccessKey = secretAccessKey;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public String getRequestQueueName() {
        return requestQueueName;
    }

    public void setRequestQueueName(String requestQueueName) {
        this.requestQueueName = requestQueueName;
    }

    public String getRequestQueueUrl() {
        return requestQueueUrl;
    }

    public void setRequestQueueUrl(String requestQueueUrl) {
        this.requestQueueUrl = requestQueueUrl;
    }

    public String normalizedRegion() {
        return StringUtils.hasText(region) ? region.strip() : "ap-northeast-2";
    }

    public String normalizedEndpointUrl() {
        return stripTrailingSlash(endpointUrl);
    }

    public String normalizedAccessKeyId() {
        return StringUtils.hasText(accessKeyId) ? accessKeyId.strip() : "";
    }

    public String normalizedSecretAccessKey() {
        return StringUtils.hasText(secretAccessKey) ? secretAccessKey.strip() : "";
    }

    public String normalizedSessionToken() {
        return StringUtils.hasText(sessionToken) ? sessionToken.strip() : "";
    }

    public String normalizedRequestQueueName() {
        return StringUtils.hasText(requestQueueName) ? requestQueueName.strip() : "";
    }

    public String normalizedRequestQueueUrl() {
        return StringUtils.hasText(requestQueueUrl) ? requestQueueUrl.strip() : "";
    }

    public boolean hasEndpointUrl() {
        return StringUtils.hasText(normalizedEndpointUrl());
    }

    public boolean hasStaticCredentials() {
        return StringUtils.hasText(normalizedAccessKeyId())
                && StringUtils.hasText(normalizedSecretAccessKey());
    }

    public boolean hasSessionToken() {
        return StringUtils.hasText(normalizedSessionToken());
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
