from __future__ import annotations

import json
import io
import shutil
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from app.rag.build_problem_bank import main as cli_main
from app.rag.problem_bank import FASTAPI_ROOT, build_problem_bank


FIXTURE_ROOT = FASTAPI_ROOT / "tests" / "fixtures" / "rag" / "problem_bank"


class ProblemBankPipelineTest(unittest.TestCase):
    def test_builds_jsonl_catalog_and_reports_from_fixtures(self) -> None:
        with self._source_copy() as source_root:
            out_dir = source_root.parent / "data" / "rag"

            result = build_problem_bank(source_root, out_dir)

            problems_path = out_dir / "catalog" / "problems.jsonl"
            skipped_path = out_dir / "reports" / "problem-bank-skipped.jsonl"
            rejected_path = out_dir / "reports" / "problem-bank-rejected.jsonl"
            summary_path = out_dir / "reports" / "problem-bank-summary.json"

            self.assertTrue(problems_path.exists())
            self.assertTrue(skipped_path.exists())
            self.assertTrue(rejected_path.exists())
            self.assertTrue(summary_path.exists())
            self.assertEqual(result.summary["storedProblemCount"], 2)
            self.assertEqual(result.summary["rejectedCount"], 0)

            problems = [json.loads(line) for line in problems_path.read_text(encoding="utf-8").splitlines()]
            questions = {problem["question"] for problem in problems}

            self.assertIn("RESTful API의 설계 원칙은?", questions)
            self.assertIn("모듈과 모듈 번들링이 무엇인지 설명하고, 번들링이 필요한 이유를 서술하세요.", questions)
            self.assertNotIn("Spring MVC의 요청 처리 흐름을 순서대로 설명하세요.", questions)
            for problem in problems:
                self.assertEqual(
                    set(problem),
                    {
                        "problemId",
                        "sourceKey",
                        "question",
                        "modelAnswer",
                        "difficulty",
                        "primaryField",
                        "fields",
                        "scoreAllocation",
                        "sourcePath",
                        "headingPath",
                        "rubric",
                        "contentHash",
                    },
                )
                self.assertEqual(set(problem["scoreAllocation"]), {"db", "algorithm", "cs", "network", "framework"})
                self.assertTrue(problem["sourceKey"].startswith("sha256:"))
                self.assertTrue(problem["contentHash"].startswith("sha256:"))
                self.assertTrue(problem["rubric"]["mustMention"] or problem["rubric"]["optionalMention"])

            rest_problem = next(problem for problem in problems if "RESTful API" in problem["question"])
            self.assertEqual(rest_problem["difficulty"], "advanced")
            self.assertEqual(rest_problem["scoreAllocation"]["network"], 10)

            build_tools_problem = next(problem for problem in problems if "모듈 번들링" in problem["question"])
            self.assertEqual(build_tools_problem["difficulty"], "basic")
            self.assertEqual(build_tools_problem["scoreAllocation"]["framework"], 5)

            skipped = [json.loads(line) for line in skipped_path.read_text(encoding="utf-8").splitlines()]
            skipped_reasons = {item["reason"] for item in skipped}
            self.assertTrue(
                {
                    "missing_model_answer",
                    "confidential_path",
                    "ds_store",
                    "empty_file",
                    "binary_file",
                    "image_file",
                    "csv_metadata_only",
                }.issubset(skipped_reasons)
            )

    def test_repeated_build_is_deterministic_and_answer_change_keeps_source_key(self) -> None:
        with self._source_copy() as source_root:
            first = build_problem_bank(source_root, source_root.parent / "data-a" / "rag")
            second = build_problem_bank(source_root, source_root.parent / "data-b" / "rag")

            first_lines = [
                json.dumps(problem.to_json_dict(), ensure_ascii=False)
                for problem in first.problems
            ]
            second_lines = [
                json.dumps(problem.to_json_dict(), ensure_ascii=False)
                for problem in second.problems
            ]
            self.assertEqual(first_lines, second_lines)

            qna_path = source_root / "qna" / "qna-api.md"
            qna_text = qna_path.read_text(encoding="utf-8")
            qna_path.write_text(
                qna_text.replace(
                    "</details>",
                    "추가 설명: 상태 코드는 성공, 클라이언트 오류, 서버 오류를 구분하는 기준입니다.\n\n</details>",
                ),
                encoding="utf-8",
            )
            changed = build_problem_bank(source_root, source_root.parent / "data-c" / "rag")

            before = next(problem for problem in first.problems if "RESTful API" in problem.question)
            after = next(problem for problem in changed.problems if "RESTful API" in problem.question)
            self.assertEqual(before.problem_id, after.problem_id)
            self.assertEqual(before.source_key, after.source_key)
            self.assertNotEqual(before.content_hash, after.content_hash)

    def test_cli_dry_run_does_not_write_and_rejects_outside_fastapi_output(self) -> None:
        with tempfile.TemporaryDirectory(dir=FASTAPI_ROOT) as temp_dir:
            out_dir = Path(temp_dir) / "dry-run" / "rag"

            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                exit_code = cli_main(["--source", str(FIXTURE_ROOT), "--out", str(out_dir), "--dry-run"])

            self.assertEqual(exit_code, 0)
            self.assertFalse(out_dir.exists())

        outside_dir = Path(tempfile.gettempdir()) / "commitgotchi-problem-bank-outside"
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            exit_code = cli_main(["--source", str(FIXTURE_ROOT), "--out", str(outside_dir), "--dry-run"])

        self.assertEqual(exit_code, 2)

    def _source_copy(self):
        temp_dir = tempfile.TemporaryDirectory(dir=FASTAPI_ROOT)
        source_root = Path(temp_dir.name) / "source"
        shutil.copytree(FIXTURE_ROOT, source_root)
        (source_root / ".DS_Store").write_bytes(b"fixture")
        (source_root / "empty.md").write_text("", encoding="utf-8")
        (source_root / "binary.md").write_bytes(b"\x00\x01\x02markdown?")
        return _TempSource(temp_dir, source_root)


class _TempSource:
    def __init__(self, temp_dir: tempfile.TemporaryDirectory[str], source_root: Path):
        self.temp_dir = temp_dir
        self.source_root = source_root

    def __enter__(self) -> Path:
        return self.source_root

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.temp_dir.cleanup()


if __name__ == "__main__":
    unittest.main()
