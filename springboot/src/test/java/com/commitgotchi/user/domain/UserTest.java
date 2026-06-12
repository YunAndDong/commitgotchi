package com.commitgotchi.user.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void newUserAlwaysHasUserRole() {
        User user = User.create("person@example.com", "$2a$12$hash");

        assertThat(user.getEmail()).isEqualTo("person@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("$2a$12$hash");
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getCreatedAt()).isNotNull();
    }
}
