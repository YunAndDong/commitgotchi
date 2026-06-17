"""Deterministic background removal for generated sprite sheets.

POC implementation for character-image-quality Story 1 (option A).

Strategy: estimate the background color palette from the image border, then
flood-fill the connected background region inward from every border pixel,
setting matched pixels transparent. Only the contiguous border-connected region
is removed, so interior dark details (black outlines, eyes) are preserved even
when they share a color with the background.
"""

from __future__ import annotations

import io
from collections import Counter, deque
from dataclasses import dataclass

from PIL import Image


@dataclass(frozen=True)
class BackgroundRemovalResult:
    png_bytes: bytes
    width: int
    height: int
    removed_pixels: int
    transparent_ratio: float
    background_palette: tuple[tuple[int, int, int], ...]


def remove_background_to_alpha(
    png_bytes: bytes,
    *,
    tolerance: int = 36,
    palette_size: int = 3,
) -> BackgroundRemovalResult:
    """Return a copy of the PNG with the border-connected background made transparent."""

    image = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    width, height = image.size
    raw = bytearray(image.tobytes())  # RGBA, length = width * height * 4

    palette = _estimate_border_palette(raw, width, height, palette_size)
    tol_sq = tolerance * tolerance

    pixel_count = width * height
    visited = bytearray(pixel_count)
    queue: deque[int] = deque()

    def matches_background(index: int) -> bool:
        off = index * 4
        rgb = (raw[off], raw[off + 1], raw[off + 2])
        return any(_distance_sq(rgb, bg) <= tol_sq for bg in palette)

    def enqueue(index: int) -> None:
        if visited[index]:
            return
        if matches_background(index):
            visited[index] = 1
            queue.append(index)

    for x in range(width):
        enqueue(x)                      # top row
        enqueue((height - 1) * width + x)  # bottom row
    for y in range(height):
        enqueue(y * width)              # left column
        enqueue(y * width + (width - 1))   # right column

    removed = 0
    while queue:
        index = queue.popleft()
        raw[index * 4 + 3] = 0  # alpha -> transparent
        removed += 1
        x = index % width
        y = index // width
        if x + 1 < width:
            enqueue(index + 1)
        if x - 1 >= 0:
            enqueue(index - 1)
        if y + 1 < height:
            enqueue(index + width)
        if y - 1 >= 0:
            enqueue(index - width)

    out_image = Image.frombytes("RGBA", (width, height), bytes(raw))
    buffer = io.BytesIO()
    out_image.save(buffer, format="PNG")
    return BackgroundRemovalResult(
        png_bytes=buffer.getvalue(),
        width=width,
        height=height,
        removed_pixels=removed,
        transparent_ratio=removed / pixel_count if pixel_count else 0.0,
        background_palette=palette,
    )


def _estimate_border_palette(
    raw: bytearray,
    width: int,
    height: int,
    palette_size: int,
) -> tuple[tuple[int, int, int], ...]:
    counter: Counter[tuple[int, int, int]] = Counter()

    def sample(index: int) -> None:
        off = index * 4
        # quantize to absorb compression noise while keeping distinct colors apart
        counter[(raw[off] & 0xF8, raw[off + 1] & 0xF8, raw[off + 2] & 0xF8)] += 1

    for x in range(width):
        sample(x)
        sample((height - 1) * width + x)
    for y in range(height):
        sample(y * width)
        sample(y * width + (width - 1))

    if not counter:
        return ((0, 0, 0),)
    return tuple(color for color, _ in counter.most_common(max(1, palette_size)))


def _distance_sq(a: tuple[int, int, int], b: tuple[int, int, int]) -> int:
    dr = a[0] - b[0]
    dg = a[1] - b[1]
    db = a[2] - b[2]
    return dr * dr + dg * dg + db * db
