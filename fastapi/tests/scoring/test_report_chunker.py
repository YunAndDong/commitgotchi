from __future__ import annotations

import unittest

from app.rag.schemas import SCORE_FIELDS
from app.scoring.report_chunker import chunk_daily_report, combine_report_text


class ReportChunkerTest(unittest.TestCase):
    def test_mixed_jpa_oauth_process_report_splits_into_multiple_chunks(self) -> None:
        chunks = chunk_daily_report(
            "오늘의 학습",
            (
                "JPA N+1이 Lazy Loading에서 왜 발생하는지 정리했다.\n\n"
                "OAuth/JWT 인증 흐름과 Access Token 검증 과정을 복습했다.\n\n"
                "프로세스/스레드 차이와 context switching 비용도 비교했다."
            ),
        )

        self.assertGreaterEqual(len(chunks), 2)
        joined_topics = [hint for chunk in chunks for hint in chunk.topic_hints]
        joined_fields = [hint for chunk in chunks for hint in chunk.field_hints]
        self.assertIn("JPA N+1", joined_topics)
        self.assertIn("OAuth/JWT", joined_topics)
        self.assertIn("프로세스/스레드", joined_topics)
        self.assertIn("db", joined_fields)
        self.assertIn("network", joined_fields)
        self.assertIn("cs", joined_fields)

    def test_bullet_list_and_paragraph_preserve_input_order(self) -> None:
        chunks = chunk_daily_report(
            "",
            (
                "오늘은 인증 흐름을 먼저 훑었다.\n\n"
                "- JPA N+1 해결 방법을 fetch join과 비교했다.\n"
                "- OAuth/JWT 토큰 흐름을 다시 그렸다.\n\n"
                "마지막으로 프로세스/스레드 차이를 정리했다."
            ),
        )

        chunk_texts = [chunk.text for chunk in chunks]
        ordered_terms = ["인증 흐름", "JPA N+1", "OAuth/JWT", "프로세스/스레드"]
        last_index = -1
        for term in ordered_terms:
            index = next(i for i, text in enumerate(chunk_texts) if term in text)
            self.assertGreater(index, last_index)
            last_index = index

    def test_markdown_heading_starts_new_topic_boundary(self) -> None:
        chunks = chunk_daily_report(
            None,
            (
                "# JPA\n"
                "N+1 문제와 Lazy Loading 발생 조건을 정리했다.\n\n"
                "## OAuth\n"
                "JWT와 Authorization Code 흐름을 복습했다.\n\n"
                "## OS\n"
                "프로세스와 스레드의 메모리 공유 차이를 비교했다."
            ),
        )

        texts = [chunk.text for chunk in chunks]
        self.assertGreaterEqual(len(chunks), 3)
        self.assertLess(_index_containing(texts, "JPA"), _index_containing(texts, "OAuth"))
        self.assertLess(_index_containing(texts, "OAuth"), _index_containing(texts, "OS"))

    def test_markdown_heading_boundary_does_not_depend_on_topic_hints(self) -> None:
        chunks = chunk_daily_report(
            None,
            (
                "# 트랜잭션 격리\n"
                "격리 수준별 현상을 정리했다.\n\n"
                "## 정규화\n"
                "1NF와 2NF 차이를 정리했다."
            ),
        )

        texts = [chunk.text for chunk in chunks]
        self.assertEqual(len(chunks), 2)
        self.assertIn("트랜잭션 격리", texts[0])
        self.assertIn("정규화", texts[1])
        self.assertNotIn("정규화", texts[0])

    def test_report_title_can_merge_with_first_heading(self) -> None:
        chunks = chunk_daily_report(
            "오늘의 학습",
            "# 트랜잭션 격리\n격리 수준별 현상을 정리했다.",
        )

        self.assertEqual(len(chunks), 1)
        self.assertIn("오늘의 학습", chunks[0].text)
        self.assertIn("트랜잭션 격리", chunks[0].text)

    def test_short_blank_and_long_single_paragraph_are_safe(self) -> None:
        self.assertEqual(chunk_daily_report("  ", "\n\t "), [])

        short_chunks = chunk_daily_report(None, "JWT 복습")
        self.assertEqual(len(short_chunks), 1)
        self.assertEqual(short_chunks[0].text, "JWT 복습")

        sentence = (
            "JPA N+1 문제를 Lazy Loading, fetch join, EntityGraph 관점에서 비교하고 "
            "OAuth/JWT 인증 흐름과 프로세스/스레드 전환 비용까지 함께 정리했다. "
        )
        long_chunks = chunk_daily_report(None, sentence * 12)
        self.assertGreaterEqual(len(long_chunks), 2)
        self.assertTrue(all(chunk.text for chunk in long_chunks))

    def test_char_offsets_track_normalized_combined_text_substrings(self) -> None:
        title = "오늘의 학습"
        content = (
            "# JPA\n"
            "JPA N+1을 정리했다.\n\n"
            "- OAuth/JWT 인증을 복습했다."
        )
        combined = combine_report_text(title, content)
        chunks = chunk_daily_report(title, content)

        self.assertTrue(chunks)
        for chunk in chunks:
            self.assertGreaterEqual(chunk.char_start, 0)
            self.assertLessEqual(chunk.char_end, len(combined))
            self.assertLess(chunk.char_start, chunk.char_end)
            self.assertEqual(combined[chunk.char_start : chunk.char_end].strip(), chunk.text)

    def test_same_input_produces_stable_ids_and_order(self) -> None:
        title = "안정성 확인"
        content = "JPA N+1을 정리했다.\n\nOAuth/JWT를 복습했다."

        first = chunk_daily_report(title, content)
        second = chunk_daily_report(title, content)

        self.assertEqual([chunk.to_dict() for chunk in first], [chunk.to_dict() for chunk in second])
        self.assertEqual(
            [chunk.report_chunk_id for chunk in first],
            [f"report:{index}" for index in range(len(first))],
        )
        self.assertEqual([chunk.char_start for chunk in first], sorted(chunk.char_start for chunk in first))

    def test_field_hints_do_not_create_final_score_objects(self) -> None:
        chunks = chunk_daily_report(None, "JPA N+1과 OAuth/JWT 인증 흐름을 복습했다.")

        self.assertTrue(chunks)
        for chunk in chunks:
            payload = chunk.to_dict()
            self.assertTrue(set(payload["fieldHints"]).issubset(set(SCORE_FIELDS)))
            for forbidden_key in (
                "scoreDelta",
                "scoreDeltaHint",
                "scoreAllocation",
                "recommendedQuizzes",
                "matches",
                "embedding",
                "evidence",
            ):
                self.assertNotIn(forbidden_key, payload)


def _index_containing(values: list[str], needle: str) -> int:
    return next(index for index, value in enumerate(values) if needle in value)


if __name__ == "__main__":
    unittest.main()
