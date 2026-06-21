package com.commitgotchi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "commitgotchi.internal-api")
public class InternalApiProperties {

    private String secret = "";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public boolean hasSecret() {
        return StringUtils.hasText(secret);
    }

    public String normalizedSecret() {
        return StringUtils.hasText(secret) ? secret.strip() : "";
    }
}
