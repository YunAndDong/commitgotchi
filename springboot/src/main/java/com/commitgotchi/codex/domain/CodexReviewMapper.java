package com.commitgotchi.codex.domain;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface CodexReviewMapper {

    @Results(id = "CodexReviewResult", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "characterId", column = "character_id"),
            @Result(property = "stars", column = "stars"),
            @Result(property = "text", column = "text"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    @Select("""
            SELECT id, user_id, character_id, stars, text, created_at, updated_at
            FROM codex_character_reviews
            WHERE character_id = #{characterId}
              AND user_id <> #{userId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            OFFSET #{offset}
            """)
    List<CodexReview> findPageByCharacterIdExcludingUser(
            @Param("characterId") long characterId,
            @Param("userId") long userId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT id, user_id, character_id, stars, text, created_at, updated_at
            FROM codex_character_reviews
            WHERE character_id = #{characterId}
              AND user_id = #{userId}
            """)
    @org.apache.ibatis.annotations.ResultMap("CodexReviewResult")
    CodexReview findByCharacterIdAndUserId(
            @Param("characterId") long characterId,
            @Param("userId") long userId
    );

    @Select("""
            SELECT count(*)
            FROM codex_character_reviews
            WHERE character_id = #{characterId}
            """)
    long countByCharacterId(long characterId);

    @Select("""
            SELECT avg(stars)
            FROM codex_character_reviews
            WHERE character_id = #{characterId}
            """)
    Double averageStarsByCharacterId(long characterId);

    @Insert("""
            INSERT INTO codex_character_reviews (
                user_id,
                character_id,
                stars,
                text,
                created_at,
                updated_at
            )
            VALUES (
                #{userId},
                #{characterId},
                #{stars},
                #{text},
                #{createdAt},
                #{createdAt}
            )
            """)
    int insert(
            @Param("userId") long userId,
            @Param("characterId") long characterId,
            @Param("stars") int stars,
            @Param("text") String text,
            @Param("createdAt") Instant createdAt
    );

    @Update("""
            UPDATE codex_character_reviews
            SET stars = #{stars},
                text = #{text},
                updated_at = #{updatedAt}
            WHERE id = #{reviewId}
              AND character_id = #{characterId}
              AND user_id = #{userId}
            """)
    int updateByIdAndCharacterIdAndUserId(
            @Param("reviewId") long reviewId,
            @Param("characterId") long characterId,
            @Param("userId") long userId,
            @Param("stars") int stars,
            @Param("text") String text,
            @Param("updatedAt") Instant updatedAt
    );

    @Delete("""
            DELETE FROM codex_character_reviews
            WHERE id = #{reviewId}
              AND character_id = #{characterId}
              AND user_id = #{userId}
            """)
    int deleteByIdAndCharacterIdAndUserId(
            @Param("reviewId") long reviewId,
            @Param("characterId") long characterId,
            @Param("userId") long userId
    );
}
