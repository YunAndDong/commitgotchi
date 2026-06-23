package com.commitgotchi.user.domain;

import java.time.Instant;

public class User {

    private Long id;

    private String email;

    private String passwordHash;

    private UserRole role;

    private Instant createdAt;

    private Instant deletedAt;

    protected User() {
    }

    private User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = UserRole.USER;
        this.createdAt = Instant.now();
    }

    public static User create(String email, String passwordHash) {
        return new User(email, passwordHash);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
