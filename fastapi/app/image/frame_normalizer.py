"""Frame atlas normalization for generated sprite sheets.

Take a transparent generated sheet, segment the 3 sprites with alpha projection
bands, crop each to its bounding box, and re-place them on a uniform 1x3 atlas
so the frontend can slice frames with a single fixed cell size.

If the projection does not resolve into the expected 1 row x 3 columns, the
caller is told (success=False) and should fall back instead of forcing a broken
grid.
"""

from __future__ import annotations

import io
from dataclasses import dataclass

from PIL import Image


EXPECTED_ROWS = 1
EXPECTED_COLUMNS = 3
ALPHA_THRESHOLD = 16
MIN_CELL_PX = 18  # png_validation MIN_FRAME_DIMENSION_PX
MIN_CELL_PADDING_PX = 4
CELL_PADDING_FRACTION = 0.07
# Detached marks (anger puffs, sweat drops, leftover speckles) produce thin
# projection bands. Bands smaller than this fraction of the axis are treated as
# noise so they do not inflate the row/column count.
MIN_BAND_FRACTION = 0.025
MIN_BAND_FLOOR_PX = 10


@dataclass(frozen=True)
class FrameNormalizationResult:
    success: bool
    png_bytes: bytes
    columns: int
    rows: int
    cell_width: int
    cell_height: int
    reason: str | None = None


def normalize_sprite_grid(png_bytes: bytes) -> FrameNormalizationResult:
    image = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    width, height = image.size
    alpha = image.getchannel("A")
    alpha_bytes = alpha.tobytes()  # length width * height

    row_bands = _bands(
        _projection(alpha_bytes, width, height, axis="row"),
        min_size=_min_band(height),
    )
    if len(row_bands) != EXPECTED_ROWS:
        return _failure(
            png_bytes,
            f"expected {EXPECTED_ROWS} row bands, found {len(row_bands)}",
        )

    cells: list[tuple[int, int, int, int]] = []
    for top, bottom in row_bands:
        col_projection = _projection(
            alpha_bytes, width, height, axis="col", y_range=(top, bottom)
        )
        col_bands = _bands(col_projection, min_size=_min_band(width))
        if len(col_bands) != EXPECTED_COLUMNS:
            return _failure(
                png_bytes,
                f"row band {top}-{bottom}: expected {EXPECTED_COLUMNS} columns, found {len(col_bands)}",
            )
        for left, right in col_bands:
            bbox = _tight_bbox(alpha_bytes, width, left, right, top, bottom)
            if bbox is None:
                return _failure(png_bytes, "empty cell detected during segmentation")
            cells.append(bbox)

    max_sprite_width = max(r - l for (l, _t, r, _b) in cells)
    max_sprite_height = max(b - t for (_l, t, _r, b) in cells)
    padding = max(
        MIN_CELL_PADDING_PX,
        int(max(max_sprite_width, max_sprite_height) * CELL_PADDING_FRACTION),
    )
    cell_size = max(MIN_CELL_PX, max(max_sprite_width, max_sprite_height) + 2 * padding)

    sheet = Image.new(
        "RGBA",
        (cell_size * EXPECTED_COLUMNS, cell_size * EXPECTED_ROWS),
        (0, 0, 0, 0),
    )
    for index, (left, top, right, bottom) in enumerate(cells):
        row = index // EXPECTED_COLUMNS
        col = index % EXPECTED_COLUMNS
        sprite = image.crop((left, top, right, bottom))
        dest_x = col * cell_size + (cell_size - sprite.width) // 2
        dest_y = row * cell_size + cell_size - padding - sprite.height
        sheet.alpha_composite(sprite, (dest_x, dest_y))

    buffer = io.BytesIO()
    sheet.save(buffer, format="PNG")
    return FrameNormalizationResult(
        success=True,
        png_bytes=buffer.getvalue(),
        columns=EXPECTED_COLUMNS,
        rows=EXPECTED_ROWS,
        cell_width=cell_size,
        cell_height=cell_size,
    )


def _projection(
    alpha_bytes: bytes,
    width: int,
    height: int,
    *,
    axis: str,
    y_range: tuple[int, int] | None = None,
) -> list[int]:
    if axis == "row":
        counts = []
        for y in range(height):
            base = y * width
            counts.append(
                sum(1 for x in range(width) if alpha_bytes[base + x] > ALPHA_THRESHOLD)
            )
        return counts
    top, bottom = y_range if y_range is not None else (0, height)
    counts = [0] * width
    for y in range(top, bottom):
        base = y * width
        for x in range(width):
            if alpha_bytes[base + x] > ALPHA_THRESHOLD:
                counts[x] += 1
    return counts


def _bands(projection: list[int], *, min_size: int = 1) -> list[tuple[int, int]]:
    bands: list[tuple[int, int]] = []
    start: int | None = None
    for i, value in enumerate(projection):
        if value > 0 and start is None:
            start = i
        elif value == 0 and start is not None:
            bands.append((start, i))
            start = None
    if start is not None:
        bands.append((start, len(projection)))
    return [(s, e) for (s, e) in bands if e - s >= min_size]


def _min_band(axis_length: int) -> int:
    return max(MIN_BAND_FLOOR_PX, int(axis_length * MIN_BAND_FRACTION))


def _tight_bbox(
    alpha_bytes: bytes,
    width: int,
    left: int,
    right: int,
    top: int,
    bottom: int,
) -> tuple[int, int, int, int] | None:
    min_x = right
    max_x = left
    min_y = bottom
    max_y = top
    found = False
    for y in range(top, bottom):
        base = y * width
        for x in range(left, right):
            if alpha_bytes[base + x] > ALPHA_THRESHOLD:
                found = True
                min_x = min(min_x, x)
                max_x = max(max_x, x)
                min_y = min(min_y, y)
                max_y = max(max_y, y)
    if not found:
        return None
    return (min_x, min_y, max_x + 1, max_y + 1)


def _failure(png_bytes: bytes, reason: str) -> FrameNormalizationResult:
    return FrameNormalizationResult(
        success=False,
        png_bytes=png_bytes,
        columns=EXPECTED_COLUMNS,
        rows=EXPECTED_ROWS,
        cell_width=0,
        cell_height=0,
        reason=reason,
    )
