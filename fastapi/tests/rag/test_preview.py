from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from app.rag import build_preview_response


class RagPreviewTest(unittest.TestCase):
    def test_recommends_existing_qna_problem(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_root = Path(temp_dir)
            qna_file = source_root / "computer-science-interview-KR" / "08-api-design"
            qna_file.mkdir(parents=True)
            (qna_file / "qna-api-design.md").write_text(
                """
# API 설계 면접 질문 & 답변

## Q1. RESTful API의 설계 원칙은? ⭐⭐⭐

<details>
<summary>답변 보기</summary>

REST는 HTTP 기반의 아키텍처 스타일입니다. Stateless, Client-Server,
Uniform Interface, Cacheable 원칙을 지키며 URI는 리소스를 표현하고
행위는 HTTP 메서드로 표현합니다. GET, POST, PUT, PATCH, DELETE를
의미에 맞게 사용하고 상태 코드를 일관되게 반환해야 합니다.

</details>
""",
                encoding="utf-8",
            )

            result = build_preview_response(
                "오늘 RESTful API 설계 원칙과 HTTP 메서드를 공부했다.",
                source_root=source_root,
            )

        self.assertEqual(result["detectedFields"][0], "network")
        self.assertGreaterEqual(len(result["nearbyConcepts"]), 1)
        self.assertEqual(len(result["recommendedQuizzes"]), 1)
        self.assertIn("RESTful API", result["recommendedQuizzes"][0]["question"])
        self.assertEqual(result["recommendedQuizzes"][0]["scoreAllocation"]["network"], 10)

    def test_does_not_emit_quiz_when_problem_is_not_nearby(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_root = Path(temp_dir)
            docs = source_root / "docs"
            docs.mkdir()
            (docs / "os.md").write_text(
                """
# 운영체제

## 프로세스와 스레드

프로세스는 실행 중인 프로그램의 인스턴스이며 독립된 메모리 공간을 가집니다.
스레드는 프로세스 내부의 실행 흐름으로 같은 주소 공간을 공유합니다.
이 차이는 메모리 격리와 문맥 교환 비용을 이해할 때 중요합니다.
""",
                encoding="utf-8",
            )

            result = build_preview_response(
                "오늘 OAuth 로그인 흐름을 공부했다.",
                source_root=source_root,
                min_problem_score=3.5,
            )

        self.assertEqual(result["recommendedQuizzes"], [])


if __name__ == "__main__":
    unittest.main()
