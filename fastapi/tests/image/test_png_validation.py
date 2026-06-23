from __future__ import annotations

import unittest

from tests.image.png_fixtures import (
    make_png_bytes,
    make_png_with_bad_ihdr_crc,
    make_png_with_invalid_idat,
    make_png_without_idat,
)


class PngValidationTest(unittest.TestCase):
    def test_valid_transparent_png_is_accepted(self) -> None:
        from app.image.png_validation import validate_transparent_png

        metadata = validate_transparent_png(make_png_bytes(width=96, height=64, color_type=6))

        self.assertEqual(metadata.width, 96)
        self.assertEqual(metadata.height, 64)
        self.assertTrue(metadata.has_alpha)

    def test_invalid_signature_is_rejected(self) -> None:
        from app.image.png_validation import PngValidationError, validate_transparent_png

        with self.assertRaises(PngValidationError) as context:
            validate_transparent_png(b"not-a-png")

        self.assertEqual(context.exception.reason, "INVALID_PNG")

    def test_png_without_alpha_is_rejected(self) -> None:
        from app.image.png_validation import PngValidationError, validate_transparent_png

        with self.assertRaises(PngValidationError) as context:
            validate_transparent_png(make_png_bytes(width=96, height=64, color_type=2))

        self.assertEqual(context.exception.reason, "MISSING_ALPHA_CHANNEL")

    def test_zero_dimensions_are_rejected(self) -> None:
        from app.image.png_validation import PngValidationError, validate_transparent_png

        with self.assertRaises(PngValidationError) as context:
            validate_transparent_png(make_png_bytes(width=0, height=2, color_type=6))

        self.assertEqual(context.exception.reason, "INVALID_PNG")

    def test_non_frame_splittable_dimensions_are_rejected(self) -> None:
        from app.image.png_validation import PngValidationError, validate_transparent_png

        for png_bytes in (
            make_png_bytes(width=1, height=1, color_type=6),
            make_png_bytes(width=95, height=64, color_type=6),
            make_png_bytes(width=96, height=17, color_type=6),
        ):
            with self.subTest(length=len(png_bytes)):
                with self.assertRaises(PngValidationError) as context:
                    validate_transparent_png(png_bytes)
                self.assertEqual(context.exception.reason, "INVALID_PNG")

    def test_png_without_idat_is_rejected(self) -> None:
        from app.image.png_validation import PngValidationError, validate_transparent_png

        with self.assertRaises(PngValidationError) as context:
            validate_transparent_png(make_png_without_idat())

        self.assertEqual(context.exception.reason, "INVALID_PNG")

    def test_bad_crc_is_rejected(self) -> None:
        from app.image.png_validation import PngValidationError, validate_transparent_png

        with self.assertRaises(PngValidationError) as context:
            validate_transparent_png(make_png_with_bad_ihdr_crc())

        self.assertEqual(context.exception.reason, "INVALID_PNG")

    def test_invalid_idat_payload_is_rejected(self) -> None:
        from app.image.png_validation import PngValidationError, validate_transparent_png

        with self.assertRaises(PngValidationError) as context:
            validate_transparent_png(make_png_with_invalid_idat())

        self.assertEqual(context.exception.reason, "INVALID_PNG")

    def test_unextractable_dimensions_are_rejected(self) -> None:
        from app.image.png_validation import PngValidationError, validate_transparent_png

        with self.assertRaises(PngValidationError) as context:
            validate_transparent_png(b"\x89PNG\r\n\x1a\n" + b"broken")

        self.assertEqual(context.exception.reason, "INVALID_PNG")


if __name__ == "__main__":
    unittest.main()
