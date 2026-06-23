from __future__ import annotations

import hmac
from typing import Any, Callable

from fastapi import APIRouter, Depends, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator

from app.config import Settings, settings as default_settings
from app.image.schemas import CharacterImageResult
from app.image.sprite_service import generate_commitgotchi_sprite
from app.integration.spring_client import INTERNAL_AUTH_SCHEME


# Story character-image-2 (Option A): the endpoint honors architecture §4.4.
# Spring Boot's FastApiCharacterImageClient sends {userId, s3ObjectUrl, prompt}
# where `prompt` carries the design keyword (Spring no longer builds prompts),
# and expects {status: "OK"|"FAIL", spriteSheetUrl, spriteMeta} back.
SpriteGenerator = Callable[..., CharacterImageResult]


class CommitgotchiImageRequest(BaseModel):
    userId: int = Field(gt=0)
    # §4.4 `prompt` carries the design keyword. FastAPI owns prompt building.
    prompt: str = Field(min_length=1)
    # Received for contract parity. NOT trusted as a storage destination in the
    # MVP local flow; real S3 upload arrives in story character-image-3.
    s3ObjectUrl: str | None = None

    model_config = ConfigDict(extra="ignore")

    @field_validator("prompt")
    @classmethod
    def _required_text(cls, value: str) -> str:
        stripped = str(value).strip()
        if not stripped:
            raise ValueError("prompt must not be blank")
        return stripped


router = APIRouter(prefix="/api/ai", tags=["character-image"])


def get_settings() -> Settings:
    return default_settings


def get_sprite_generator() -> SpriteGenerator:
    return generate_commitgotchi_sprite


def require_internal_auth(
    authorization: str | None = Header(default=None, alias="Authorization"),
    settings: Settings = Depends(get_settings),
) -> None:
    expected_token = _secret_value(settings.spring_internal_api_secret)
    if not expected_token:
        return

    received_token = _extract_internal_token(authorization)
    if received_token is None or not hmac.compare_digest(received_token, expected_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid internal authorization",
        )


@router.post("/commitgotchi")
def create_commitgotchi_image(
    request: CommitgotchiImageRequest,
    _: None = Depends(require_internal_auth),
    generator: SpriteGenerator = Depends(get_sprite_generator),
) -> dict[str, Any]:
    result = generator(design_keyword=request.prompt, user_id=request.userId)
    return _to_contract_response(
        result,
        user_id=request.userId,
        s3_object_url=request.s3ObjectUrl,
    )


def _to_contract_response(
    result: CharacterImageResult,
    *,
    user_id: int,
    s3_object_url: str | None,
) -> dict[str, Any]:
    """Adapt the local core result to the architecture §4.4 response shape."""
    if result.image_status == "READY" and result.local_path and result.sprite_meta is not None:
        return {
            "userId": user_id,
            "status": "OK",
            "s3ObjectUrl": s3_object_url,
            # MVP: local URL-compatible path until S3 upload (story-3).
            "spriteSheetUrl": result.local_path,
            "spriteMeta": result.sprite_meta.to_dict(),
        }
    return {
        "userId": user_id,
        "status": "FAIL",
        "s3ObjectUrl": s3_object_url,
        "spriteSheetUrl": None,
        "spriteMeta": None,
        "errorMessage": result.fallback_reason or "IMAGE_GENERATION_FAILED",
    }


def _extract_internal_token(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, separator, token = authorization.partition(" ")
    if not separator or scheme != INTERNAL_AUTH_SCHEME:
        return None
    stripped = token.strip()
    return stripped or None


def _secret_value(secret: SecretStr | str | None) -> str:
    if secret is None:
        return ""
    if isinstance(secret, SecretStr):
        return secret.get_secret_value().strip()
    return str(secret).strip()
