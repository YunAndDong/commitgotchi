from __future__ import annotations

import base64
from typing import Any

from .config import CharacterImageSettings, DEFAULT_GEMINI_IMAGE_MODEL


class GeminiImageGenerationClient:
    def __init__(self, *, api_key: str, default_model: str = DEFAULT_GEMINI_IMAGE_MODEL) -> None:
        if not api_key:
            raise ValueError("GEMINI_API_KEY is required for Gemini image generation")
        self.api_key = api_key
        self.default_model = default_model or DEFAULT_GEMINI_IMAGE_MODEL
        self._client: Any | None = None

    @classmethod
    def from_settings(
        cls,
        settings: CharacterImageSettings,
    ) -> "GeminiImageGenerationClient":
        return cls(
            api_key=settings.api_key_value or "",
            default_model=settings.model,
        )

    def generate_png(
        self,
        prompt: str,
        *,
        model: str,
        timeout_seconds: float,
    ) -> bytes:
        try:
            from google import genai
            from google.genai import types
        except ImportError as exc:
            raise RuntimeError(
                "google-genai package is required for Gemini image generation"
            ) from exc

        if self._client is None:
            self._client = genai.Client(
                api_key=self.api_key,
                http_options=types.HttpOptions(
                    timeout=max(1, int(timeout_seconds * 1000)),
                ),
            )

        response = self._client.models.generate_content(
            model=model or self.default_model,
            contents=prompt,
            config=types.GenerateContentConfig(
                response_modalities=["IMAGE"],
            ),
        )
        return _extract_png_bytes(response)


def _extract_png_bytes(response: Any) -> bytes:
    for part in _iter_response_parts(response):
        inline_data = getattr(part, "inline_data", None) or getattr(part, "inlineData", None)
        if inline_data is None:
            continue
        mime_type = (
            getattr(inline_data, "mime_type", None)
            or getattr(inline_data, "mimeType", None)
            or ""
        )
        if mime_type and mime_type != "image/png":
            continue
        data = getattr(inline_data, "data", None)
        if not data:
            continue
        if isinstance(data, str):
            return base64.b64decode(data)
        return bytes(data)
    return b""


def _iter_response_parts(response: Any):
    direct_parts = getattr(response, "parts", None)
    if direct_parts:
        yield from direct_parts
    for candidate in getattr(response, "candidates", None) or ():
        content = getattr(candidate, "content", None)
        parts = getattr(content, "parts", None)
        if parts:
            yield from parts
