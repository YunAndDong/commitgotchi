from __future__ import annotations

import unittest
from typing import Any

from app.image.s3_storage import S3SpriteImageStorage, S3StorageError, _parse_s3_url


class FakeS3Client:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    def put_object(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(kwargs)
        return {}


class S3SpriteImageStorageTest(unittest.TestCase):
    def test_uploads_to_spring_provided_location_with_png_content_type(self) -> None:
        client = FakeS3Client()
        storage = S3SpriteImageStorage(
            s3_object_url="s3://commitgotchi-character-images-491013322019/dev/characters/42/sprite-sheet.png",
            region="ap-northeast-2",
            client=client,
        )

        stored = storage.save_png(b"\x89PNG-bytes", user_id=1, image_request_id="req-1")

        self.assertEqual(len(client.calls), 1)
        call = client.calls[0]
        self.assertEqual(call["Bucket"], "commitgotchi-character-images-491013322019")
        self.assertEqual(call["Key"], "dev/characters/42/sprite-sheet.png")
        self.assertEqual(call["ContentType"], "image/png")
        self.assertEqual(call["Body"], b"\x89PNG-bytes")

    def test_returns_stable_s3_location_as_local_path(self) -> None:
        client = FakeS3Client()
        storage = S3SpriteImageStorage(
            s3_object_url="s3://bucket/prod/characters/7/sprite-sheet.png",
            region="ap-northeast-2",
            client=client,
        )

        stored = storage.save_png(b"png")

        # Stable s3:// location (not an expiring URL); Spring persists its own key.
        self.assertEqual(stored.local_path, "s3://bucket/prod/characters/7/sprite-sheet.png")
        self.assertEqual(stored.content_type, "image/png")

    def test_prod_and_dev_prefix_reflected_in_key(self) -> None:
        for prefix in ("dev", "prod"):
            client = FakeS3Client()
            S3SpriteImageStorage(
                s3_object_url=f"s3://bucket/{prefix}/characters/9/sprite-sheet.png",
                region="ap-northeast-2",
                client=client,
            ).save_png(b"png")
            self.assertEqual(client.calls[0]["Key"], f"{prefix}/characters/9/sprite-sheet.png")

    def test_parse_valid_url(self) -> None:
        bucket, key = _parse_s3_url("s3://my-bucket/dev/characters/1/sprite-sheet.png")
        self.assertEqual(bucket, "my-bucket")
        self.assertEqual(key, "dev/characters/1/sprite-sheet.png")

    def test_rejects_malformed_or_unsafe_urls(self) -> None:
        for bad in [
            None,
            "",
            "   ",
            "https://bucket/key",                       # wrong scheme
            "s3://bucket",                              # no key
            "s3:///dev/key",                            # no bucket
            "s3://bucket/dev/../prod/characters/1.png", # traversal
        ]:
            with self.assertRaises(S3StorageError):
                _parse_s3_url(bad)  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
