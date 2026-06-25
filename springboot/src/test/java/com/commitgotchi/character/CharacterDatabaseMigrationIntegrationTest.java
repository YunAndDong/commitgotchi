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

    private static final String SPRITE_META = """
            {"columns":3,"rows":1,"frameMap":{"joy":[0,0],"sad":[0,1],"angry":[0,2]},"transparent":true}
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsCharacterCatalogTableFromArchitectureContract() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'characters'
                  AND column_name IN (
                    'id',
                    'personality',
                    'design_keyword',
                    'sprite_sheet_url',
                    'sprite_meta',
                    'image_status',
                    'created_at'
                  )
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'characters'
                  AND column_name IN (
                    'user_id',
                    'name',
                    'status_message',
                    'is_active',
                    'is_evolved',
                    'deleted_at'
                  )
                """, Integer.class)).isZero();
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
                  AND indexname = 'idx_characters_created_at'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void seedsDefaultBabyCharacterCatalogRows() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM characters
                WHERE id IN (1, 2, 3)
                  AND personality IS NULL
                  AND design_keyword IS NULL
                  AND image_status = 'READY'
                  AND sprite_meta = ?::jsonb
                """, Integer.class, SPRITE_META)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sprite_sheet_url
                FROM characters
                WHERE id = 1
                """, String.class))
                .isEqualTo("s3://commitgotchi-character-images/sprites/characters/1/sprite-sheet.png");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sprite_sheet_url
                FROM characters
                WHERE id = 2
                """, String.class))
                .isEqualTo("s3://commitgotchi-character-images/sprites/characters/2/sprite-sheet.png");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sprite_sheet_url
                FROM characters
                WHERE id = 3
                """, String.class))
                .isEqualTo("s3://commitgotchi-character-images/sprites/characters/3/sprite-sheet.png");
    }

    @Test
    void generatedCharacterCatalogIdsAreReservedAfterDefaultBabyRows() {
        Long generatedId = jdbcTemplate.queryForObject("""
                INSERT INTO characters (
                    personality,
                    design_keyword,
                    image_status
                )
                VALUES ('generated-personality', 'generated-design', 'PENDING')
                RETURNING id
                """, Long.class);

        assertThat(generatedId).isNotNull().isGreaterThan(3L);

        jdbcTemplate.update("DELETE FROM characters WHERE id = ?", generatedId);
    }

    @Test
    void createsUserCharacterTableColumnsAndIndexes() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'user_character'
                  AND column_name IN (
                    'id',
                    'user_id',
                    'character_id',
                    'name',
                    'stat_db',
                    'stat_algorithm',
                    'stat_cs',
                    'stat_network',
                    'stat_framework',
                    'battle_power',
                    'emotion',
                    'status_message',
                    'is_evolved',
                    'is_active',
                    'created_at',
                    'deleted_at'
                  )
                """, Integer.class)).isEqualTo(16);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM pg_indexes
                WHERE tablename = 'user_character'
                  AND indexname IN (
                    'uq_one_active_character_per_user',
                    'idx_user_character_user_created_at',
                    'idx_user_character_user_active',
                    'idx_user_character_character'
                  )
                """, Integer.class)).isEqualTo(4);
    }

    @Test
    void createsCodexCharacterReviewTableColumnsAndIndexes() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'codex_character_reviews'
                  AND column_name IN (
                    'id',
                    'user_id',
                    'character_id',
                    'stars',
                    'text',
                    'created_at',
                    'updated_at'
                  )
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM pg_indexes
                WHERE tablename = 'codex_character_reviews'
                  AND indexname IN (
                    'uq_codex_character_review_user_character',
                    'idx_codex_character_reviews_character_created_at'
                  )
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void activeUserCharacterPartialUniqueIndexAllowsOnlyOneActiveCharacterPerUser() {
        long userId = insertUser();

        insertUserCharacter(userId, "active-one", true);
        insertUserCharacter(userId, "inactive-one", false);
        insertUserCharacter(userId, "inactive-two", false);

        assertThatThrownBy(() -> insertUserCharacter(userId, "active-two", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void activeUserCharacterPartialUniqueIndexAllowsOneActiveCharacterForEachUser() {
        long firstUserId = insertUser();
        long secondUserId = insertUser();

        insertUserCharacter(firstUserId, "first-active", true);
        insertUserCharacter(secondUserId, "second-active", true);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_character WHERE is_active = true",
                Integer.class
        )).isGreaterThanOrEqualTo(2);
    }

    @Test
    void activeUserCharacterPartialUniqueIndexIgnoresSoftDeletedRows() {
        long userId = insertUser();
        long deletedActiveId = insertUserCharacter(userId, "deleted-active", true);
        jdbcTemplate.update(
                "UPDATE user_character SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
                deletedActiveId
        );

        long replacementId = insertUserCharacter(userId, "replacement-active", true);

        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT count(*)
                        FROM user_character
                        WHERE user_id = ?
                          AND is_active = true
                          AND deleted_at IS NULL
                        """,
                Integer.class,
                userId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NULL FROM user_character WHERE id = ?",
                Boolean.class,
                replacementId
        )).isTrue();
    }

    @Test
    void characterCatalogConstraintsRejectInvalidRows() {
        assertThatThrownBy(() -> insertCharacterCatalogWithOverrides("design_keyword", "''"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterCatalogWithOverrides("personality", "'   '"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterCatalogWithOverrides("image_status", "'DONE'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterCatalogWithOverrides("image_status", "'READY'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCharacterCatalogWithImageStatusAndSprite("FALLBACK", "\t\n"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userCharacterConstraintsRejectInvalidRows() {
        long userId = insertUser();

        assertThatThrownBy(() -> insertUserCharacterWithOverrides(userId, "stat_db", "-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUserCharacterWithOverrides(userId, "emotion", "'BORED'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUserCharacterWithOverrides(userId, "name", "E'\\t\\n'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUserCharacterWithOverrides(userId, "status_message", "E'\\t'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUserCharacter(Long.MAX_VALUE, "missing-user", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO user_character (
                    user_id,
                    character_id,
                    name,
                    status_message
                )
                VALUES (?, ?, 'missing-character', 'Ready')
                """, userId, Long.MAX_VALUE)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userDeleteCascadesUserCharacterButKeepsSharedCharacterCatalog() {
        long userId = insertUser();
        insertUserCharacter(userId, "cascade-target", false);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_character WHERE user_id = ?",
                Integer.class,
                userId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM characters WHERE id IN (1, 2, 3)",
                Integer.class
        )).isEqualTo(3);
    }

    @Test
    void battlePowerMustMatchUserCharacterStatTotalOnInsertAndUpdate() {
        long userId = insertUser();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO user_character (
                    user_id,
                    character_id,
                    name,
                    status_message,
                    stat_db,
                    stat_algorithm,
                    stat_cs,
                    stat_network,
                    stat_framework,
                    battle_power
                )
                VALUES (?, 1, 'mismatch', 'Ready', 1, 2, 3, 4, 5, 10)
                """, userId)).isInstanceOf(DataIntegrityViolationException.class);

        long userCharacterId = insertUserCharacter(userId, "consistent", false);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE user_character SET stat_db = 7 WHERE id = ?",
                userCharacterId
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

    private long insertUserCharacter(long userId, String name, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO user_character (
                    user_id,
                    character_id,
                    name,
                    status_message,
                    is_active
                )
                VALUES (?, 1, ?, ?, ?)
                """, userId, name, "Ready", active);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_character WHERE user_id = ? AND name = ?",
                Long.class,
                userId,
                name
        );
    }

    private void insertCharacterCatalogWithOverrides(String columnName, String sqlValue) {
        String designKeyword = valueFor(columnName, "design_keyword", "'valid-design'", sqlValue);
        String personality = valueFor(columnName, "personality", "'valid-personality'", sqlValue);
        String imageStatus = valueFor(columnName, "image_status", "'PENDING'", sqlValue);

        jdbcTemplate.update("""
                INSERT INTO characters (
                    design_keyword,
                    personality,
                    image_status
                )
                VALUES (%s, %s, %s)
                """.formatted(
                designKeyword,
                personality,
                imageStatus
        ));
    }

    private void insertCharacterCatalogWithImageStatusAndSprite(String imageStatus, String spriteSheetUrl) {
        jdbcTemplate.update("""
                INSERT INTO characters (
                    design_keyword,
                    personality,
                    image_status,
                    sprite_sheet_url
                )
                VALUES (?, ?, ?, ?)
                """,
                "sprite-design",
                "sprite-personality",
                imageStatus,
                spriteSheetUrl
        );
    }

    private void insertUserCharacterWithOverrides(long userId, String columnName, String sqlValue) {
        String name = valueFor(columnName, "name", "'valid-name'", sqlValue);
        String statusMessage = valueFor(columnName, "status_message", "'Ready'", sqlValue);
        String statDb = valueFor(columnName, "stat_db", "0", sqlValue);
        String emotion = valueFor(columnName, "emotion", "'JOY'", sqlValue);

        jdbcTemplate.update("""
                INSERT INTO user_character (
                    user_id,
                    character_id,
                    name,
                    status_message,
                    stat_db,
                    emotion
                )
                VALUES (?, 1, %s, %s, %s, %s)
                """.formatted(
                name,
                statusMessage,
                statDb,
                emotion
        ), userId);
    }

    private String valueFor(String actualColumn, String targetColumn, String defaultValue, String overrideValue) {
        if (actualColumn.equals(targetColumn)) {
            return overrideValue;
        }
        return defaultValue;
    }
}
