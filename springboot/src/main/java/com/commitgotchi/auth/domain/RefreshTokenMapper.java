package com.commitgotchi.auth.domain;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface RefreshTokenMapper {

    @Results(id = "RefreshTokenResult", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "user", column = "user_id",
                    one = @org.apache.ibatis.annotations.One(
                            select = "com.commitgotchi.user.domain.UserMapper.findById"
                    )),
            @Result(property = "tokenHash", column = "token_hash"),
            @Result(property = "expiresAt", column = "expires_at"),
            @Result(property = "revokedAt", column = "revoked_at"),
            @Result(property = "createdAt", column = "created_at")
    })
    @Select("""
            SELECT id, user_id, token_hash, expires_at, revoked_at, created_at
            FROM refresh_tokens
            WHERE id = #{id}
            """)
    RefreshToken findById(UUID id);

    @Select("""
            SELECT id, user_id, token_hash, expires_at, revoked_at, created_at
            FROM refresh_tokens
            WHERE token_hash = #{tokenHash}
            FOR UPDATE
            """)
    @org.apache.ibatis.annotations.ResultMap("RefreshTokenResult")
    RefreshToken findByTokenHashForUpdate(String tokenHash);

    @Select("SELECT EXISTS (SELECT 1 FROM refresh_tokens WHERE id = #{id})")
    boolean existsById(UUID id);

    @Select("""
            SELECT COUNT(*)
            FROM refresh_tokens
            WHERE user_id = #{userId}
              AND revoked_at IS NULL
            """)
    long countByUserIdAndRevokedAtIsNull(long userId);

    @Insert("""
            INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked_at, created_at)
            VALUES (#{id}, #{user.id}, #{tokenHash}, #{expiresAt}, #{revokedAt}, #{createdAt})
            """)
    int insert(RefreshToken token);

    @Update("""
            UPDATE refresh_tokens
            SET token_hash = #{tokenHash},
                expires_at = #{expiresAt},
                revoked_at = #{revokedAt}
            WHERE id = #{id}
            """)
    int update(RefreshToken token);

    @Update("""
            UPDATE refresh_tokens
            SET revoked_at = #{revokedAt}
            WHERE user_id = #{userId}
              AND revoked_at IS NULL
            """)
    int revokeAllActiveByUserId(@Param("userId") long userId, @Param("revokedAt") Instant revokedAt);

    @Delete("""
            DELETE FROM refresh_tokens
            WHERE token_hash = #{tokenHash}
              AND revoked_at IS NULL
            """)
    int deleteActiveByTokenHash(String tokenHash);

    @Delete("DELETE FROM refresh_tokens")
    int deleteAll();
}
