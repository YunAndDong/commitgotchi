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
    void appliesAllVersionedMigrationsAndJpaValidationStartsSuccessfully() {
        assertThat(Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().toString()))
                .containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    void createsSoftDeleteColumnsAndKeepsDeletedUserEmailReserved() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE (table_name = 'users' AND column_name = 'deleted_at')
                   OR (table_name = 'user_character' AND column_name = 'deleted_at')
                """, Integer.class)).isEqualTo(2);

        insertUser("soft-delete-email@example.com", "USER");
        jdbcTemplate.update("""
                UPDATE users
                SET deleted_at = CURRENT_TIMESTAMP
                WHERE email = 'soft-delete-email@example.com'
                """);

        assertThatThrownBy(() -> insertUser("soft-delete-email@example.com", "USER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createsReportRequestOutboxConstraintsAndIndexes() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'report_request_outbox' " +
                        "AND indexname IN ('idx_report_request_outbox_pending_available', " +
                        "'idx_report_request_outbox_user_date')",
                Integer.class
        )).isEqualTo(2);

        insertUser("report-outbox@example.com", "USER");
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = 'report-outbox@example.com'", Long.class);
        Long userCharacterId = insertUserCharacter(userId);

        insertReportRequestOutbox(
                "report-request-1",
                userId,
                userCharacterId,
                "2026-06-23",
                "0100011",
                "{\"algorithm\":3}",
                "PENDING",
                null
        );

        assertThatThrownBy(() -> insertReportRequestOutbox(
                "report-request-1",
                userId,
                userCharacterId,
                "2026-06-24",
                "0100011",
                "{}",
                "PENDING",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReportRequestOutbox(
                "report-request-2",
                userId,
                userCharacterId,
                "2026-06-23",
                "0100011",
                "{}",
                "PENDING",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReportRequestOutbox(
                "report-request-3",
                userId,
                userCharacterId,
                "2026-06-25",
                "0120011",
                "{}",
                "PENDING",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReportRequestOutbox(
                "report-request-4",
                userId,
                userCharacterId,
                "2026-06-25",
                "0100011",
                "{}",
                "PROCESSING",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReportRequestOutbox(
                "report-request-5",
                userId,
                userCharacterId,
                "2026-06-25",
                "0100011",
                "[]",
                "PENDING",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReportRequestOutbox(
                "report-request-6",
                userId,
                userCharacterId,
                "2026-06-25",
                "0100011",
                "{}",
                "SENT",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM report_request_outbox WHERE user_id = ?", Integer.class, userId
        )).isZero();
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

    private Long insertUserCharacter(Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO user_character (user_id, character_id, name, status_message)
                VALUES (?, 1, 'Reportmon', 'Ready to learn')
                RETURNING id
                """, Long.class, userId);
    }

    private void insertReportRequestOutbox(
            String requestId,
            Long userId,
            Long userCharacterId,
            String targetDate,
            String weeklyStudyStreak,
            String scoreDeltaHint,
            String status,
            String sentAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO report_request_outbox (
                    request_id,
                    user_id,
                    user_character_id,
                    target_date,
                    report_title,
                    report_content,
                    weekly_study_streak,
                    score_delta_hint,
                    focus,
                    status,
                    sent_at
                )
                VALUES (?, ?, ?, ?::date, 'Daily study record', 'Reviewed SQS and outbox.',
                        ?, ?::jsonb, 'Comment on algorithm learning progress',
                        ?, ?::timestamptz)
                """, requestId, userId, userCharacterId, targetDate, weeklyStudyStreak, scoreDeltaHint, status, sentAt);
    }
}
