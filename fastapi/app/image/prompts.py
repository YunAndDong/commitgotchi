from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any


MAX_DESIGN_KEYWORD_LENGTH = 80
PROMPT_TEMPLATE_VERSION = "commitgotchi_character_sprite_v2"
# v2 hardening (validated by the character-image-quality POC):
# - drop "16x16/18x18" wording (the model baked it as visible "16x1" labels)
# - forbid any text/numbers/labels outright
# - request one solid flat magenta background so deterministic border flood-fill
#   can key it out, and forbid per-creature backdrop panels
CANONICAL_SPRITE_PROMPT_TEMPLATE = """Create a pixel-art creature sprite sheet for a retro handheld virtual pet game.

Draw exactly six pixel-art creatures laid out on a uniform grid of 2 rows and 3 columns.
Every one of the six grid cells is the exact same size, with equal spacing and equal margins, and exactly one creature is centered inside each cell at roughly the same scale.
Top row left to right: happy, sad, angry. Bottom row left to right: happy, sad, angry.
The bottom-row creatures are a slightly larger, more evolved version of the same creature.

All six sprites share the same creature identity based on this design keyword: "{designKeyword}".
Use clean black outlines, a vibrant limited palette, a simple readable silhouette, and a front-facing pose.

ABSOLUTELY NO text, NO letters, NO numbers, NO labels, NO captions, NO watermark, and NO grid lines anywhere in the image.
Fill the ENTIRE background with one solid, flat, uniform magenta color (RGB 255,0,255); the background must be a single flat color, NOT a checkerboard and NOT a gradient.
Do NOT draw any panel, card, frame, or colored backdrop behind the creatures, and never use magenta anywhere inside the creatures. No shadows, no UI.
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
