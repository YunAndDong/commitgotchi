from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .concept_catalog import (
    DEFAULT_CONCEPT_SOURCE_ROOT,
    DEFAULT_DATA_DIR,
    build_concept_catalog,
)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build the static FastAPI RAG concept chunk catalog.",
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=DEFAULT_CONCEPT_SOURCE_ROOT,
        help=(
            "Markdown source root. Must be inside "
            f"{DEFAULT_CONCEPT_SOURCE_ROOT.as_posix()}."
        ),
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
        result = build_concept_catalog(args.source, args.out, dry_run=args.dry_run)
    except (FileNotFoundError, ValueError) as exc:
        print(f"concept catalog build failed: {exc}", file=sys.stderr)
        return 2

    print(json.dumps(result.summary, ensure_ascii=False, indent=2, sort_keys=True))
    if not args.dry_run:
        print(f"chunks: {result.output_paths['chunks']}")
        print(f"skipped: {result.output_paths['skipped']}")
        print(f"rejected: {result.output_paths['rejected']}")
        print(f"summary: {result.output_paths['summaryJson']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
