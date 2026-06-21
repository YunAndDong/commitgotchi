package com.commitgotchi.auth.api;

import com.commitgotchi.auth.api.dto.TokenPairResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class RefreshTokenCookie {

    public static final String NAME = "cg_refresh";

    private final boolean secure;
    private final Clock clock;

    public RefreshTokenCookie(
            @Value("${commitgotchi.auth.refresh-cookie-secure:false}") boolean secure,
            Clock clock
    ) {
        this.secure = secure;
        this.clock = clock;
    }

    public ResponseCookie issue(TokenPairResponse tokens) {
        Duration maxAge = Duration.between(clock.instant(), tokens.refreshTokenExpiresAt());
        return base(tokens.refreshToken()).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .path("/api/auth");
    }
}
