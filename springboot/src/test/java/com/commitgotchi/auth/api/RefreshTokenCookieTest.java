package com.commitgotchi.auth.api;

import com.commitgotchi.auth.api.dto.TokenPairResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenCookieTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    void issuesHttpOnlyScopedCookieAndClearsIt() {
        RefreshTokenCookie cookies = new RefreshTokenCookie(true, Clock.fixed(NOW, ZoneOffset.UTC));
        TokenPairResponse tokens = new TokenPairResponse(
                "Bearer", "access", NOW.plusSeconds(900), "refresh", NOW.plusSeconds(3600)
        );

        ResponseCookie issued = cookies.issue(tokens);
        assertThat(issued.isHttpOnly()).isTrue();
        assertThat(issued.isSecure()).isTrue();
        assertThat(issued.getSameSite()).isEqualTo("None");
        assertThat(issued.getPath()).isEqualTo("/api/auth");
        assertThat(issued.getMaxAge()).hasSeconds(3600);

        assertThat(cookies.clear().getMaxAge()).isZero();
    }

    @Test
    void nonSecureLocalCookieUsesLaxSameSitePolicy() {
        RefreshTokenCookie cookies = new RefreshTokenCookie(false, Clock.fixed(NOW, ZoneOffset.UTC));
        TokenPairResponse tokens = new TokenPairResponse(
                "Bearer", "access", NOW.plusSeconds(900), "refresh", NOW.plusSeconds(3600)
        );

        ResponseCookie issued = cookies.issue(tokens);
        assertThat(issued.isSecure()).isFalse();
        assertThat(issued.getSameSite()).isEqualTo("Lax");
    }
}
