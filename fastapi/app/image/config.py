from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from pydantic import SecretStr

from app.config import Settings


DEFAULT_GEMINI_IMAGE_MODEL = "gemini-2.5-flash-image"
FASTAPI_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = FASTAPI_ROOT.parent
FASTAPI_CHARACTER_IMAGE_ROOT = FASTAPI_ROOT / "runtime" / "data" / "character-images"


@dataclass(frozen=True, repr=False)
class CharacterImageSettings:
    api_key: SecretStr | str | None
    model: str
    timeout_seconds: float
    retry_limit: int
    storage_root: Path
    storage_backend: str = "local"
    s3_region: str = "ap-northeast-2"

    @classmethod
    def from_app_settings(
        cls,
        settings: Settings | None = None,
    ) -> "CharacterImageSettings":
        app_settings = settings or Settings()
        return cls(
            api_key=app_settings.gemini_api_key,
            model=app_settings.gemini_image_model or DEFAULT_GEMINI_IMAGE_MODEL,
            timeout_seconds=app_settings.gemini_image_timeout_seconds,
            retry_limit=app_settings.gemini_image_retry_limit,
            storage_root=resolve_character_image_storage_root(
                app_settings.character_image_storage_root
            ),
            storage_backend=app_settings.character_image_storage_backend,
            s3_region=app_settings.aws_region,
        )

    @property
    def api_key_value(self) -> str | None:
        if self.api_key is None:
            return None
        if isinstance(self.api_key, SecretStr):
            value = self.api_key.get_secret_value().strip()
            return value or None
        value = str(self.api_key).strip()
        return value or None

    def __repr__(self) -> str:
        return (
            "CharacterImageSettings("
            "api_key=<redacted>, "
            f"model={self.model!r}, "
            f"timeout_seconds={self.timeout_seconds!r}, "
            f"retry_limit={self.retry_limit!r}, "
            f"storage_root={str(self.storage_root)!r})"
        )


def resolve_character_image_storage_root(root: str | Path) -> Path:
    raw_path = Path(root).expanduser()
    if not raw_path.is_absolute():
        raw_path = FASTAPI_ROOT / raw_path
    resolved = raw_path.resolve(strict=False)
    allowed_root = FASTAPI_CHARACTER_IMAGE_ROOT.resolve(strict=False)
    try:
        resolved.relative_to(allowed_root)
    except ValueError as exc:
        raise ValueError(
            "CHARACTER_IMAGE_STORAGE_ROOT must stay under "
            "fastapi/runtime/data/character-images"
        ) from exc
    return resolved
