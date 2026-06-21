package com.commitgotchi.character;

import com.commitgotchi.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class CharacterDatabaseMigrationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsCharactersTableColumnsAndIndexes() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'characters'
                  AND column_name IN (
                    'id',
                    'user_id',
                    'name',
                    'design_keyword',
                    'personality',
                    'stat_db',
                    'stat_algorithm',
                    'stat_cs',
                    'stat_network',
                    'stat_framework',
                    'battle_power',
                    'emotion',
                    'status_message',
                    'is_evolved',
                    'image_status',
                    'sprite_sheet_url',
                    'sprite_meta',
                    'is_active',
                    'created_at',
                    'updated_at',
                    'version'
                  )
                """, Integer.class)).isEqualTo(21);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_name = 'characters'
                  AND column_name = 'sprite_meta'
                """, String.class)).isEqualTo("jsonb");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM pg_indexes
                WHERE tablename = 'characters'
                  AND indexname IN (
                    'uq_one_active_character_per_user',
                    'idx_characters_user_created_at',
                    'idx_characters_user_active'
                  )
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void activeCharacterPartialUniqueIndexAllowsOnlyOneActiveCharacterPerUser() {
        long userId = insertUser();

        insertCharacter(userId, "active-one", true);
        insertCharacter(userId, "inactive-one", false);
        insertCharacter(userId, "inactive-two", false);

        assertThatThrownBy(() -> insertCharacter(userId, "active-two", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void activeCharacterPartialUniqueIndexAllowsOneActiveCharacterForEachUser() {
        long firstUserId = insertUser();
        long secondUserId = insertUser();

        insertCharacter(firstUserId, "first-active", true);
        insertCharacter(secondUserId, "second-active", true);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE is_active = true",
                Integer.class
        )).isGreaterThanOrEqualTo(2);
    }

    @Test
    void characterConstraintsRejectInvalidRows() {
        long userId = insertUser();

        assertThatThrownBy(() -> insertCharacterWithOverrides(userId, "stat_db", "-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterWithOverrides(userId, "emotion", "'BORED'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterWithOverrides(userId, "image_status", "'DONE'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterWithOverrides(userId, "name", "E'\\t\\n'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterWithOverrides(userId, "design_keyword", "''"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterWithOverrides(userId, "personality", "'   '"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterWithOverrides(userId, "status_message", "E'\\t'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterWithOverrides(userId, "image_status", "'READY'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterWithOverrides(userId, "image_status", "'FALLBACK'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterWithImageStatusAndSprite(userId, "READY", "\t\n"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacter(Long.MAX_VALUE, "missing-user", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userDeleteCascadesCharacters() {
        long userId = insertUser();
        insertCharacter(userId, "cascade-target", false);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE user_id = ?",
                Integer.class,
                userId
        )).isZero();
    }

    @Test
    void battlePowerMustMatchStatTotalOnInsertAndUpdate() {
        long userId = insertUser();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO characters (
                    user_id,
                    name,
                    design_keyword,
                    personality,
                    status_message,
                    stat_db,
                    stat_algorithm,
                    stat_cs,
                    stat_network,
                    stat_framework,
                    battle_power
                )
                VALUES (?, 'mismatch', 'mismatch-design', 'mismatch-personality', 'Ready', 1, 2, 3, 4, 5, 10)
                """, userId)).isInstanceOf(DataIntegrityViolationException.class);

        long characterId = insertCharacter(userId, "consistent", false);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE characters SET stat_db = 7 WHERE id = ?",
                characterId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertUser() {
        String email = "character-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role) VALUES (?, ?, 'USER')",
                email,
                "$2a$12$placeholder"
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?",
                Long.class,
                email
        );
    }

    private long insertCharacter(long userId, String name, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO characters (
                    user_id,
                    name,
                    design_keyword,
                    personality,
                    status_message,
                    is_active
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, name, name + "-design", name + "-personality", "Ready", active);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM characters WHERE user_id = ? AND name = ?",
                Long.class,
                userId,
                name
        );
    }

    private void insertCharacterWithOverrides(long userId, String columnName, String sqlValue) {
        String name = valueFor(columnName, "name", "'valid-name'", sqlValue);
        String designKeyword = valueFor(columnName, "design_keyword", "'valid-design'", sqlValue);
        String personality = valueFor(columnName, "personality", "'valid-personality'", sqlValue);
        String statusMessage = valueFor(columnName, "status_message", "'Ready'", sqlValue);
        String statDb = valueFor(columnName, "stat_db", "0", sqlValue);
        String emotion = valueFor(columnName, "emotion", "'JOY'", sqlValue);
        String imageStatus = valueFor(columnName, "image_status", "'PENDING'", sqlValue);

        jdbcTemplate.update("""
                INSERT INTO characters (
                    user_id,
                    name,
                    design_keyword,
                    personality,
                    status_message,
                    stat_db,
                    emotion,
                    image_status
                )
                VALUES (?, %s, %s, %s, %s, %s, %s, %s)
                """.formatted(
                name,
                designKeyword,
                personality,
                statusMessage,
                statDb,
                emotion,
                imageStatus
        ), userId);
    }

    private void insertCharacterWithImageStatusAndSprite(long userId, String imageStatus, String spriteSheetUrl) {
        jdbcTemplate.update("""
                INSERT INTO characters (
                    user_id,
                    name,
                    design_keyword,
                    personality,
                    status_message,
                    image_status,
                    sprite_sheet_url
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                userId,
                "sprite-status",
                "sprite-design",
                "sprite-personality",
                "Ready",
                imageStatus,
                spriteSheetUrl
        );
    }

    private String valueFor(String actualColumn, String targetColumn, String defaultValue, String overrideValue) {
        if (actualColumn.equals(targetColumn)) {
            return overrideValue;
        }
        return defaultValue;
    }
}
