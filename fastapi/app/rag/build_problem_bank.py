from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .config import resolve_default_source_root
from .problem_bank import DEFAULT_DATA_DIR, build_problem_bank


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build the static FastAPI RAG problem bank catalog.",
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=resolve_default_source_root(),
        help="Markdown/CSV source root. Defaults to RAG_SOURCE_ROOT or ~/study/md파일들.",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=DEFAULT_DATA_DIR,
        help="Output data dir inside fastapi. Defaults to fastapi/data/rag.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Parse and validate without writing catalog or reports.",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        result = build_problem_bank(args.source, args.out, dry_run=args.dry_run)
    except (FileNotFoundError, ValueError) as exc:
        print(f"problem bank build failed: {exc}", file=sys.stderr)
        return 2

    print(json.dumps(result.summary, ensure_ascii=False, indent=2, sort_keys=True))
    if not args.dry_run:
        print(f"problems: {result.output_paths['problems']}")
        print(f"skipped: {result.output_paths['skipped']}")
        print(f"rejected: {result.output_paths['rejected']}")
        print(f"summary: {result.output_paths['summaryJson']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
