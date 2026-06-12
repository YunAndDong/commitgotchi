package com.commitgotchi.security;

import com.commitgotchi.user.domain.UserRole;

public record AuthPrincipal(long userId, String email, UserRole role) {
}
