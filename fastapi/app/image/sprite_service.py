from __future__ import annotations

from typing import Protocol

from .config import CharacterImageSettings
from .gemini_client import GeminiImageGenerationClient
from .local_storage import LocalSpriteImageStorage, StoredImage
from .png_validation import PngValidationError, validate_transparent_png
from .prompts import DesignKeywordError, build_sprite_prompt
from .schemas import (
    CharacterImageResult,
    FallbackReason,
    fallback_image_result,
    ready_image_result,
)


class ImageGenerationClient(Protocol):
    def generate_png(
        self,
        prompt: str,
        *,
        model: str,
        timeout_seconds: float,
    ) -> bytes:
        ...


class SpriteImageStorage(Protocol):
    def save_png(
        self,
        png_bytes: bytes,
        *,
        user_id: int | str | None = None,
        image_request_id: str | None = None,
    ) -> StoredImage:
        ...


def generate_commitgotchi_sprite(
    *,
    design_keyword: str | None,
    user_id: int | str | None = None,
    image_request_id: str | None = None,
    client: ImageGenerationClient | None = None,
    storage: SpriteImageStorage | None = None,
    settings: CharacterImageSettings | None = None,
) -> CharacterImageResult:
    image_settings = settings or CharacterImageSettings.from_app_settings()
    try:
        prompt = build_sprite_prompt(design_keyword).prompt
    except DesignKeywordError:
        return fallback_image_result("INVALID_DESIGN_KEYWORD")

    try:
        image_client = client or GeminiImageGenerationClient.from_settings(image_settings)
        png_bytes = _generate_with_retries(
            image_client,
            prompt=prompt,
            model=image_settings.model,
            timeout_seconds=image_settings.timeout_seconds,
            retry_limit=image_settings.retry_limit,
        )
    except TimeoutError:
        return fallback_image_result("IMAGE_GENERATION_TIMEOUT")
    except Exception:
        return fallback_image_result("IMAGE_GENERATION_FAILED")

    if not png_bytes:
        return fallback_image_result("EMPTY_IMAGE_BYTES")

    try:
        validate_transparent_png(png_bytes)
    except PngValidationError as exc:
        return fallback_image_result(_png_reason_to_fallback(exc.reason))

    try:
        image_storage = storage or LocalSpriteImageStorage(image_settings.storage_root)
        stored = image_storage.save_png(
            png_bytes,
            user_id=user_id,
            image_request_id=image_request_id,
        )
    except Exception:
        return fallback_image_result("LOCAL_STORAGE_FAILED")

    return ready_image_result(
        local_path=stored.local_path,
        content_type=stored.content_type,
    )


def _generate_with_retries(
    client: ImageGenerationClient,
    *,
    prompt: str,
    model: str,
    timeout_seconds: float,
    retry_limit: int,
) -> bytes:
    attempts = max(0, retry_limit) + 1
    for attempt in range(attempts):
        try:
            return client.generate_png(
                prompt,
                model=model,
                timeout_seconds=timeout_seconds,
            )
        except Exception:
            if attempt == attempts - 1:
                raise
    return b""


def _png_reason_to_fallback(reason: str) -> FallbackReason:
    if reason == "MISSING_ALPHA_CHANNEL":
        return "MISSING_ALPHA_CHANNEL"
    if reason == "EMPTY_IMAGE_BYTES":
        return "EMPTY_IMAGE_BYTES"
    return "INVALID_PNG"
