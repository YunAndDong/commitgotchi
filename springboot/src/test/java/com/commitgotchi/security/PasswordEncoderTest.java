package com.commitgotchi.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderTest {

    @Test
    void usesCostTwelveAndMatchesOnlyTheOriginalPassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

        String hash = encoder.encode("very-secure-password");

        assertThat(hash).containsPattern("^\\$2[ayb]\\$12\\$");
        assertThat(encoder.matches("very-secure-password", hash)).isTrue();
        assertThat(encoder.matches("wrong-password", hash)).isFalse();
    }
}
