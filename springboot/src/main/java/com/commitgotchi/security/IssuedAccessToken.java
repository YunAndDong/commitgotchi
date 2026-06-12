package com.commitgotchi.security;

import java.time.Instant;

public record IssuedAccessToken(String value, Instant expiresAt) {
}
