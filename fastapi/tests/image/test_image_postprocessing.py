from __future__ import annotations

import io
import unittest

from PIL import Image

from app.image.background_removal import remove_background_to_alpha
from app.image.frame_normalizer import normalize_sprite_grid
from app.image.png_validation import validate_transparent_png
from app.image.quality_gate import evaluate_sprite_quality
from tests.image.png_fixtures import make_sprite_sheet_png_bytes


def _rgba(png_bytes: bytes) -> Image.Image:
    return Image.open(io.BytesIO(png_bytes)).convert("RGBA")


class BackgroundRemovalTest(unittest.TestCase):
    def test_solid_background_becomes_transparent_and_interior_black_is_preserved(self) -> None:
        sheet = make_sprite_sheet_png_bytes(with_alpha=False)
        result = remove_background_to_alpha(sheet)
        image = _rgba(result.png_bytes)

        self.assertEqual(image.getpixel((0, 0))[3], 0)  # corner background transparent
        self.assertGreater(result.transparent_ratio, 0.4)
        # an interior black pixel stays opaque (alpha kept, near-black rgb)
        raw = image.tobytes()
        opaque_black = any(
            raw[i + 3] > 16 and raw[i] < 40 and raw[i + 1] < 40 and raw[i + 2] < 40
            for i in range(0, len(raw), 4)
        )
        self.assertTrue(opaque_black)

    def test_deterministic_output(self) -> None:
        sheet = make_sprite_sheet_png_bytes()
        self.assertEqual(
            remove_background_to_alpha(sheet).png_bytes,
            remove_background_to_alpha(sheet).png_bytes,
        )


class FrameNormalizerTest(unittest.TestCase):
    def test_six_sprites_normalize_into_uniform_validatable_grid(self) -> None:
        bg = remove_background_to_alpha(make_sprite_sheet_png_bytes()).png_bytes
        result = normalize_sprite_grid(bg)

        self.assertTrue(result.success)
        self.assertEqual((result.columns, result.rows), (3, 2))
        meta = validate_transparent_png(result.png_bytes)
        self.assertEqual(meta.width, result.cell_width * 3)
        self.assertEqual(meta.height, result.cell_height * 2)
        self.assertTrue(meta.has_alpha)

    def test_missing_sprite_fails_normalization(self) -> None:
        bg = remove_background_to_alpha(
            make_sprite_sheet_png_bytes(sprite_count=5)
        ).png_bytes
        result = normalize_sprite_grid(bg)
        self.assertFalse(result.success)
        self.assertIsNotNone(result.reason)

    def test_small_noise_band_is_ignored(self) -> None:
        # add a 2px detached speckle near the top, simulating anger marks
        bg = remove_background_to_alpha(make_sprite_sheet_png_bytes()).png_bytes
        image = _rgba(bg)
        for x in range(5, 7):
            image.putpixel((x, 1), (10, 10, 10, 255))
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        result = normalize_sprite_grid(buffer.getvalue())
        self.assertTrue(result.success)


class QualityGateTest(unittest.TestCase):
    def _normalized(self, **kwargs):
        bg = remove_background_to_alpha(make_sprite_sheet_png_bytes(**kwargs)).png_bytes
        return normalize_sprite_grid(bg)

    def test_clean_grid_passes(self) -> None:
        norm = self._normalized()
        gate = evaluate_sprite_quality(
            norm.png_bytes,
            columns=norm.columns,
            rows=norm.rows,
            cell_width=norm.cell_width,
            cell_height=norm.cell_height,
        )
        self.assertTrue(gate.passed, gate.reason)

    def test_panel_background_is_flagged(self) -> None:
        norm = self._normalized(panels=True)
        self.assertTrue(norm.success)  # panels still segment into a grid
        gate = evaluate_sprite_quality(
            norm.png_bytes,
            columns=norm.columns,
            rows=norm.rows,
            cell_width=norm.cell_width,
            cell_height=norm.cell_height,
        )
        self.assertFalse(gate.passed)
        self.assertEqual(gate.reason, "panel_background")


if __name__ == "__main__":
    unittest.main()
