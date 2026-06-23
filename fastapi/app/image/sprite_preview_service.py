"""POC service for character-image-quality option A.

Pipeline: design keyword -> Gemini sprite sheet -> deterministic background
removal -> uniform 1x3 frame atlas normalization -> validate -> save stages under
fastapi/runtime/data/character-images/preview/ for manual inspection.

Endpoint wiring is intentionally out of scope; this is a quick generation +
post-processing proof of concept. Real Gemini is only called when no client is
injected, so tests can pass a fake client.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .background_removal import remove_background_to_alpha
from .config import CharacterImageSettings, FASTAPI_CHARACTER_IMAGE_ROOT
from .frame_normalizer import normalize_sprite_grid
from .gemini_client import GeminiImageGenerationClient
from .png_validation import PngValidationError, validate_transparent_png
from .prompts import build_sprite_prompt
from .sprite_service import ImageGenerationClient


@dataclass(frozen=True)
class SpritePreviewResult:
    status: str  # "READY" | "FALLBACK"
    design_keyword_summary: dict[str, Any]
    stages: dict[str, str] = field(default_factory=dict)
    metrics: dict[str, Any] = field(default_factory=dict)
    reason: str | None = None

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "status": self.status,
            "designKeyword": self.design_keyword_summary,
            "stages": self.stages,
            "metrics": self.metrics,
        }
        if self.reason is not None:
            payload["reason"] = self.reason
        return payload


def build_preview_prompt(design_keyword: str | None) -> tuple[str, dict[str, Any]]:
    built = build_sprite_prompt(design_keyword)
    return built.prompt, built.keyword.loggable_summary()


def generate_sprite_preview(
    design_keyword: str | None,
    *,
    client: ImageGenerationClient | None = None,
    settings: CharacterImageSettings | None = None,
    output_dir: Path | None = None,
) -> SpritePreviewResult:
    image_settings = settings or CharacterImageSettings.from_app_settings()
    prompt, keyword_summary = build_preview_prompt(design_keyword)

    out_dir = output_dir or (FASTAPI_CHARACTER_IMAGE_ROOT / "preview" / _slug(design_keyword))
    out_dir.mkdir(parents=True, exist_ok=True)

    image_client = client or GeminiImageGenerationClient.from_settings(image_settings)
    raw_png = image_client.generate_png(
        prompt,
        model=image_settings.model,
        timeout_seconds=image_settings.timeout_seconds,
    )
    if not raw_png:
        return SpritePreviewResult("FALLBACK", keyword_summary, reason="EMPTY_IMAGE_BYTES")

    stages: dict[str, str] = {}
    metrics: dict[str, Any] = {}

    raw_path = out_dir / "1-raw.png"
    raw_path.write_bytes(raw_png)
    stages["raw"] = str(raw_path)
    metrics["rawBytes"] = len(raw_png)

    background = remove_background_to_alpha(raw_png)
    bg_path = out_dir / "2-background-removed.png"
    bg_path.write_bytes(background.png_bytes)
    stages["backgroundRemoved"] = str(bg_path)
    metrics["transparentRatio"] = round(background.transparent_ratio, 4)
    metrics["backgroundPalette"] = [list(color) for color in background.background_palette]
    metrics["sourceSize"] = [background.width, background.height]

    normalization = normalize_sprite_grid(background.png_bytes)
    final_png = normalization.png_bytes
    final_path = out_dir / "3-normalized.png"
    final_path.write_bytes(final_png)
    stages["normalized"] = str(final_path)
    metrics["normalized"] = normalization.success
    metrics["cell"] = [normalization.cell_width, normalization.cell_height]
    if not normalization.success:
        metrics["normalizationReason"] = normalization.reason

    try:
        meta = validate_transparent_png(final_png)
    except PngValidationError as exc:
        return SpritePreviewResult(
            "FALLBACK",
            keyword_summary,
            stages=stages,
            metrics=metrics,
            reason=f"VALIDATION_{exc.reason}",
        )

    metrics["finalSize"] = [meta.width, meta.height]
    metrics["hasAlpha"] = meta.has_alpha
    status = "READY" if normalization.success else "FALLBACK"
    reason = None if normalization.success else "GRID_NORMALIZATION_FAILED"
    return SpritePreviewResult(status, keyword_summary, stages=stages, metrics=metrics, reason=reason)


def _slug(value: str | None) -> str:
    text = re.sub(r"[^A-Za-z0-9가-힣_-]+", "-", (value or "preview").strip())
    return text.strip("-")[:48] or "preview"
