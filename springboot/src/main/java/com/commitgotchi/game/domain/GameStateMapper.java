package com.commitgotchi.game.domain;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GameStateMapper {

    @Results(id = "GameStateResult", value = {
            @Result(property = "userId", column = "user_id", id = true),
            @Result(property = "stateJson", column = "state_json"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    @Select("""
            SELECT user_id, state_json, created_at, updated_at
            FROM game_states
            WHERE user_id = #{userId}
            """)
    GameState findById(Long userId);

    @Select("""
            SELECT user_id, state_json, created_at, updated_at
            FROM game_states
            WHERE user_id = #{userId}
            FOR UPDATE
            """)
    @org.apache.ibatis.annotations.ResultMap("GameStateResult")
    GameState findByIdForUpdate(Long userId);

    @Insert("""
            INSERT INTO game_states (user_id, state_json, created_at, updated_at)
            VALUES (#{userId}, #{stateJson}, #{createdAt}, #{updatedAt})
            ON CONFLICT (user_id) DO UPDATE
            SET state_json = EXCLUDED.state_json,
                updated_at = EXCLUDED.updated_at
            """)
    int upsert(GameState state);
}
