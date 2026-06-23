from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any


MAX_DESIGN_KEYWORD_LENGTH = 80
PROMPT_TEMPLATE_VERSION = "commitgotchi_character_sprite_v9_retro_wide_3x1_poc"
# v9 POC:
# - generate only the hatched/evolved creature frames; pre-hatch eggs are static assets
# - keep a strict 3x1 horizontal sheet aligned with validation/storage contracts
# - align generated creatures with the Commit-gotchi retro pixel logo/mascot art
# - preserve the v5 creature style while requesting a frontend-ready 3:1 crop
# - preserve the magenta chroma-key guardrail used by post-processing
CANONICAL_SPRITE_PROMPT_TEMPLATE = """Create a pixel-art creature sprite sheet for Commit-gotchi, a cozy retro virtual pet game.

Draw exactly three pixel-art creatures laid out as a 1 row by 3 columns horizontal sprite sheet.
Use three invisible layout slots of equal size, with equal spacing and equal margins, and exactly one creature centered in each invisible slot at the same scale.
Compose the final PNG itself as a wide rectangular 3:1 sprite sheet, not as a square poster and not as a small strip floating inside a square canvas.
Keep only small top and bottom magenta padding so the row is easy to slice into three equal frontend sprite cells.
Each creature should fill most of its one-third frame cell while staying fully visible, aligned on the same baseline, and separated by clean magenta space.
Frame order from left to right: joy, sad, angry.
The words joy, sad, and angry are internal frame instructions only; never write these words or any emotion labels in the image.

These three sprites are the post-hatch evolved character only.
Do NOT draw an egg, cracked egg, baby form, pre-hatch form, evolution sequence, before/after comparison, or any second row.
All three sprites share the same post-hatch evolved creature identity based on this design keyword: "{designKeyword}".

Use the Commit-gotchi visual style: cute old-game pixel art, chunky mascot proportions, rounded dinosaur-logo-friendly charm, deep navy or near-black pixel outlines, a small limited pastel palette, crisp square pixels, and a simple readable front-facing silhouette.
Use flat color clusters with only one or two darker shade steps, like a classic handheld game sprite enlarged without smoothing.
The creature should feel friendly and toy-like, matching a retro pixel logo/mascot family, but not glossy, not modern 3D, not painterly, and not Japanese-anime styled.
Show emotion through the creature's face and pose only; do NOT add detached anger puffs, sweat drops, floating symbols, puddles, ground marks, shine streaks, aura, or effect marks outside the creature silhouette.

ABSOLUTELY NO text, NO letters, NO numbers, NO labels, NO captions, NO watermark, NO visible layout guides, and NO grid lines anywhere in the image.
Do NOT draw boxes, borders, panels, cards, frames, dividers, cell outlines, stage platforms, UI chrome, or colored backdrops around or behind the creatures.
The three creatures must float directly on the plain magenta background with empty magenta space between them.
Fill the ENTIRE background with one solid, flat, uniform magenta color (RGB 255,0,255); the background must be a single flat color, NOT a checkerboard, NOT a gradient, and NOT a pink scene backdrop.
Never use magenta anywhere inside the creatures. No shadows, no UI, no extra props.
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
