from __future__ import annotations

import struct
import zlib
from binascii import crc32
from dataclasses import dataclass
from typing import Literal


PngValidationReason = Literal[
    "EMPTY_IMAGE_BYTES",
    "INVALID_PNG",
    "MISSING_ALPHA_CHANNEL",
]
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SPRITE_COLUMNS = 3
SPRITE_ROWS = 1
MIN_FRAME_DIMENSION_PX = 18

_VALID_BIT_DEPTHS_BY_COLOR_TYPE = {
    0: {1, 2, 4, 8, 16},
    2: {8, 16},
    3: {1, 2, 4, 8},
    4: {8, 16},
    6: {8, 16},
}
_CHANNELS_BY_COLOR_TYPE = {
    0: 1,
    2: 3,
    3: 1,
    4: 2,
    6: 4,
}


@dataclass(frozen=True)
class PngMetadata:
    width: int
    height: int
    bit_depth: int
    color_type: int
    has_alpha: bool


class PngValidationError(ValueError):
    def __init__(self, reason: PngValidationReason) -> None:
        self.reason = reason
        super().__init__(reason)


def validate_transparent_png(png_bytes: bytes | bytearray | memoryview) -> PngMetadata:
    data = bytes(png_bytes)
    if not data:
        raise PngValidationError("EMPTY_IMAGE_BYTES")
    if len(data) < len(PNG_SIGNATURE) or not data.startswith(PNG_SIGNATURE):
        raise PngValidationError("INVALID_PNG")

    offset = len(PNG_SIGNATURE)
    width: int | None = None
    height: int | None = None
    bit_depth: int | None = None
    color_type: int | None = None
    has_alpha = False
    saw_ihdr = False
    saw_idat = False
    saw_iend = False
    idat_chunks: list[bytes] = []

    while offset < len(data):
        if offset + 8 > len(data):
            raise PngValidationError("INVALID_PNG")

        chunk_length = struct.unpack(">I", data[offset : offset + 4])[0]
        chunk_type = data[offset + 4 : offset + 8]
        chunk_data_start = offset + 8
        chunk_data_end = chunk_data_start + chunk_length
        crc_end = chunk_data_end + 4
        if crc_end > len(data):
            raise PngValidationError("INVALID_PNG")
        chunk_data = data[chunk_data_start:chunk_data_end]
        expected_crc = struct.unpack(">I", data[chunk_data_end:crc_end])[0]
        actual_crc = crc32(chunk_type + chunk_data) & 0xFFFFFFFF
        if expected_crc != actual_crc:
            raise PngValidationError("INVALID_PNG")

        if not saw_ihdr and chunk_type != b"IHDR":
            raise PngValidationError("INVALID_PNG")

        if chunk_type == b"IHDR":
            if saw_ihdr or chunk_length != 13:
                raise PngValidationError("INVALID_PNG")
            width, height, bit_depth, color_type, compression, filter_method, interlace = (
                struct.unpack(">IIBBBBB", chunk_data)
            )
            if width <= 0 or height <= 0:
                raise PngValidationError("INVALID_PNG")
            if color_type not in _VALID_BIT_DEPTHS_BY_COLOR_TYPE:
                raise PngValidationError("INVALID_PNG")
            if bit_depth not in _VALID_BIT_DEPTHS_BY_COLOR_TYPE[color_type]:
                raise PngValidationError("INVALID_PNG")
            if compression != 0 or filter_method != 0 or interlace != 0:
                raise PngValidationError("INVALID_PNG")
            _validate_sprite_sheet_dimensions(width, height)
            has_alpha = color_type in {4, 6}
            saw_ihdr = True
        elif chunk_type == b"IDAT":
            if not saw_ihdr or saw_iend:
                raise PngValidationError("INVALID_PNG")
            saw_idat = True
            idat_chunks.append(chunk_data)
        elif chunk_type == b"tRNS":
            if not saw_ihdr or saw_idat or color_type not in {0, 2, 3}:
                raise PngValidationError("INVALID_PNG")
            has_alpha = True
        elif chunk_type == b"IEND":
            saw_iend = True
            break

        offset = crc_end

    if (
        not saw_ihdr
        or not saw_idat
        or not saw_iend
        or width is None
        or height is None
        or bit_depth is None
        or color_type is None
    ):
        raise PngValidationError("INVALID_PNG")
    if not has_alpha:
        raise PngValidationError("MISSING_ALPHA_CHANNEL")
    _validate_idat_payload(
        idat_chunks,
        width=width,
        height=height,
        bit_depth=bit_depth,
        color_type=color_type,
    )
    return PngMetadata(
        width=width,
        height=height,
        bit_depth=bit_depth,
        color_type=color_type,
        has_alpha=has_alpha,
    )


def _validate_sprite_sheet_dimensions(width: int, height: int) -> None:
    if width % SPRITE_COLUMNS != 0 or height % SPRITE_ROWS != 0:
        raise PngValidationError("INVALID_PNG")
    if width // SPRITE_COLUMNS < MIN_FRAME_DIMENSION_PX:
        raise PngValidationError("INVALID_PNG")
    if height // SPRITE_ROWS < MIN_FRAME_DIMENSION_PX:
        raise PngValidationError("INVALID_PNG")


def _validate_idat_payload(
    idat_chunks: list[bytes],
    *,
    width: int,
    height: int,
    bit_depth: int,
    color_type: int,
) -> None:
    if not idat_chunks:
        raise PngValidationError("INVALID_PNG")
    try:
        decompressed = zlib.decompress(b"".join(idat_chunks))
    except zlib.error as exc:
        raise PngValidationError("INVALID_PNG") from exc

    channels = _CHANNELS_BY_COLOR_TYPE[color_type]
    bits_per_scanline = width * channels * bit_depth
    bytes_per_scanline = (bits_per_scanline + 7) // 8
    expected_size = height * (bytes_per_scanline + 1)
    if len(decompressed) != expected_size:
        raise PngValidationError("INVALID_PNG")
