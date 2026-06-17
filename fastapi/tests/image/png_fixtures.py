from __future__ import annotations

import struct
import zlib
from binascii import crc32


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def make_png_bytes(
    *,
    width: int = 3,
    height: int = 2,
    color_type: int = 6,
    include_iend: bool = True,
) -> bytes:
    channels = {2: 3, 6: 4}[color_type]
    raw_scanline = bytes([0]) + bytes([0] * width * channels)
    image_data = raw_scanline * height
    ihdr = struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)
    chunks = [
        _chunk(b"IHDR", ihdr),
        _chunk(b"IDAT", zlib.compress(image_data)),
    ]
    if include_iend:
        chunks.append(_chunk(b"IEND", b""))
    return PNG_SIGNATURE + b"".join(chunks)


def make_png_without_idat(
    *,
    width: int = 96,
    height: int = 64,
    color_type: int = 6,
) -> bytes:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)
    return PNG_SIGNATURE + _chunk(b"IHDR", ihdr) + _chunk(b"IEND", b"")


def make_png_with_bad_ihdr_crc(
    *,
    width: int = 96,
    height: int = 64,
    color_type: int = 6,
) -> bytes:
    channels = {2: 3, 6: 4}[color_type]
    raw_scanline = bytes([0]) + bytes([0] * width * channels)
    image_data = raw_scanline * height
    ihdr = struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)
    bad_ihdr = struct.pack(">I", len(ihdr)) + b"IHDR" + ihdr + b"\x00\x00\x00\x00"
    return (
        PNG_SIGNATURE
        + bad_ihdr
        + _chunk(b"IDAT", zlib.compress(image_data))
        + _chunk(b"IEND", b"")
    )


def make_png_with_invalid_idat(
    *,
    width: int = 96,
    height: int = 64,
    color_type: int = 6,
) -> bytes:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)
    return (
        PNG_SIGNATURE
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", b"not-zlib")
        + _chunk(b"IEND", b"")
    )


def make_sprite_sheet_png_bytes(
    *,
    cell: int = 80,
    bg_color: tuple[int, int, int] = (255, 0, 255),
    with_alpha: bool = False,
    panels: bool = False,
    sprite_count: int = 6,
) -> bytes:
    """Build a synthetic 2x3 creature sprite sheet for post-processing tests.

    Defaults to a solid magenta background with six centered blobs (with interior
    black detail) separated by gaps, which survives background removal + grid
    normalization + quality gate. Options reproduce known failure shapes:

    - ``panels=True``: each cell is filled by an opaque backdrop -> quality gate
      should flag ``panel_background``.
    - ``sprite_count<6``: a missing creature -> grid normalization should fail.
    - ``with_alpha=False``: RGB sheet with no alpha -> pipeline must add alpha.
    """

    import io

    from PIL import Image, ImageDraw

    columns, rows = 3, 2
    width, height = cell * columns, cell * rows
    mode = "RGBA" if with_alpha else "RGB"
    base_bg = bg_color + ((255,) if with_alpha else ())
    image = Image.new(mode, (width, height), base_bg)
    draw = ImageDraw.Draw(image)

    body_colors = [
        (60, 200, 90), (70, 180, 220), (230, 120, 60),
        (90, 210, 120), (80, 160, 230), (240, 140, 80),
    ]
    opaque = (255,) if with_alpha else ()
    drawn = 0
    for index in range(columns * rows):
        if drawn >= sprite_count:
            break
        row, col = index // columns, index % columns
        cx, cy = col * cell + cell // 2, row * cell + cell // 2
        color = body_colors[index % len(body_colors)]
        if panels:
            margin = max(3, cell // 16)
            draw.rectangle(
                (col * cell + margin, row * cell + margin,
                 (col + 1) * cell - margin, (row + 1) * cell - margin),
                fill=color + opaque,
            )
        else:
            r = cell // 3
            draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=color + opaque,
                         outline=(0, 0, 0) + opaque, width=2)
            draw.ellipse((cx - r // 2, cy - r // 3, cx - r // 5, cy), fill=(0, 0, 0) + opaque)
            draw.ellipse((cx + r // 5, cy - r // 3, cx + r // 2, cy), fill=(0, 0, 0) + opaque)
        drawn += 1

    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def _chunk(chunk_type: bytes, data: bytes) -> bytes:
    checksum = crc32(chunk_type + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", checksum)
