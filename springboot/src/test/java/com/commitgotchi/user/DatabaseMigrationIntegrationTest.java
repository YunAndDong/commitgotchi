package com.commitgotchi.user;

import com.commitgotchi.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DatabaseMigrationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesOnlyVersionOneAndTwoAndJpaValidationStartsSuccessfully() {
        assertThat(Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().toString()))
                .containsExactly("1", "2");
    }

    @Test
    void createsRefreshTokenConstraintsAndIndexes() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'refresh_tokens' " +
                        "AND indexname IN ('idx_refresh_tokens_user_active', 'idx_refresh_tokens_cleanup')",
                Integer.class
        )).isEqualTo(2);

        insertUser("refresh-db@example.com", "USER");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = 'refresh-db@example.com'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at)
                VALUES (?::uuid, ?, ?, CURRENT_TIMESTAMP + interval '30 days', CURRENT_TIMESTAMP)
                """, java.util.UUID.randomUUID().toString(), userId, "a".repeat(64));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at)
                VALUES (?::uuid, ?, ?, CURRENT_TIMESTAMP + interval '30 days', CURRENT_TIMESTAMP)
                """, java.util.UUID.randomUUID().toString(), userId, "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at)
                VALUES (?::uuid, ?, ?, CURRENT_TIMESTAMP + interval '30 days', CURRENT_TIMESTAMP)
                """, java.util.UUID.randomUUID().toString(), Long.MAX_VALUE, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at)
                VALUES (?::uuid, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, java.util.UUID.randomUUID().toString(), userId, "c".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked_at, created_at)
                VALUES (?::uuid, ?, ?, CURRENT_TIMESTAMP + interval '30 days',
                        CURRENT_TIMESTAMP - interval '1 second', CURRENT_TIMESTAMP)
                """, java.util.UUID.randomUUID().toString(), userId, "d".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId
        )).isZero();
    }

    @Test
    void databaseEnforcesNormalizedUniqueEmailAndRoleChecks() {
        insertUser("db-check@example.com", "USER");

        assertThatThrownBy(() -> insertUser("db-check@example.com", "USER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(" Not-Normalized@example.com ", "USER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser("admin-injection@example.com", "OWNER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertUser(String email, String role) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?)",
                email,
                "$2a$12$placeholder",
                role
        );
    }
}
