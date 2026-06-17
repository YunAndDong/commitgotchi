from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from app.image.local_storage import FASTAPI_CHARACTER_IMAGE_ROOT, LocalSpriteImageStorage
from tests.image.png_fixtures import make_png_bytes


class LocalSpriteImageStorageTest(unittest.TestCase):
    def setUp(self) -> None:
        FASTAPI_CHARACTER_IMAGE_ROOT.mkdir(parents=True, exist_ok=True)
        self.temp_dir = tempfile.TemporaryDirectory(dir=FASTAPI_CHARACTER_IMAGE_ROOT)
        self.root = Path(self.temp_dir.name)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_save_png_stays_under_configured_root(self) -> None:
        storage = LocalSpriteImageStorage(self.root)

        stored = storage.save_png(
            make_png_bytes(),
            user_id=7,
            image_request_id="../outside",
        )

        saved_path = Path(stored.absolute_path)
        self.assertTrue(saved_path.exists())
        self.assertEqual(saved_path.read_bytes(), make_png_bytes())
        saved_path.relative_to(self.root.resolve())
        self.assertIn("/users/7/", saved_path.as_posix())
        self.assertNotIn("..", saved_path.name)

    def test_filename_does_not_include_design_keyword(self) -> None:
        design_keyword = "별의 커비에 나오는 주인공 커비"
        storage = LocalSpriteImageStorage(self.root)

        stored = storage.save_png(
            make_png_bytes(),
            user_id=1,
            image_request_id=design_keyword,
        )

        self.assertNotIn(design_keyword, stored.local_path)
        self.assertTrue(stored.local_path.endswith(".png"))

    def test_storage_root_must_be_under_character_image_runtime_root(self) -> None:
        outside_root = self.root.parents[3] / "not-character-images"

        with self.assertRaises(ValueError):
            LocalSpriteImageStorage(outside_root)


if __name__ == "__main__":
    unittest.main()
