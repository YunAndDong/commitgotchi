package com.commitgotchi.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigurationTest {

    @Test
    void rejectsInvalidBase64Secret() {
        assertThatThrownBy(() -> new JwtConfiguration("not-base64!", "issuer", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsSecretShorterThan256Bits() {
        assertThatThrownBy(() -> new JwtConfiguration("c2hvcnQ=", "issuer", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class);
    }
}
