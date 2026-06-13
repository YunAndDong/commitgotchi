from __future__ import annotations

import json
import unittest
from typing import Any, Mapping
from unittest.mock import patch

from app.rag.schemas import SCORE_FIELDS
from app.scoring.schemas import ReportChunk


class FakeReportClient:
    def __init__(
        self,
        response: Mapping[str, Any] | str | None = None,
        *,
        error: Exception | None = None,
    ) -> None:
        self.response = response
        self.error = error
        self.prompts: list[str] = []

    def analyze(self, prompt: str) -> Mapping[str, Any] | str:
        self.prompts.append(prompt)
        if self.error is not None:
            raise self.error
        return self.response or _success_payload()


class ReportAnalyzerTest(unittest.TestCase):
    def test_fake_client_mapping_response_builds_success_analysis(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        client = FakeReportClient(_success_payload())

        result = analyze_daily_report(
            report_title="오늘의 학습",
            report_content="JPA N+1 원인과 fetch join 해결책을 정리했다.",
            character_personality="따뜻하지만 틀린 부분은 분명하게 짚는 성격",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=client,
        )

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["scoreDelta"]["db"], 8)
        self.assertEqual(tuple(result["scoreDelta"]), SCORE_FIELDS)
        self.assertEqual(tuple(result["fieldEvidence"]), SCORE_FIELDS)
        self.assertIn("JPA N+1", result["topics"])
        self.assertIn("fetch join", result["dailyReport"]["feedback"])
        self.assertIn("EntityGraph", result["nextRecommendation"]["topics"])
        self.assertNotIn("recommendedQuizzes", result)
        self.assertNotIn("gradings", result)
        self.assertNotIn("requestId", result)
        self.assertEqual(len(client.prompts), 1)
        prompt = client.prompts[0]
        self.assertIn("오늘의 학습", prompt)
        self.assertIn("report:0", prompt)
        self.assertIn("concept:jpa-n-plus-one", prompt)
        self.assertIn("따뜻하지만 틀린 부분은 분명하게 짚는 성격", prompt)

    def test_json_string_response_is_parsed(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        client = FakeReportClient(json.dumps(_success_payload(), ensure_ascii=False))

        result = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=client,
        )

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["scoreDelta"]["framework"], 7)

    def test_markdown_code_fence_response_is_parsed(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        payload = json.dumps(_success_payload(), ensure_ascii=False)
        client = FakeReportClient(f"```json\n{payload}\n```")

        result = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=client,
        )

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["confidence"], 0.84)

    def test_score_delta_is_clamped_and_unknown_fields_are_removed(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        payload = _success_payload(
            score_delta={
                "db": 15,
                "algorithm": -2,
                "network": 5.9,
                "framework": "8",
                "unknown": 10,
            },
            field_evidence={"db": "근거", "unknown": "제거"},
        )
        client = FakeReportClient(payload)

        result = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=client,
        )

        self.assertEqual(
            result["scoreDelta"],
            {
                "db": 10,
                "algorithm": 0,
                "cs": 0,
                "network": 5,
                "framework": 8,
            },
        )
        self.assertEqual(tuple(result["fieldEvidence"]), SCORE_FIELDS)
        self.assertNotIn("unknown", result["scoreDelta"])
        self.assertNotIn("unknown", result["fieldEvidence"])

    def test_invalid_json_returns_fallback(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        result = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=FakeReportClient("not json"),
        )

        self.assertEqual(result["status"], "FALLBACK")
        self.assertEqual(result["scoreDelta"], {field: 0 for field in SCORE_FIELDS})

    def test_non_object_json_returns_fallback(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        result = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=FakeReportClient(json.dumps([_success_payload()], ensure_ascii=False)),
        )

        self.assertEqual(result["status"], "FALLBACK")
        self.assertEqual(result["scoreDelta"], {field: 0 for field in SCORE_FIELDS})

    def test_json_object_embedded_in_text_returns_fallback(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        payload = json.dumps(_success_payload(), ensure_ascii=False)
        result = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=FakeReportClient(f"분석 결과입니다.\n{payload}"),
        )

        self.assertEqual(result["status"], "FALLBACK")
        self.assertEqual(result["scoreDelta"], {field: 0 for field in SCORE_FIELDS})

    def test_client_exception_returns_fallback(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        result = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=FakeReportClient(error=RuntimeError("boom")),
        )

        self.assertEqual(result["status"], "FALLBACK")
        self.assertEqual(result["confidence"], 0.0)

    def test_low_confidence_applies_conservative_score_policy(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        client = FakeReportClient(
            _success_payload(
                confidence=0.4,
                score_delta={field: 10 for field in SCORE_FIELDS},
            )
        )

        result = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1 원인을 구체적으로 정리했다.",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=client,
        )

        self.assertEqual(result["status"], "SUCCESS")
        self.assertTrue(all(value <= 3 for value in result["scoreDelta"].values()))

        very_low = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1 원인을 구체적으로 정리했다.",
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=FakeReportClient(_success_payload(confidence=0.2, score_delta={"db": 10})),
        )
        self.assertEqual(very_low["status"], "FALLBACK")
        self.assertEqual(very_low["scoreDelta"], {field: 0 for field in SCORE_FIELDS})

    def test_character_metadata_personality_fallback_is_prompted_and_preserved(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        marker = "엄격하지만 응원하는 말투"
        client = FakeReportClient(
            _success_payload(
                status_message=f"{marker}: 핵심은 좋지만 빠진 한계를 짚어보자.",
                feedback=f"{marker}: fetch join의 장점과 한계를 함께 봤다.",
                rationale=f"{marker}: 다음에는 페이징 한계를 확인하자.",
            )
        )

        result = analyze_daily_report(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            character_metadata={"personality": marker},
            report_chunks=[_report_chunk()],
            evidence_bundles=[_evidence_bundle()],
            client=client,
        )

        self.assertIn(marker, client.prompts[0])
        self.assertIn(marker, result["statusMessage"])
        self.assertIn(marker, result["dailyReport"]["feedback"])
        self.assertIn(marker, result["nextRecommendation"]["rationale"])

    def test_user_metadata_and_current_stats_are_in_prompt_context(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        client = FakeReportClient(
            _success_payload(
                status_message="연속 작성 흐름이 좋아요.",
                feedback="최근 network 정체를 JWT 학습으로 다시 잡고 있어요.",
                rationale="currentStats를 보면 network를 이어가면 좋아요.",
            )
        )
        user_metadata = {
            "weeklyStudyStreak": "0111111",
            "reportDirection": {
                "scoreDeltaHint": {"network": 10, "db": 3},
                "focus": "network 흐름 회복",
            },
            "recentStudyTopics": ["OAuth", "JWT"],
            "recentReports": ["HTTP 상태 코드를 복습했다."],
            "recentFieldChanges": {"network": "stagnant", "db": "growing"},
        }

        result = analyze_daily_report(
            report_title="인증 학습",
            report_content="JWT 토큰 검증 흐름을 정리했다.",
            user_metadata=user_metadata,
            character_metadata={
                "personality": "칭찬을 많이 하는 성격",
                "currentStats": {"db": 120, "network": 60, "framework": 140},
            },
            report_chunks=[_report_chunk(text="JWT 토큰 검증 흐름을 정리했다.")],
            evidence_bundles=[_evidence_bundle()],
            client=client,
        )

        prompt = client.prompts[0]
        self.assertIn("weeklyStudyStreak", prompt)
        self.assertIn("0111111", prompt)
        self.assertIn("scoreDeltaHint", prompt)
        self.assertIn("network 흐름 회복", prompt)
        self.assertIn("recentStudyTopics", prompt)
        self.assertIn("recentReports", prompt)
        self.assertIn("recentFieldChanges", prompt)
        self.assertIn("currentStats", prompt)
        self.assertIn("연속 작성", result["statusMessage"])
        self.assertIn("network 정체", result["dailyReport"]["feedback"])
        self.assertIn("currentStats", result["nextRecommendation"]["rationale"])

    def test_metadata_only_cannot_raise_score_delta_without_report_content(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        client = FakeReportClient(
            _success_payload(
                score_delta={field: 10 for field in SCORE_FIELDS},
                status_message="metadata만 보고 점수를 올리려는 응답",
            )
        )

        result = analyze_daily_report(
            report_title=" ",
            report_content=" ",
            user_metadata={
                "weeklyStudyStreak": "1111111",
                "reportDirection": {
                    "scoreDeltaHint": {field: 10 for field in SCORE_FIELDS},
                    "focus": "모든 분야 성장",
                },
                "recentFieldChanges": {field: "+10" for field in SCORE_FIELDS},
            },
            character_metadata={"currentStats": {field: 999 for field in SCORE_FIELDS}},
            client=client,
        )

        self.assertEqual(result["status"], "FALLBACK")
        self.assertEqual(result["scoreDelta"], {field: 0 for field in SCORE_FIELDS})
        self.assertEqual(client.prompts, [])

    def test_prebuilt_evidence_skips_retrieval_api_path(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        with patch(
            "app.scoring.report_analyzer.build_report_evidence_bundles",
            side_effect=AssertionError("retrieval should not run"),
        ):
            result = analyze_daily_report(
                report_title=None,
                report_content="JPA N+1을 정리했다.",
                report_chunks=[_report_chunk()],
                evidence_bundles=[_evidence_bundle()],
                client=FakeReportClient(_success_payload()),
            )

        self.assertEqual(result["status"], "SUCCESS")

    def test_missing_prebuilt_evidence_reuses_story3_public_functions(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        chunk = _report_chunk()
        store = object()
        embedding_store = object()

        with patch("app.scoring.report_analyzer.chunk_daily_report", return_value=[chunk]) as chunker, patch(
            "app.scoring.report_analyzer.load_concept_catalog_store",
            return_value=store,
        ) as catalog_loader, patch(
            "app.scoring.report_analyzer.load_concept_embedding_store",
            return_value=embedding_store,
        ) as embedding_loader, patch(
            "app.scoring.report_analyzer.build_report_evidence_bundles",
            return_value=[_evidence_bundle()],
        ) as evidence_builder:
            result = analyze_daily_report(
                report_title="오늘",
                report_content="JPA N+1을 정리했다.",
                client=FakeReportClient(_success_payload()),
            )

        self.assertEqual(result["status"], "SUCCESS")
        chunker.assert_called_once_with("오늘", "JPA N+1을 정리했다.")
        catalog_loader.assert_called_once_with()
        embedding_loader.assert_called_once_with(store=store)
        evidence_builder.assert_called_once_with(
            [chunk],
            store=store,
            embedding_store=embedding_store,
        )

    def test_quiz_and_callback_boundaries_are_not_called_or_output(self) -> None:
        from app.scoring.report_analyzer import analyze_daily_report

        with patch(
            "app.scoring.quiz_grader.grade_quiz_answer",
            side_effect=AssertionError("quiz grading must not be called"),
        ), patch(
            "app.rag.quiz_recommender.recommend_quizzes",
            side_effect=AssertionError("quiz recommender must not be called"),
        ):
            result = analyze_daily_report(
                report_title=None,
                report_content="JPA N+1을 정리했다.",
                report_chunks=[_report_chunk()],
                evidence_bundles=[_evidence_bundle()],
                client=FakeReportClient(_success_payload()),
            )

        forbidden_keys = {
            "recommendedQuizzes",
            "gradings",
            "submissionId",
            "requestId",
            "userId",
            "characterId",
            "targetDate",
        }
        self.assertTrue(forbidden_keys.isdisjoint(result))


def _report_chunk(text: str = "JPA N+1 원인과 fetch join 해결책을 정리했다.") -> ReportChunk:
    return ReportChunk(
        report_chunk_id="report:0",
        text=text,
        char_start=0,
        char_end=len(text),
        topic_hints=("JPA N+1", "Fetch Join"),
        field_hints=("db", "framework"),
    )


def _evidence_bundle() -> dict[str, Any]:
    return {
        "reportChunkId": "report:0",
        "query": {
            "text": "JPA N+1 원인과 fetch join 해결책을 정리했다.",
            "topicHints": ["JPA N+1", "Fetch Join"],
            "fieldHints": ["db", "framework"],
        },
        "matches": [
            {
                "chunkId": "concept:jpa-n-plus-one",
                "score": 9.1,
                "searchMode": "embedding",
                "matchedTerms": ["jpa", "fetch"],
                "sourcePath": "03-framework/jpa.md",
                "headingPath": ["Framework", "JPA", "N+1"],
                "fieldHints": ["db", "framework"],
                "text": "N+1 문제와 fetch join 해결책",
            }
        ],
        "neighborhood": [],
    }


def _success_payload(
    *,
    score_delta: Mapping[str, Any] | None = None,
    field_evidence: Mapping[str, Any] | None = None,
    confidence: float = 0.84,
    status_message: str = "핵심 원인과 해결책을 잘 연결했어요.",
    feedback: str = "fetch join과 BatchSize 차이를 비교한 점이 좋습니다.",
    rationale: str = "N+1 해결 도구를 비교했으므로 적용 한계로 확장하면 좋습니다.",
) -> dict[str, Any]:
    return {
        "status": "SUCCESS",
        "topics": ["JPA N+1", "fetch join"],
        "fieldEvidence": dict(
            field_evidence
            if field_evidence is not None
            else {
                "db": "N+1 문제의 원인을 설명했다.",
                "algorithm": "",
                "cs": "",
                "network": "",
                "framework": "JPA fetch join을 비교했다.",
            }
        ),
        "scoreDelta": dict(
            score_delta
            if score_delta is not None
            else {"db": 8, "algorithm": 0, "cs": 0, "network": 0, "framework": 7}
        ),
        "confidence": confidence,
        "emotion": "JOY",
        "statusMessage": status_message,
        "dailyReport": {
            "text": "오늘은 JPA N+1의 발생 원인과 해결 전략을 중심으로 학습했습니다.",
            "feedback": feedback,
        },
        "nextRecommendation": {
            "topics": ["EntityGraph", "fetch join 페이징 한계"],
            "rationale": rationale,
        },
        "extraTopLevelField": "removed",
        "recommendedQuizzes": [{"problemId": 1}],
    }


if __name__ == "__main__":
    unittest.main()
