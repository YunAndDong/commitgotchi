from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal


ImageStatus = Literal["READY", "FALLBACK"]
StorageKind = Literal["LOCAL", "NONE"]
FallbackReason = Literal[
    "INVALID_DESIGN_KEYWORD",
    "IMAGE_GENERATION_FAILED",
    "IMAGE_GENERATION_TIMEOUT",
    "EMPTY_IMAGE_BYTES",
    "INVALID_PNG",
    "MISSING_ALPHA_CHANNEL",
    "BACKGROUND_REMOVAL_FAILED",
    "GRID_NORMALIZATION_FAILED",
    "QUALITY_GATE_FAILED",
    "LOCAL_STORAGE_FAILED",
]


@dataclass(frozen=True)
class SpriteMetadata:
    columns: int = 3
    rows: int = 1
    transparent: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "columns": self.columns,
            "rows": self.rows,
            "frameMap": {
                "joy": [0, 0],
                "happy": [0, 0],
                "sad": [0, 1],
                "angry": [0, 2],
            },
            "transparent": self.transparent,
        }


@dataclass(frozen=True)
class CharacterImageResult:
    image_status: ImageStatus
    storage_kind: StorageKind
    local_path: str | None = None
    content_type: str | None = None
    sprite_meta: SpriteMetadata | None = None
    fallback_reason: FallbackReason | None = None

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "imageStatus": self.image_status,
            "storageKind": self.storage_kind,
            "spriteMeta": (
                self.sprite_meta.to_dict() if self.sprite_meta is not None else None
            ),
        }
        if self.local_path is not None:
            payload["localPath"] = self.local_path
        if self.content_type is not None:
            payload["contentType"] = self.content_type
        if self.fallback_reason is not None:
            payload["fallbackReason"] = self.fallback_reason
        return payload


def ready_image_result(*, local_path: str, content_type: str = "image/png") -> CharacterImageResult:
    return CharacterImageResult(
        image_status="READY",
        storage_kind="LOCAL",
        local_path=local_path,
        content_type=content_type,
        sprite_meta=SpriteMetadata(),
    )


def fallback_image_result(reason: FallbackReason) -> CharacterImageResult:
    return CharacterImageResult(
        image_status="FALLBACK",
        storage_kind="NONE",
        fallback_reason=reason,
        sprite_meta=None,
    )
