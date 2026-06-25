package com.commitgotchi.character.domain;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface LearningCharacterMapper {

    String CHARACTER_COLUMNS = """
            uc.id,
            uc.user_id,
            uc.character_id AS catalog_character_id,
            uc.name,
            catalog.design_keyword,
            catalog.personality,
            uc.stat_db,
            uc.stat_algorithm,
            uc.stat_cs,
            uc.stat_network,
            uc.stat_framework,
            uc.battle_power,
            uc.emotion,
            uc.status_message,
            uc.is_evolved,
            CASE
                WHEN uc.is_evolved THEN catalog.image_status
                ELSE baby.image_status
            END AS image_status,
            CASE
                WHEN uc.is_evolved THEN catalog.sprite_sheet_url
                ELSE baby.sprite_sheet_url
            END AS sprite_sheet_url,
            CAST(
                CASE
                    WHEN uc.is_evolved THEN catalog.sprite_meta
                    ELSE baby.sprite_meta
                END AS text
            ) AS sprite_meta,
            baby.image_status AS baby_image_status,
            baby.sprite_sheet_url AS baby_sprite_sheet_url,
            CAST(baby.sprite_meta AS text) AS baby_sprite_meta,
            catalog.image_status AS evolved_image_status,
            catalog.sprite_sheet_url AS evolved_sprite_sheet_url,
            CAST(catalog.sprite_meta AS text) AS evolved_sprite_meta,
            uc.is_active,
            0 AS version,
            uc.created_at,
            uc.created_at AS updated_at,
            uc.deleted_at
            """;

    String CHARACTER_FROM = """
            FROM user_character uc
            JOIN characters catalog ON catalog.id = uc.character_id
            LEFT JOIN characters baby ON baby.id = ((uc.id % 3) + 1)
            """;

    String CODEX_CHARACTER_COLUMNS = """
            id,
            personality,
            design_keyword,
            image_status,
            sprite_sheet_url,
            CAST(sprite_meta AS text) AS sprite_meta
            """;

    @Results(id = "LearningCharacterResult", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "catalogCharacterId", column = "catalog_character_id"),
            @Result(property = "user", column = "user_id",
                    one = @org.apache.ibatis.annotations.One(
                            select = "com.commitgotchi.user.domain.UserMapper.findById"
                    )),
            @Result(property = "name", column = "name"),
            @Result(property = "designKeyword", column = "design_keyword"),
            @Result(property = "personality", column = "personality"),
            @Result(property = "statDb", column = "stat_db"),
            @Result(property = "statAlgorithm", column = "stat_algorithm"),
            @Result(property = "statCs", column = "stat_cs"),
            @Result(property = "statNetwork", column = "stat_network"),
            @Result(property = "statFramework", column = "stat_framework"),
            @Result(property = "battlePower", column = "battle_power"),
            @Result(property = "emotion", column = "emotion"),
            @Result(property = "statusMessage", column = "status_message"),
            @Result(property = "evolved", column = "is_evolved"),
            @Result(property = "imageStatus", column = "image_status"),
            @Result(property = "spriteSheetUrl", column = "sprite_sheet_url"),
            @Result(property = "spriteMeta", column = "sprite_meta"),
            @Result(property = "babyImageStatus", column = "baby_image_status"),
            @Result(property = "babySpriteSheetUrl", column = "baby_sprite_sheet_url"),
            @Result(property = "babySpriteMeta", column = "baby_sprite_meta"),
            @Result(property = "evolvedImageStatus", column = "evolved_image_status"),
            @Result(property = "evolvedSpriteSheetUrl", column = "evolved_sprite_sheet_url"),
            @Result(property = "evolvedSpriteMeta", column = "evolved_sprite_meta"),
            @Result(property = "active", column = "is_active"),
            @Result(property = "version", column = "version"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at"),
            @Result(property = "deletedAt", column = "deleted_at")
    })
    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            """ + CHARACTER_FROM + """
            WHERE uc.id = #{id}
              AND uc.deleted_at IS NULL
            """)
    LearningCharacter findById(Long id);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            """ + CHARACTER_FROM + """
            WHERE uc.id = #{id}
              AND uc.user_id = #{userId}
              AND uc.deleted_at IS NULL
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    LearningCharacter findByIdAndUserId(@Param("id") Long id, @Param("userId") long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            """ + CHARACTER_FROM + """
            WHERE uc.id = #{id}
              AND uc.user_id = #{userId}
              AND uc.deleted_at IS NULL
            FOR UPDATE OF uc
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    LearningCharacter findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            """ + CHARACTER_FROM + """
            WHERE uc.user_id = #{userId}
              AND uc.deleted_at IS NULL
            ORDER BY uc.created_at DESC, uc.id DESC
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    List<LearningCharacter> findAllByUserIdOrderByCreatedAtDesc(long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            """ + CHARACTER_FROM + """
            WHERE uc.user_id = #{userId}
              AND uc.deleted_at IS NULL
            ORDER BY uc.created_at DESC, uc.id DESC
            FOR UPDATE OF uc
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    List<LearningCharacter> findAllByUserIdForUpdateOrderByCreatedAtDesc(long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            """ + CHARACTER_FROM + """
            WHERE uc.user_id = #{userId}
              AND uc.is_active = true
              AND uc.deleted_at IS NULL
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    LearningCharacter findActiveByUserId(long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            """ + CHARACTER_FROM + """
            WHERE uc.user_id = #{userId}
              AND uc.is_active = true
              AND uc.deleted_at IS NULL
            FOR UPDATE OF uc
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    LearningCharacter findActiveByUserIdForUpdate(long userId);

    @Results(id = "CodexCharacterProjectionResult", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "personality", column = "personality"),
            @Result(property = "designKeyword", column = "design_keyword"),
            @Result(property = "imageStatus", column = "image_status"),
            @Result(property = "spriteSheetUrl", column = "sprite_sheet_url"),
            @Result(property = "spriteMeta", column = "sprite_meta")
    })
    @Select("""
            SELECT
            """ + CODEX_CHARACTER_COLUMNS + """
            FROM characters
            WHERE id >= 4
              AND image_status IN ('READY', 'FALLBACK')
              AND (#{afterId,jdbcType=BIGINT} IS NULL OR id > #{afterId,jdbcType=BIGINT})
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<CodexCharacterProjection> findCodexCharactersAfterId(
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT
            """ + CODEX_CHARACTER_COLUMNS + """
            FROM characters
            WHERE id >= 4
              AND image_status IN ('READY', 'FALLBACK')
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            ORDER BY id ASC
            </script>
            """)
    @org.apache.ibatis.annotations.ResultMap("CodexCharacterProjectionResult")
    List<CodexCharacterProjection> findCodexCharactersByIds(@Param("ids") List<Long> ids);

    @Select("""
            SELECT
            """ + CODEX_CHARACTER_COLUMNS + """
            FROM characters
            WHERE id = #{id}
              AND id >= 4
              AND image_status IN ('READY', 'FALLBACK')
            """)
    @org.apache.ibatis.annotations.ResultMap("CodexCharacterProjectionResult")
    CodexCharacterProjection findCodexCharacterById(long id);

    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM user_character
                WHERE user_id = #{userId}
                  AND character_id = #{characterId}
            )
            """)
    boolean existsUserCharacterByUserIdAndCatalogCharacterId(
            @Param("userId") long userId,
            @Param("characterId") long characterId
    );

    @Select("""
            SELECT id
            FROM user_character
            WHERE user_id = #{userId}
              AND character_id = #{characterId}
              AND deleted_at IS NULL
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    Long findUserCharacterIdByUserIdAndCatalogCharacterId(
            @Param("userId") long userId,
            @Param("characterId") long characterId
    );

    @Select("""
            INSERT INTO user_character (
                user_id,
                character_id,
                name,
                is_active,
                created_at
            )
            VALUES (
                #{userId},
                #{characterId},
                #{name},
                #{active},
                #{createdAt}
            )
            RETURNING id
            """)
    Long insertUserCharacterForCatalog(
            @Param("userId") long userId,
            @Param("characterId") long characterId,
            @Param("name") String name,
            @Param("active") boolean active,
            @Param("createdAt") Instant createdAt
    );

    @Select("SELECT COUNT(*) FROM user_character WHERE user_id = #{userId} AND deleted_at IS NULL")
    long countByUserId(long userId);

    @Update("""
            UPDATE user_character
            SET is_active = false,
                deleted_at = #{deletedAt}
            WHERE user_id = #{userId}
              AND deleted_at IS NULL
            """)
    int softDeleteAllByUserId(@Param("userId") long userId, @Param("deletedAt") Instant deletedAt);

    @Insert("""
            INSERT INTO characters (
                personality,
                design_keyword,
                image_status,
                sprite_sheet_url,
                sprite_meta,
                created_at
            )
            VALUES (
                #{personality},
                #{designKeyword},
                #{evolvedImageStatus},
                #{evolvedSpriteSheetUrl},
                CAST(#{evolvedSpriteMeta} AS jsonb),
                #{createdAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "catalogCharacterId", keyColumn = "id")
    int insertCatalog(LearningCharacter character);

    @Insert("""
            INSERT INTO user_character (
                user_id,
                character_id,
                name,
                stat_db,
                stat_algorithm,
                stat_cs,
                stat_network,
                stat_framework,
                battle_power,
                emotion,
                status_message,
                is_evolved,
                is_active,
                created_at
            )
            VALUES (
                #{user.id},
                #{catalogCharacterId},
                #{name},
                #{statDb},
                #{statAlgorithm},
                #{statCs},
                #{statNetwork},
                #{statFramework},
                #{battlePower},
                #{emotion},
                #{statusMessage},
                #{evolved},
                #{active},
                #{createdAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertUserCharacter(LearningCharacter character);

    @Update("""
            UPDATE characters
            SET design_keyword = #{designKeyword},
                personality = #{personality},
                image_status = #{evolvedImageStatus},
                sprite_sheet_url = #{evolvedSpriteSheetUrl},
                sprite_meta = CAST(#{evolvedSpriteMeta} AS jsonb)
            WHERE id = #{catalogCharacterId}
            """)
    int updateCatalog(LearningCharacter character);

    @Update("""
            UPDATE user_character
            SET name = #{name},
                stat_db = #{statDb},
                stat_algorithm = #{statAlgorithm},
                stat_cs = #{statCs},
                stat_network = #{statNetwork},
                stat_framework = #{statFramework},
                battle_power = #{battlePower},
                emotion = #{emotion},
                status_message = #{statusMessage},
                is_evolved = #{evolved},
                is_active = #{active},
                deleted_at = #{deletedAt}
            WHERE id = #{id}
              AND deleted_at IS NULL
            """)
    int updateUserCharacter(LearningCharacter character);
}
