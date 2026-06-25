package com.commitgotchi.character.application;

import com.commitgotchi.character.image.CharacterImageProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DefaultCharacterCatalogInitializer implements ApplicationRunner, Ordered {

    private static final String SPRITE_META =
            "{\"columns\":3,\"rows\":1,\"frameMap\":{\"joy\":[0,0],\"sad\":[0,1],\"angry\":[0,2]},\"transparent\":true}";

    private final JdbcTemplate jdbcTemplate;
    private final CharacterImageProperties imageProperties;
    private final TransactionTemplate transactionTemplate;

    public DefaultCharacterCatalogInitializer(
            JdbcTemplate jdbcTemplate,
            CharacterImageProperties imageProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.imageProperties = imageProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactionTemplate.executeWithoutResult(status -> initialize());
    }

    private void initialize() {
        moveUserCatalogRowsOutOfReservedIds();
        upsertDefaultCatalogRows();
        resetCharacterSequence();
    }

    private void moveUserCatalogRowsOutOfReservedIds() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE character_catalog_reseed_map (
                    old_id BIGINT PRIMARY KEY,
                    new_id BIGINT NOT NULL UNIQUE
                ) ON COMMIT DROP
                """);
        jdbcTemplate.update("""
                INSERT INTO character_catalog_reseed_map (old_id, new_id)
                SELECT reserved.id,
                       (SELECT GREATEST(COALESCE(MAX(id), 0), 3) FROM characters)
                           + ROW_NUMBER() OVER (ORDER BY reserved.id)
                FROM characters reserved
                WHERE reserved.id IN (1, 2, 3)
                  AND (reserved.personality IS NOT NULL OR reserved.design_keyword IS NOT NULL)
                """);
        jdbcTemplate.update("""
                INSERT INTO characters (
                    id,
                    personality,
                    design_keyword,
                    sprite_sheet_url,
                    sprite_meta,
                    image_status,
                    created_at
                )
                SELECT reseed.new_id,
                       original.personality,
                       original.design_keyword,
                       original.sprite_sheet_url,
                       original.sprite_meta,
                       original.image_status,
                       original.created_at
                FROM characters original
                JOIN character_catalog_reseed_map reseed ON reseed.old_id = original.id
                """);
        jdbcTemplate.update("""
                UPDATE user_character
                SET character_id = reseed.new_id
                FROM character_catalog_reseed_map reseed
                WHERE user_character.character_id = reseed.old_id
                """);
        jdbcTemplate.update("""
                DELETE FROM characters
                USING character_catalog_reseed_map reseed
                WHERE characters.id = reseed.old_id
                """);
    }

    private void upsertDefaultCatalogRows() {
        jdbcTemplate.update("""
                INSERT INTO characters (
                    id,
                    personality,
                    design_keyword,
                    sprite_sheet_url,
                    sprite_meta,
                    image_status
                )
                VALUES
                    (1, NULL, NULL, ?, CAST(? AS jsonb), 'READY'),
                    (2, NULL, NULL, ?, CAST(? AS jsonb), 'READY'),
                    (3, NULL, NULL, ?, CAST(? AS jsonb), 'READY')
                ON CONFLICT (id) DO UPDATE
                SET personality = EXCLUDED.personality,
                    design_keyword = EXCLUDED.design_keyword,
                    sprite_sheet_url = EXCLUDED.sprite_sheet_url,
                    sprite_meta = EXCLUDED.sprite_meta,
                    image_status = EXCLUDED.image_status
                """,
                defaultSpriteUrl(1), SPRITE_META,
                defaultSpriteUrl(2), SPRITE_META,
                defaultSpriteUrl(3), SPRITE_META
        );
    }

    private void resetCharacterSequence() {
        jdbcTemplate.execute("""
                SELECT setval(
                    pg_get_serial_sequence('characters', 'id'),
                    GREATEST((SELECT COALESCE(MAX(id), 0) FROM characters), 3),
                    true
                )
                """);
    }

    private String defaultSpriteUrl(int presetId) {
        return "%s/characters/%d/sprite-sheet.png".formatted(
                imageProperties.normalizedS3ObjectPrefix(),
                presetId
        );
    }
}
