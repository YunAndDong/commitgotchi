from __future__ import annotations

from typing import Any
from urllib.parse import urlsplit

from .local_storage import StoredImage


class S3StorageError(ValueError):
    """Raised when an s3:// destination is missing or malformed."""


class S3SpriteImageStorage:
    """Uploads a sprite sheet straight from memory to the S3 location Spring
    Boot chose (the request's s3ObjectUrl). No local file is written.

    Credentials come from the boto3 default chain (local AWS profile / prod EC2
    instance role); no static keys are required. The S3 client is created lazily
    so importing this module never needs boto3 or AWS config, and a fake client
    can be injected for tests.
    """

    def __init__(
        self,
        *,
        s3_object_url: str,
        region: str,
        client: Any | None = None,
    ) -> None:
        self._bucket, self._key = _parse_s3_url(s3_object_url)
        self._s3_object_url = f"s3://{self._bucket}/{self._key}"
        self._region = region
        self._client = client

    def save_png(
        self,
        png_bytes: bytes,
        *,
        user_id: int | str | None = None,
        image_request_id: str | None = None,
    ) -> StoredImage:
        client = self._client or _create_s3_client(self._region)
        client.put_object(
            Bucket=self._bucket,
            Key=self._key,
            Body=png_bytes,
            ContentType="image/png",
        )
        # local_path carries the stable s3:// location. Spring ignores the
        # returned URL for persistence (it stores its own objectLocation) but
        # requires a non-empty spriteSheetUrl to treat the result as success.
        return StoredImage(
            local_path=self._s3_object_url,
            absolute_path=self._s3_object_url,
            content_type="image/png",
            size_bytes=len(png_bytes),
        )


def _parse_s3_url(s3_object_url: str | None) -> tuple[str, str]:
    if not s3_object_url or not str(s3_object_url).strip():
        raise S3StorageError("s3 backend requires a non-empty s3ObjectUrl")
    parts = urlsplit(str(s3_object_url).strip())
    if parts.scheme != "s3":
        raise S3StorageError("s3ObjectUrl must use the s3:// scheme")
    bucket = parts.netloc
    key = parts.path.lstrip("/")
    if not bucket or not key:
        raise S3StorageError("s3ObjectUrl must be s3://bucket/key")
    # Defensive: the key comes from Spring (characterId-based), never from user
    # input, but reject traversal/control just in case.
    if ".." in key.split("/") or key.startswith("/") or "\x00" in key:
        raise S3StorageError("s3ObjectUrl key is not allowed")
    return bucket, key


def _create_s3_client(region: str) -> Any:
    import boto3

    return boto3.client("s3", region_name=region)
