from __future__ import annotations

import hashlib
import re
import uuid
from dataclasses import dataclass
from pathlib import Path

from .config import FASTAPI_CHARACTER_IMAGE_ROOT, FASTAPI_ROOT, REPO_ROOT


_SAFE_SEGMENT = re.compile(r"[^A-Za-z0-9_-]+")


@dataclass(frozen=True)
class StoredImage:
    local_path: str
    absolute_path: str
    content_type: str
    size_bytes: int


class LocalSpriteImageStorage:
    def __init__(
        self,
        root: str | Path = FASTAPI_CHARACTER_IMAGE_ROOT,
        *,
        allowed_root: str | Path = FASTAPI_CHARACTER_IMAGE_ROOT,
    ) -> None:
        self.root = _resolve_under_root(root, allowed_root)
        self.allowed_root = _resolve_path(allowed_root)

    def save_png(
        self,
        png_bytes: bytes,
        *,
        user_id: int | str | None = None,
        image_request_id: str | None = None,
    ) -> StoredImage:
        user_segment = _safe_user_segment(user_id)
        filename = _safe_image_filename(image_request_id)
        target = (self.root / "users" / user_segment / filename).resolve(strict=False)
        _assert_under_root(target, self.root)

        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(png_bytes)
        return StoredImage(
            local_path=_display_path(target),
            absolute_path=str(target),
            content_type="image/png",
            size_bytes=len(png_bytes),
        )


def _resolve_path(path: str | Path) -> Path:
    raw = Path(path).expanduser()
    if not raw.is_absolute():
        raw = FASTAPI_ROOT / raw
    return raw.resolve(strict=False)


def _resolve_under_root(path: str | Path, allowed_root: str | Path) -> Path:
    resolved = _resolve_path(path)
    _assert_under_root(resolved, _resolve_path(allowed_root))
    return resolved


def _assert_under_root(path: Path, root: Path) -> None:
    try:
        path.relative_to(root)
    except ValueError as exc:
        raise ValueError("character image path must stay under configured storage root") from exc


def _safe_user_segment(user_id: int | str | None) -> str:
    if user_id is None:
        return "anonymous"
    cleaned = _SAFE_SEGMENT.sub("_", str(user_id).strip()).strip("_-")
    return cleaned[:64] or "anonymous"


def _safe_image_filename(image_request_id: str | None) -> str:
    token_source = (image_request_id or uuid.uuid4().hex).strip() or uuid.uuid4().hex
    token_hash = hashlib.sha256(token_source.encode("utf-8")).hexdigest()[:16]
    random_suffix = uuid.uuid4().hex[:12]
    return f"image-{token_hash}-{random_suffix}.png"


def _display_path(path: Path) -> str:
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()
