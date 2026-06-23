package com.commitgotchi.character.domain;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LearningCharacterMapper {

    String CHARACTER_COLUMNS = """
            id,
            user_id,
            name,
            design_keyword,
            personality,
            stat_db,
            stat_algorithm,
            stat_cs,
            stat_network,
            stat_framework,
            battle_power,
            emotion,
            status_message,
            is_evolved,
            image_status,
            sprite_sheet_url,
            CAST(sprite_meta AS text) AS sprite_meta,
            is_active,
            version,
            created_at,
            updated_at
            """;

    @Results(id = "LearningCharacterResult", value = {
            @Result(property = "id", column = "id", id = true),
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
            @Result(property = "active", column = "is_active"),
            @Result(property = "version", column = "version"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            FROM characters
            WHERE id = #{id}
            """)
    LearningCharacter findById(Long id);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            FROM characters
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    LearningCharacter findByIdAndUserId(@Param("id") Long id, @Param("userId") long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            FROM characters
            WHERE id = #{id}
              AND user_id = #{userId}
            FOR UPDATE
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    LearningCharacter findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            FROM characters
            WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    List<LearningCharacter> findAllByUserIdOrderByCreatedAtDesc(long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            FROM characters
            WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            FOR UPDATE
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    List<LearningCharacter> findAllByUserIdForUpdateOrderByCreatedAtDesc(long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            FROM characters
            WHERE user_id = #{userId}
              AND is_active = true
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    LearningCharacter findActiveByUserId(long userId);

    @Select("""
            SELECT
            """ + CHARACTER_COLUMNS + """
            FROM characters
            WHERE user_id = #{userId}
              AND is_active = true
            FOR UPDATE
            """)
    @org.apache.ibatis.annotations.ResultMap("LearningCharacterResult")
    LearningCharacter findActiveByUserIdForUpdate(long userId);

    @Select("SELECT COUNT(*) FROM characters WHERE user_id = #{userId}")
    long countByUserId(long userId);

    @Insert("""
            INSERT INTO characters (
                user_id,
                name,
                design_keyword,
                personality,
                stat_db,
                stat_algorithm,
                stat_cs,
                stat_network,
                stat_framework,
                battle_power,
                emotion,
                status_message,
                is_evolved,
                image_status,
                sprite_sheet_url,
                sprite_meta,
                is_active,
                version,
                created_at,
                updated_at
            )
            VALUES (
                #{user.id},
                #{name},
                #{designKeyword},
                #{personality},
                #{statDb},
                #{statAlgorithm},
                #{statCs},
                #{statNetwork},
                #{statFramework},
                #{battlePower},
                #{emotion},
                #{statusMessage},
                #{evolved},
                #{imageStatus},
                #{spriteSheetUrl},
                CAST(#{spriteMeta} AS jsonb),
                #{active},
                #{version},
                #{createdAt},
                #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(LearningCharacter character);

    @Update("""
            UPDATE characters
            SET name = #{name},
                design_keyword = #{designKeyword},
                personality = #{personality},
                stat_db = #{statDb},
                stat_algorithm = #{statAlgorithm},
                stat_cs = #{statCs},
                stat_network = #{statNetwork},
                stat_framework = #{statFramework},
                battle_power = #{battlePower},
                emotion = #{emotion},
                status_message = #{statusMessage},
                is_evolved = #{evolved},
                image_status = #{imageStatus},
                sprite_sheet_url = #{spriteSheetUrl},
                sprite_meta = CAST(#{spriteMeta} AS jsonb),
                is_active = #{active},
                version = version + 1,
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int update(LearningCharacter character);

    @Delete("DELETE FROM characters WHERE id = #{id}")
    int deleteById(Long id);
}
