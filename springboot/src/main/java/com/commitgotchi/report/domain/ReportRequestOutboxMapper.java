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
            character_id,
            target_date,
            report_title,
            report_content,
            weekly_study_streak,
            focus,
            character_name,
            character_personality,
            character_emotion,
            character_stat_db,
            character_stat_algorithm,
            character_stat_cs,
            character_stat_network,
            character_stat_framework,
            score_delta_hint_db,
            score_delta_hint_algorithm,
            score_delta_hint_cs,
            score_delta_hint_network,
            score_delta_hint_framework,
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
              AND character_id = #{characterId}
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
                  FROM characters
                  WHERE characters.id = report_request_outbox.character_id
                    AND characters.deleted_at IS NULL
              )
            ORDER BY available_at ASC, id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    @org.apache.ibatis.annotations.ResultMap("ReportRequestOutboxResult")
    List<ReportRequestOutbox> claimAvailable(@Param("now") Instant now, @Param("limit") int limit);

    @Insert("""
            INSERT INTO report_request_outbox (
                request_id,
                user_id,
                character_id,
                target_date,
                report_title,
                report_content,
                weekly_study_streak,
                focus,
                character_name,
                character_personality,
                character_emotion,
                character_stat_db,
                character_stat_algorithm,
                character_stat_cs,
                character_stat_network,
                character_stat_framework,
                score_delta_hint_db,
                score_delta_hint_algorithm,
                score_delta_hint_cs,
                score_delta_hint_network,
                score_delta_hint_framework,
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
                #{focus},
                #{characterName},
                #{characterPersonality},
                #{characterEmotion},
                #{characterStatDb},
                #{characterStatAlgorithm},
                #{characterStatCs},
                #{characterStatNetwork},
                #{characterStatFramework},
                #{scoreDeltaHintDb},
                #{scoreDeltaHintAlgorithm},
                #{scoreDeltaHintCs},
                #{scoreDeltaHintNetwork},
                #{scoreDeltaHintFramework},
                #{status},
                #{attemptCount},
                #{availableAt}
            )
            ON CONFLICT (user_id, character_id, target_date) DO UPDATE
            SET report_title = EXCLUDED.report_title,
                report_content = EXCLUDED.report_content,
                weekly_study_streak = EXCLUDED.weekly_study_streak,
                focus = EXCLUDED.focus,
                character_name = EXCLUDED.character_name,
                character_personality = EXCLUDED.character_personality,
                character_emotion = EXCLUDED.character_emotion,
                character_stat_db = EXCLUDED.character_stat_db,
                character_stat_algorithm = EXCLUDED.character_stat_algorithm,
                character_stat_cs = EXCLUDED.character_stat_cs,
                character_stat_network = EXCLUDED.character_stat_network,
                character_stat_framework = EXCLUDED.character_stat_framework,
                score_delta_hint_db = EXCLUDED.score_delta_hint_db,
                score_delta_hint_algorithm = EXCLUDED.score_delta_hint_algorithm,
                score_delta_hint_cs = EXCLUDED.score_delta_hint_cs,
                score_delta_hint_network = EXCLUDED.score_delta_hint_network,
                score_delta_hint_framework = EXCLUDED.score_delta_hint_framework,
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
