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


def _chunk(chunk_type: bytes, data: bytes) -> bytes:
    checksum = crc32(chunk_type + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", checksum)
