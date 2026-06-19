from __future__ import annotations

import math
import tempfile
import unittest
from pathlib import Path

from app.rag.problem_embedding_store import EmbeddedProblem, ProblemEmbeddingStore
from app.rag.problem_bank_store import ProblemBankStore
from app.rag.quiz_recommender import (
    build_recommendation_query,
    build_recommended_quizzes,
    recommend_quizzes,
)
from app.rag.schemas import ProblemRecord, ProblemRubric


class QuizRecommenderTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = ProblemBankStore(
            (
                _problem(
                    101,
                    "jpa-n-plus-one",
                    "JPA N+1 문제란 무엇이며 어떻게 해결하나요?",
                    (
                        "JPA N+1은 지연 로딩 연관 엔티티를 반복 접근할 때 "
                        "추가 쿼리가 발생하는 문제입니다. fetch join, "
                        "EntityGraph, Batch Size로 해결할 수 있습니다."
                    ),
                    "db",
                    ("db", "framework"),
                    ("N+1", "fetch join", "Lazy Loading"),
                ),
                _problem(
                    102,
                    "jpa-n-plus-one-duplicate",
                    "기본 Q5 2. N+1 쿼리 문제가 무엇이고 어떻게 해결하는지 서술하시오.",
                    "N+1 쿼리는 fetch join이나 batch size로 줄일 수 있습니다.",
                    "db",
                    ("db", "framework"),
                    ("N+1", "fetch join"),
                ),
                _problem(
                    202,
                    "oauth-jwt-flow",
                    "OAuth와 JWT 인증 흐름의 차이를 설명해주세요.",
                    (
                        "OAuth 2.0은 인가 프로토콜이고 Authorization Code를 "
                        "Access Token으로 교환합니다. JWT는 Header, Payload, "
                        "Signature로 구성된 토큰 형식입니다."
                    ),
                    "network",
                    ("network", "framework"),
                    ("OAuth", "JWT", "Authorization Code", "Access Token"),
                ),
                _problem(
                    303,
                    "rest-api",
                    "REST API란 무엇인가요?",
                    "REST는 HTTP와 URI 기반의 stateless API 설계 스타일입니다.",
                    "network",
                    ("network",),
                    ("REST", "HTTP", "URI", "stateless"),
                ),
                _problem(
                    404,
                    "docker-container",
                    "Docker 컨테이너와 이미지의 차이는?",
                    "이미지는 실행 템플릿이고 컨테이너는 실행 중인 인스턴스입니다.",
                    "framework",
                    ("framework", "cs"),
                    ("Docker", "container", "image"),
                ),
                _problem(
                    505,
                    "dijkstra",
                    "다익스트라 알고리즘을 설명해주세요.",
                    "가중치 그래프에서 최단 경로를 찾는 그리디 알고리즘입니다.",
                    "algorithm",
                    ("algorithm",),
                    ("graph", "shortest path", "priority queue"),
                ),
            )
        )

    def test_jpa_report_recommends_db_framework_problem(self) -> None:
        quizzes = recommend_quizzes(
            "JPA N+1과 fetch join을 공부했다",
            store=self.store,
        )

        self.assertGreaterEqual(len(quizzes), 1)
        self.assertEqual(quizzes[0]["problemId"], 101)
        self.assertGreater(quizzes[0]["scoreAllocation"]["db"], 0)
        self.assertGreater(quizzes[0]["scoreAllocation"]["framework"], 0)

    def test_oauth_jwt_report_recommends_network_framework_problem(self) -> None:
        quizzes = recommend_quizzes(
            "OAuth와 JWT 인증 흐름을 공부했다",
            store=self.store,
        )

        self.assertGreaterEqual(len(quizzes), 1)
        self.assertEqual(quizzes[0]["problemId"], 202)
        self.assertGreater(quizzes[0]["scoreAllocation"]["network"], 0)
        self.assertGreater(quizzes[0]["scoreAllocation"]["framework"], 0)

    def test_unrelated_report_returns_empty_array(self) -> None:
        self.assertEqual(
            recommend_quizzes("오늘 점심으로 김치찌개를 먹었다", store=self.store),
            [],
        )

    def test_hybrid_unrelated_report_returns_empty_array(self) -> None:
        low_positive_similarity = 0.2
        embedding_store = _embedding_store(
            self.store.records,
            vectors={},
            default=(
                low_positive_similarity,
                math.sqrt(1.0 - low_positive_similarity * low_positive_similarity),
            ),
        )

        quizzes = recommend_quizzes(
            "오늘 점심으로 김치찌개를 먹었다",
            store=self.store,
            embedding_store=embedding_store,
            client=_FakeEmbeddingClient((1.0, 0.0)),
        )

        self.assertEqual(quizzes, [])

    def test_recommendations_are_capped_at_three(self) -> None:
        quizzes = recommend_quizzes(
            "JPA N+1 fetch join OAuth JWT REST API Docker 컨테이너 다익스트라 그래프",
            store=self.store,
            limit=10,
            min_score=1,
        )

        self.assertLessEqual(len(quizzes), 3)

    def test_recommendation_contains_required_snapshot_fields(self) -> None:
        snapshot = recommend_quizzes("JPA N+1 fetch join", store=self.store)[0]

        for field_name in (
            "problemId",
            "question",
            "modelAnswer",
            "scoreAllocation",
        ):
            self.assertIn(field_name, snapshot)

    def test_missing_or_empty_problem_bank_file_returns_empty_array(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing_path = Path(temp_dir) / "missing.jsonl"
            empty_path = Path(temp_dir) / "empty.jsonl"
            empty_path.write_text("", encoding="utf-8")

            self.assertEqual(
                recommend_quizzes("JPA N+1 fetch join", catalog_path=missing_path),
                [],
            )
            self.assertEqual(
                recommend_quizzes("JPA N+1 fetch join", catalog_path=empty_path),
                [],
            )

    def test_report_analysis_mapping_can_be_used_as_input(self) -> None:
        query = build_recommendation_query(
            {
                "summary": "영속성 컨텍스트와 fetch join을 정리했다.",
                "detectedKeywords": ["JPA", "N+1"],
                "detectedFields": ["db", "framework"],
            }
        )
        quizzes = build_recommended_quizzes(
            {
                "summary": "영속성 컨텍스트와 fetch join을 정리했다.",
                "detectedKeywords": ["JPA", "N+1"],
                "detectedFields": ["db", "framework"],
            },
            store=self.store,
        )

        self.assertIn("JPA", query)
        self.assertEqual(quizzes[0]["problemId"], 101)

    def test_duplicate_same_topic_is_not_recommended_twice(self) -> None:
        quizzes = recommend_quizzes(
            "JPA N+1 fetch join lazy loading",
            store=self.store,
            limit=3,
        )

        n_plus_one_questions = [
            quiz for quiz in quizzes if "N+1" in quiz["question"]
        ]
        self.assertEqual(len(n_plus_one_questions), 1)

    def test_hybrid_candidates_preserve_limit_shape_and_dedupe(self) -> None:
        embedding_store = _embedding_store(
            self.store.records,
            vectors={
                101: (1.0, 0.0),
                102: (1.0, 0.0),
                202: (0.8, 0.2),
                303: (0.7, 0.3),
                404: (0.6, 0.4),
            },
            default=(0.0, 1.0),
        )

        quizzes = recommend_quizzes(
            "semantic sparse alpha",
            store=self.store,
            embedding_store=embedding_store,
            client=_FakeEmbeddingClient((1.0, 0.0)),
            limit=10,
            min_score=99.0,
        )

        self.assertLessEqual(len(quizzes), 3)
        self.assertEqual(len([quiz for quiz in quizzes if "N+1" in quiz["question"]]), 1)
        for snapshot in quizzes:
            self.assertEqual(
                set(snapshot),
                {
                    "problemId",
                    "question",
                    "modelAnswer",
                    "scoreAllocation",
                    "difficulty",
                    "sourcePath",
                    "matchScore",
                },
            )


def _problem(
    problem_id: int,
    key: str,
    question: str,
    model_answer: str,
    primary_field: str,
    fields: tuple[str, ...],
    rubric_terms: tuple[str, ...],
) -> ProblemRecord:
    score_allocation = {
        "db": 0,
        "algorithm": 0,
        "cs": 0,
        "network": 0,
        "framework": 0,
    }
    for field in fields:
        score_allocation[field] = 7
    return ProblemRecord(
        problem_id=problem_id,
        source_key=f"sha256:fixture-{key}",
        question=question,
        model_answer=model_answer,
        difficulty="intermediate",
        primary_field=primary_field,
        fields=fields,
        score_allocation=score_allocation,
        source_path=f"fixtures/{key}.md",
        heading_path=("Fixture", question),
        rubric=ProblemRubric(must_mention=rubric_terms),
        content_hash=f"sha256:fixture-{key}-content",
    )


class _FakeEmbeddingClient:
    def __init__(self, query_embedding: tuple[float, ...]):
        self.query_embedding = query_embedding

    def embed_document(self, text: str) -> tuple[float, ...]:
        return self.query_embedding

    def embed_query(self, text: str) -> tuple[float, ...]:
        return self.query_embedding


def _embedding_store(
    records: tuple[ProblemRecord, ...],
    *,
    vectors: dict[int, tuple[float, ...]],
    default: tuple[float, ...],
) -> ProblemEmbeddingStore:
    return ProblemEmbeddingStore(
        items=tuple(
            EmbeddedProblem(
                problem=problem,
                embedding=vectors.get(problem.problem_id, default),
            )
            for problem in records
        ),
        expected_model="fake",
        expected_dimensionality=len(default),
    )


if __name__ == "__main__":
    unittest.main()
