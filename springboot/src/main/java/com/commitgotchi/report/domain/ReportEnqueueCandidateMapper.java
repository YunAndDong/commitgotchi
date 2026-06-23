package com.commitgotchi.report.domain;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ReportEnqueueCandidateMapper {

    @Results(id = "ReportEnqueueCandidateResult", value = {
            @Result(property = "userId", column = "user_id"),
            @Result(property = "stateJson", column = "state_json"),
            @Result(property = "reportJson", column = "report_json")
    })
    @Select("""
            SELECT gs.user_id,
                   gs.state_json,
                   report.report_json::text AS report_json
            FROM (
                SELECT gs.user_id,
                       gs.state_json,
                       gs.state_json::jsonb AS state_doc
                FROM game_states gs
                JOIN users u ON u.id = gs.user_id
                 AND u.deleted_at IS NULL
                WHERE state_json IS JSON
            ) gs
            CROSS JOIN LATERAL jsonb_array_elements(
                CASE
                    WHEN jsonb_typeof(gs.state_doc -> 'reports') = 'array'
                    THEN gs.state_doc -> 'reports'
                    ELSE '[]'::jsonb
                END
            ) AS report(report_json)
            WHERE report.report_json ->> 'date' = CAST(#{targetDate} AS text)
              AND btrim(COALESCE(report.report_json ->> 'title', '')) <> ''
              AND btrim(COALESCE(report.report_json ->> 'content', '')) <> ''
            ORDER BY gs.user_id, report.report_json ->> 'id'
            """)
    List<ReportEnqueueCandidate> findByTargetDate(LocalDate targetDate);
}
