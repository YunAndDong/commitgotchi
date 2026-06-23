from __future__ import annotations

from typing import Protocol

from .background_removal import remove_background_to_alpha
from .config import CharacterImageSettings
from .frame_normalizer import normalize_sprite_grid
from .gemini_client import GeminiImageGenerationClient
from .local_storage import LocalSpriteImageStorage, StoredImage
from .png_validation import PNG_SIGNATURE, PngValidationError, validate_transparent_png
from .prompts import DesignKeywordError, build_sprite_prompt
from .quality_gate import evaluate_sprite_quality
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

    processed_bytes, post_process_reason = _post_process_sprite_sheet(png_bytes)
    if processed_bytes is None:
        return fallback_image_result(post_process_reason or "INVALID_PNG")

    try:
        validate_transparent_png(processed_bytes)
    except PngValidationError as exc:
        return fallback_image_result(_png_reason_to_fallback(exc.reason))

    try:
        image_storage = storage or LocalSpriteImageStorage(image_settings.storage_root)
        stored = image_storage.save_png(
            processed_bytes,
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


def _post_process_sprite_sheet(
    png_bytes: bytes,
) -> tuple[bytes | None, FallbackReason | None]:
    """Turn a raw generated PNG into a contract-ready transparent 1x3 atlas.

    Returns (processed_bytes, None) on success or (None, reason) when the sheet
    cannot be repaired into a sliceable transparent grid, so the caller falls
    back instead of storing a dirty asset.
    """

    if not png_bytes.startswith(PNG_SIGNATURE):
        return None, "INVALID_PNG"

    try:
        background = remove_background_to_alpha(png_bytes)
    except Exception:
        return None, "BACKGROUND_REMOVAL_FAILED"

    normalized = normalize_sprite_grid(background.png_bytes)
    if not normalized.success:
        return None, "GRID_NORMALIZATION_FAILED"

    gate = evaluate_sprite_quality(
        normalized.png_bytes,
        columns=normalized.columns,
        rows=normalized.rows,
        cell_width=normalized.cell_width,
        cell_height=normalized.cell_height,
    )
    if not gate.passed:
        return None, "QUALITY_GATE_FAILED"

    return normalized.png_bytes, None


def _png_reason_to_fallback(reason: str) -> FallbackReason:
    if reason == "MISSING_ALPHA_CHANNEL":
        return "MISSING_ALPHA_CHANNEL"
    if reason == "EMPTY_IMAGE_BYTES":
        return "EMPTY_IMAGE_BYTES"
    return "INVALID_PNG"
