"""Manual preview runner for character-image-quality option A.

Usage (from fastapi/ with venv active and GEMINI_API_KEY set):

    python -m scripts.character_image_preview "별의 커비에 나오는 커비"

Calls the real Gemini image model, runs the deterministic post-processing
pipeline, saves each stage under fastapi/runtime/data/character-images/preview/,
and prints a safe summary (no API key, no full prompt, no image bytes).
"""

from __future__ import annotations

import json
import sys

from app.image.sprite_preview_service import generate_sprite_preview


DEFAULT_KEYWORD = "별의 커비에 나오는 커비"


def main(argv: list[str]) -> int:
    keyword = argv[1] if len(argv) > 1 else DEFAULT_KEYWORD
    result = generate_sprite_preview(keyword)
    print(json.dumps(result.to_dict(), ensure_ascii=False, indent=2))
    return 0 if result.status == "READY" else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
