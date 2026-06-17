from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any


MAX_DESIGN_KEYWORD_LENGTH = 80
PROMPT_TEMPLATE_VERSION = "commitgotchi_character_sprite_v1"
CANONICAL_SPRITE_PROMPT_TEMPLATE = """Create a transparent PNG sprite sheet for a retro handheld virtual pet game.

Six distinct pixel-art creatures arranged in a precise 2x3 layout, no grid lines and no text.
Each creature is drawn as a crisp pixel-art sprite on a logical pixel grid, enlarged for extraction with sharp nearest-neighbor edges.

Columns: happy, sad, angry.
Row 1: baby stage, logical 16x16 sprite design.
Row 2: mature evolved stage, logical 18x18 sprite design.

All six sprites share the same creature identity based on this design keyword: "{designKeyword}".
Use clean black outlines, vibrant limited palette, simple readable silhouette, front-facing pose, transparent background, no shadows, no UI, no labels.
The output should function as a game asset sprite sheet with alpha transparency.
"""

_CONTROL_CHARACTERS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_FORMAT_CONTROL_CHARACTERS = re.compile(r"[\u200b-\u200f\u202a-\u202e\u2066-\u2069]")
_LINE_BREAKS = re.compile(r"[\r\n\u2028\u2029]+")
_WHITESPACE = re.compile(r"\s+")


class DesignKeywordError(ValueError):
    """Raised when a design keyword cannot be safely used in the prompt."""


@dataclass(frozen=True, repr=False)
class SanitizedDesignKeyword:
    prompt_value: str
    keyword_length: int
    keyword_sha256: str
    truncated: bool

    def loggable_summary(self) -> dict[str, Any]:
        return {
            "keywordLength": self.keyword_length,
            "keywordSha256": self.keyword_sha256,
            "truncated": self.truncated,
        }

    def __repr__(self) -> str:
        return (
            "SanitizedDesignKeyword("
            f"keyword_length={self.keyword_length!r}, "
            f"keyword_sha256={self.keyword_sha256!r}, "
            f"truncated={self.truncated!r})"
        )


@dataclass(frozen=True, repr=False)
class BuiltSpritePrompt:
    prompt: str
    keyword: SanitizedDesignKeyword
    template_version: str = PROMPT_TEMPLATE_VERSION

    def loggable_summary(self) -> dict[str, Any]:
        return {
            "templateVersion": self.template_version,
            "promptSha256": hashlib.sha256(self.prompt.encode("utf-8")).hexdigest()[:16],
            **self.keyword.loggable_summary(),
        }

    def __repr__(self) -> str:
        return (
            "BuiltSpritePrompt("
            "prompt=<redacted>, "
            f"template_version={self.template_version!r}, "
            f"keyword={self.keyword!r})"
        )


def sanitize_design_keyword(
    design_keyword: str | None,
    *,
    max_length: int = MAX_DESIGN_KEYWORD_LENGTH,
) -> SanitizedDesignKeyword:
    if design_keyword is None:
        raise DesignKeywordError("design keyword is required")
    if max_length <= 0:
        raise ValueError("max_length must be positive")

    normalized = _normalize_design_keyword(str(design_keyword))
    if not normalized:
        raise DesignKeywordError("design keyword is required")

    truncated = len(normalized) > max_length
    capped = normalized[:max_length].rstrip() if truncated else normalized
    if not capped:
        raise DesignKeywordError("design keyword is required")

    prompt_value = _escape_for_quoted_prompt(capped)
    digest = hashlib.sha256(capped.encode("utf-8")).hexdigest()[:16]
    return SanitizedDesignKeyword(
        prompt_value=prompt_value,
        keyword_length=len(capped),
        keyword_sha256=digest,
        truncated=truncated,
    )


def build_sprite_prompt(design_keyword: str | None) -> BuiltSpritePrompt:
    sanitized = sanitize_design_keyword(design_keyword)
    if CANONICAL_SPRITE_PROMPT_TEMPLATE.count("{designKeyword}") != 1:
        raise RuntimeError(
            "canonical sprite prompt template must contain exactly one designKeyword placeholder"
        )
    return BuiltSpritePrompt(
        prompt=CANONICAL_SPRITE_PROMPT_TEMPLATE.replace(
            "{designKeyword}",
            sanitized.prompt_value,
        ),
        keyword=sanitized,
    )


def _normalize_design_keyword(value: str) -> str:
    without_line_breaks = _LINE_BREAKS.sub(" ", value)
    without_controls = _CONTROL_CHARACTERS.sub("", without_line_breaks)
    without_format_controls = _FORMAT_CONTROL_CHARACTERS.sub("", without_controls)
    return _WHITESPACE.sub(" ", without_format_controls).strip()


def _escape_for_quoted_prompt(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)[1:-1]
