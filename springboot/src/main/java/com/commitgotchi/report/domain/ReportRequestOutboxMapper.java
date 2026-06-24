package com.commitgotchi.report.domain;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ReportRequestOutboxMapper {

    String OUTBOX_COLUMNS = """
            id,
            request_id,
            user_id,
            user_character_id AS character_id,
            target_date,
            report_title,
            report_content,
            weekly_study_streak,
            focus,
            COALESCE((
                SELECT user_character.name
                FROM user_character
                WHERE user_character.id = report_request_outbox.user_character_id
            ), '') AS character_name,
            COALESCE((
                SELECT characters.personality
                FROM user_character
                JOIN characters ON characters.id = user_character.character_id
                WHERE user_character.id = report_request_outbox.user_character_id
            ), 'Unknown') AS character_personality,
            COALESCE((
                SELECT user_character.emotion
                FROM user_character
                WHERE user_character.id = report_request_outbox.user_character_id
            ), 'JOY') AS character_emotion,
            COALESCE((
                SELECT user_character.stat_db
                FROM user_character
                WHERE user_character.id = report_request_outbox.user_character_id
            ), 0) AS character_stat_db,
            COALESCE((
                SELECT user_character.stat_algorithm
                FROM user_character
                WHERE user_character.id = report_request_outbox.user_character_id
            ), 0) AS character_stat_algorithm,
            COALESCE((
                SELECT user_character.stat_cs
                FROM user_character
                WHERE user_character.id = report_request_outbox.user_character_id
            ), 0) AS character_stat_cs,
            COALESCE((
                SELECT user_character.stat_network
                FROM user_character
                WHERE user_character.id = report_request_outbox.user_character_id
            ), 0) AS character_stat_network,
            COALESCE((
                SELECT user_character.stat_framework
                FROM user_character
                WHERE user_character.id = report_request_outbox.user_character_id
            ), 0) AS character_stat_framework,
            COALESCE((score_delta_hint ->> 'db')::integer, 0) AS score_delta_hint_db,
            COALESCE((score_delta_hint ->> 'algorithm')::integer, 0) AS score_delta_hint_algorithm,
            COALESCE((score_delta_hint ->> 'cs')::integer, 0) AS score_delta_hint_cs,
            COALESCE((score_delta_hint ->> 'network')::integer, 0) AS score_delta_hint_network,
            COALESCE((score_delta_hint ->> 'framework')::integer, 0) AS score_delta_hint_framework,
            status,
            attempt_count,
            available_at,
            sent_at,
            last_error,
            created_at,
            updated_at
            """;

    @Results(id = "ReportRequestOutboxResult", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "requestId", column = "request_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "characterId", column = "character_id"),
            @Result(property = "targetDate", column = "target_date"),
            @Result(property = "reportTitle", column = "report_title"),
            @Result(property = "reportContent", column = "report_content"),
            @Result(property = "weeklyStudyStreak", column = "weekly_study_streak"),
            @Result(property = "focus", column = "focus"),
            @Result(property = "characterName", column = "character_name"),
            @Result(property = "characterPersonality", column = "character_personality"),
            @Result(property = "characterEmotion", column = "character_emotion"),
            @Result(property = "characterStatDb", column = "character_stat_db"),
            @Result(property = "characterStatAlgorithm", column = "character_stat_algorithm"),
            @Result(property = "characterStatCs", column = "character_stat_cs"),
            @Result(property = "characterStatNetwork", column = "character_stat_network"),
            @Result(property = "characterStatFramework", column = "character_stat_framework"),
            @Result(property = "scoreDeltaHintDb", column = "score_delta_hint_db"),
            @Result(property = "scoreDeltaHintAlgorithm", column = "score_delta_hint_algorithm"),
            @Result(property = "scoreDeltaHintCs", column = "score_delta_hint_cs"),
            @Result(property = "scoreDeltaHintNetwork", column = "score_delta_hint_network"),
            @Result(property = "scoreDeltaHintFramework", column = "score_delta_hint_framework"),
            @Result(property = "status", column = "status"),
            @Result(property = "attemptCount", column = "attempt_count"),
            @Result(property = "availableAt", column = "available_at"),
            @Result(property = "sentAt", column = "sent_at"),
            @Result(property = "lastError", column = "last_error"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    @Select("""
            SELECT
            """ + OUTBOX_COLUMNS + """
            FROM report_request_outbox
            WHERE id = #{id}
            """)
    ReportRequestOutbox findById(Long id);

    @Select("""
            SELECT
            """ + OUTBOX_COLUMNS + """
            FROM report_request_outbox
            WHERE user_id = #{userId}
              AND user_character_id = #{characterId}
              AND target_date = #{targetDate}
            """)
    @org.apache.ibatis.annotations.ResultMap("ReportRequestOutboxResult")
    ReportRequestOutbox findByUserCharacterAndTargetDate(
            @Param("userId") long userId,
            @Param("characterId") long characterId,
            @Param("targetDate") LocalDate targetDate
    );

    @Select("""
            SELECT
            """ + OUTBOX_COLUMNS + """
            FROM report_request_outbox
            WHERE request_id = #{requestId}
            """)
    @org.apache.ibatis.annotations.ResultMap("ReportRequestOutboxResult")
    ReportRequestOutbox findByRequestId(String requestId);

    @Select("""
            SELECT
            """ + OUTBOX_COLUMNS + """
            FROM report_request_outbox
            WHERE status = 'PENDING'
              AND available_at <= #{now}
              AND EXISTS (
                  SELECT 1
                  FROM users
                  WHERE users.id = report_request_outbox.user_id
                    AND users.deleted_at IS NULL
              )
              AND EXISTS (
                  SELECT 1
                  FROM user_character
                  WHERE user_character.id = report_request_outbox.user_character_id
                    AND user_character.deleted_at IS NULL
              )
            ORDER BY available_at ASC, id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    @org.apache.ibatis.annotations.ResultMap("ReportRequestOutboxResult")
    List<ReportRequestOutbox> claimAvailable(@Param("now") Instant now, @Param("limit") int limit);

    @Select("""
            SELECT
            """ + OUTBOX_COLUMNS + """
            FROM report_request_outbox
            WHERE request_id = #{requestId}
              AND status = 'PENDING'
              AND EXISTS (
                  SELECT 1
                  FROM users
                  WHERE users.id = report_request_outbox.user_id
                    AND users.deleted_at IS NULL
              )
              AND EXISTS (
                  SELECT 1
                  FROM user_character
                  WHERE user_character.id = report_request_outbox.user_character_id
                    AND user_character.deleted_at IS NULL
              )
            FOR UPDATE SKIP LOCKED
            """)
    @org.apache.ibatis.annotations.ResultMap("ReportRequestOutboxResult")
    ReportRequestOutbox claimPendingRequest(String requestId);

    @Insert("""
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
                attempt_count,
                available_at
            )
            VALUES (
                #{requestId},
                #{userId},
                #{characterId},
                #{targetDate},
                #{reportTitle},
                #{reportContent},
                #{weeklyStudyStreak},
                jsonb_build_object(
                    'db', #{scoreDeltaHintDb},
                    'algorithm', #{scoreDeltaHintAlgorithm},
                    'cs', #{scoreDeltaHintCs},
                    'network', #{scoreDeltaHintNetwork},
                    'framework', #{scoreDeltaHintFramework}
                ),
                #{focus},
                #{status},
                #{attemptCount},
                #{availableAt}
            )
            ON CONFLICT (user_id, user_character_id, target_date) DO UPDATE
            SET report_title = EXCLUDED.report_title,
                report_content = EXCLUDED.report_content,
                weekly_study_streak = EXCLUDED.weekly_study_streak,
                score_delta_hint = EXCLUDED.score_delta_hint,
                focus = EXCLUDED.focus,
                available_at = EXCLUDED.available_at,
                updated_at = CURRENT_TIMESTAMP
            WHERE report_request_outbox.status = 'PENDING'
            """)
    int upsertPendingSnapshot(ReportRequestOutbox outbox);

    @Update("""
            UPDATE report_request_outbox
            SET status = 'SENT',
                sent_at = #{sentAt},
                last_error = NULL,
                updated_at = #{sentAt}
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markSent(@Param("id") long id, @Param("sentAt") Instant sentAt);

    @Update("""
            UPDATE report_request_outbox
            SET status = 'PENDING',
                attempt_count = #{attemptCount},
                available_at = #{availableAt},
                sent_at = NULL,
                last_error = #{lastError},
                updated_at = #{updatedAt}
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markRetryableFailure(
            @Param("id") long id,
            @Param("attemptCount") int attemptCount,
            @Param("availableAt") Instant availableAt,
            @Param("lastError") String lastError,
            @Param("updatedAt") Instant updatedAt
    );

    @Update("""
            UPDATE report_request_outbox
            SET status = 'FAILED',
                attempt_count = #{attemptCount},
                available_at = #{updatedAt},
                sent_at = NULL,
                last_error = #{lastError},
                updated_at = #{updatedAt}
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markFailed(
            @Param("id") long id,
            @Param("attemptCount") int attemptCount,
            @Param("lastError") String lastError,
            @Param("updatedAt") Instant updatedAt
    );
}
