from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


FASTAPI_ROOT = Path(__file__).resolve().parents[1]
if str(FASTAPI_ROOT) not in sys.path:
    sys.path.insert(0, str(FASTAPI_ROOT))

from app.rag import build_preview_response  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Preview Commitgotchi RAG business logic without running the API server.",
    )
    parser.add_argument(
        "report",
        nargs="*",
        help="User study report text. If omitted, stdin is used.",
    )
    parser.add_argument(
        "--source-root",
        type=Path,
        default=None,
        help="Override RAG source root. Defaults to RAG_SOURCE_ROOT or ~/study/md파일들.",
    )
    parser.add_argument("--max-concepts", type=int, default=4)
    parser.add_argument("--max-quizzes", type=int, default=3)
    parser.add_argument("--min-problem-score", type=float, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report_text = " ".join(args.report).strip()
    if not report_text:
        report_text = sys.stdin.read().strip()

    response = build_preview_response(
        report_text,
        source_root=args.source_root,
        max_concepts=args.max_concepts,
        max_quizzes=args.max_quizzes,
        min_problem_score=args.min_problem_score,
    )
    print(json.dumps(response, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
