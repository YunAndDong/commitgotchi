"""Sprite sheet quality gate (character-image-quality Story 3).

Runs after background removal + grid normalization, before a sheet is accepted
as READY. Catches two classes of "passes PNG validation but is visually dirty"
output that alpha + dimension checks miss:

- per-cell panel background: the model drew a solid backdrop behind each
  creature, so the cell is opaque to its corners (low corner transparency).
- empty / missing frame: a cell has no opaque sprite pixels.
"""

from __future__ import annotations

import io
from dataclasses import dataclass

from PIL import Image


ALPHA_THRESHOLD = 16
# Calibrated on real gemini-2.5-flash-image output: clean centered sprites score
# corner transparency 0.77-0.97, while per-creature panel backdrops score ~0.07.
# 0.45 sits in the wide gap so panels are rejected while dramatic effect frames
# (fire/wings reaching a corner) are not falsely failed.
DEFAULT_MIN_CORNER_TRANSPARENCY = 0.45
DEFAULT_MIN_CELL_OPAQUE_RATIO = 0.02
CORNER_FRACTION = 0.22


@dataclass(frozen=True)
class QualityGateResult:
    passed: bool
    reason: str | None = None
    overall_transparent_ratio: float = 0.0
    min_corner_transparency: float = 0.0
    min_cell_opaque_ratio: float = 0.0


def evaluate_sprite_quality(
    png_bytes: bytes,
    *,
    columns: int,
    rows: int,
    cell_width: int,
    cell_height: int,
    min_corner_transparency: float = DEFAULT_MIN_CORNER_TRANSPARENCY,
    min_cell_opaque_ratio: float = DEFAULT_MIN_CELL_OPAQUE_RATIO,
) -> QualityGateResult:
    if columns <= 0 or rows <= 0 or cell_width <= 0 or cell_height <= 0:
        return QualityGateResult(False, reason="invalid_grid_geometry")

    image = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    alpha = image.getchannel("A").tobytes()
    width, height = image.size

    corner_w = max(1, int(cell_width * CORNER_FRACTION))
    corner_h = max(1, int(cell_height * CORNER_FRACTION))

    overall_opaque = 0
    worst_corner_transparency = 1.0
    worst_cell_opaque_ratio = 1.0

    for row in range(rows):
        for col in range(columns):
            x0 = col * cell_width
            y0 = row * cell_height
            x1 = min(width, x0 + cell_width)
            y1 = min(height, y0 + cell_height)

            opaque = _opaque_count(alpha, width, x0, y0, x1, y1)
            cell_area = (x1 - x0) * (y1 - y0)
            overall_opaque += opaque
            opaque_ratio = opaque / cell_area if cell_area else 0.0
            worst_cell_opaque_ratio = min(worst_cell_opaque_ratio, opaque_ratio)

            corner_transparency = _corner_transparency(
                alpha, width, x0, y0, x1, y1, corner_w, corner_h
            )
            worst_corner_transparency = min(worst_corner_transparency, corner_transparency)

    total_area = width * height
    overall_transparent_ratio = (
        1.0 - (overall_opaque / total_area) if total_area else 0.0
    )

    if worst_cell_opaque_ratio < min_cell_opaque_ratio:
        reason = "empty_cell"
    elif worst_corner_transparency < min_corner_transparency:
        reason = "panel_background"
    else:
        reason = None

    return QualityGateResult(
        passed=reason is None,
        reason=reason,
        overall_transparent_ratio=round(overall_transparent_ratio, 4),
        min_corner_transparency=round(worst_corner_transparency, 4),
        min_cell_opaque_ratio=round(worst_cell_opaque_ratio, 4),
    )


def _opaque_count(alpha: bytes, width: int, x0: int, y0: int, x1: int, y1: int) -> int:
    count = 0
    for y in range(y0, y1):
        base = y * width
        for x in range(x0, x1):
            if alpha[base + x] > ALPHA_THRESHOLD:
                count += 1
    return count


def _corner_transparency(
    alpha: bytes,
    width: int,
    x0: int,
    y0: int,
    x1: int,
    y1: int,
    corner_w: int,
    corner_h: int,
) -> float:
    regions = (
        (x0, y0, x0 + corner_w, y0 + corner_h),
        (x1 - corner_w, y0, x1, y0 + corner_h),
        (x0, y1 - corner_h, x0 + corner_w, y1),
        (x1 - corner_w, y1 - corner_h, x1, y1),
    )
    transparent = 0
    total = 0
    for rx0, ry0, rx1, ry1 in regions:
        for y in range(max(y0, ry0), min(y1, ry1)):
            base = y * width
            for x in range(max(x0, rx0), min(x1, rx1)):
                total += 1
                if alpha[base + x] <= ALPHA_THRESHOLD:
                    transparent += 1
    return transparent / total if total else 0.0
