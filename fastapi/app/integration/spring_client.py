from __future__ import annotations

import json
from typing import Any, Mapping, Protocol
from urllib.error import HTTPError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen

from pydantic import SecretStr

from app.config import Settings, settings as default_settings

from .schemas import SpringCallbackResponse, SpringCallbackResult
from .spring_payloads import (
    build_quiz_grade_result_callback_payload,
    build_report_callback_payload,
)


INTERNAL_AUTH_HEADER_NAME = "Authorization"
INTERNAL_AUTH_SCHEME = "Internal"
JSON_CONTENT_TYPE = "application/json"


class SpringCallbackTransport(Protocol):
    def post_json(
        self,
        url: str,
        *,
        headers: Mapping[str, str],
        payload: Mapping[str, Any],
        timeout_seconds: float,
    ) -> SpringCallbackResponse:
        ...


class UrllibSpringCallbackTransport:
    def post_json(
        self,
        url: str,
        *,
        headers: Mapping[str, str],
        payload: Mapping[str, Any],
        timeout_seconds: float,
    ) -> SpringCallbackResponse:
        encoded_payload = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = Request(
            url,
            data=encoded_payload,
            headers=dict(headers),
            method="POST",
        )
        try:
            with urlopen(request, timeout=timeout_seconds) as response:
                return SpringCallbackResponse(
                    status_code=response.getcode(),
                    body=_decode_response_body(response.read()),
                )
        except HTTPError as exc:
            return SpringCallbackResponse(
                status_code=exc.code,
                body=_decode_response_body(exc.read()),
            )


class SpringCallbackClient:
    def __init__(
        self,
        *,
        settings: Settings | None = None,
        transport: SpringCallbackTransport | None = None,
    ) -> None:
        self._settings = settings or default_settings
        self._transport = transport or UrllibSpringCallbackTransport()

    def post_report_result(
        self,
        payload: Mapping[str, Any],
    ) -> SpringCallbackResult:
        return self._post(
            path=self._settings.spring_report_callback_path,
            payload=payload,
        )

    def post_quiz_grade_result(
        self,
        payload: Mapping[str, Any],
    ) -> SpringCallbackResult:
        return self._post(
            path=self._settings.spring_quiz_grade_result_path,
            payload=payload,
        )

    def send_report_callback(
        self,
        *,
        request_id: str,
        user_id: int,
        character_id: int,
        target_date: str,
        report_result: Mapping[str, Any],
    ) -> SpringCallbackResult:
        return self.post_report_result(
            build_report_callback_payload(
                request_id=request_id,
                user_id=user_id,
                character_id=character_id,
                target_date=target_date,
                report_result=report_result,
            )
        )

    def send_quiz_grade_result(
        self,
        *,
        user_id: int,
        character_id: int,
        quiz_id: int,
        grading_result: Mapping[str, Any],
        emotion: str | None = None,
        status_message: str | None = None,
        failed_reason: str | None = None,
    ) -> SpringCallbackResult:
        return self.post_quiz_grade_result(
            build_quiz_grade_result_callback_payload(
                user_id=user_id,
                character_id=character_id,
                quiz_id=quiz_id,
                grading_result=grading_result,
                emotion=emotion,
                status_message=status_message,
                failed_reason=failed_reason,
            )
        )

    def _post(
        self,
        *,
        path: str,
        payload: Mapping[str, Any],
    ) -> SpringCallbackResult:
        url = build_spring_callback_url(
            base_url=self._settings.spring_boot_internal_base_url,
            path=path,
        )
        headers = {
            "Content-Type": JSON_CONTENT_TYPE,
            **build_internal_auth_headers(self._settings.spring_internal_api_secret),
        }
        try:
            response = self._transport.post_json(
                url,
                headers=headers,
                payload=payload,
                timeout_seconds=self._settings.spring_callback_timeout_seconds,
            )
        except TimeoutError:
            return SpringCallbackResult(
                status_code=None,
                ok=False,
                retryable=True,
                error="spring callback timeout",
            )
        except (ConnectionError, OSError):
            return SpringCallbackResult(
                status_code=None,
                ok=False,
                retryable=True,
                error="spring callback connection error",
            )

        return classify_spring_callback_response(response)


def build_spring_callback_url(*, base_url: str, path: str) -> str:
    origin = _normalize_origin(base_url)
    callback_path = _normalize_callback_path(path)
    return origin + callback_path


def build_internal_auth_headers(secret: SecretStr | str | None) -> dict[str, str]:
    secret_value = _secret_value(secret)
    if not secret_value:
        return {}
    return {
        INTERNAL_AUTH_HEADER_NAME: f"{INTERNAL_AUTH_SCHEME} {secret_value}",
    }


def classify_spring_callback_response(
    response: SpringCallbackResponse,
) -> SpringCallbackResult:
    status_code = response.status_code
    body = dict(response.body) if response.body is not None else None
    if status_code == 200:
        return SpringCallbackResult(
            status_code=status_code,
            ok=True,
            retryable=False,
            body=body,
        )
    if 500 <= status_code:
        return SpringCallbackResult(
            status_code=status_code,
            ok=False,
            retryable=True,
            body=body,
            error="spring callback server error",
        )
    if 400 <= status_code < 500:
        return SpringCallbackResult(
            status_code=status_code,
            ok=False,
            retryable=False,
            body=body,
            error="spring callback client error",
        )
    return SpringCallbackResult(
        status_code=status_code,
        ok=False,
        retryable=False,
        body=body,
        error="spring callback contract mismatch",
    )


def _secret_value(secret: SecretStr | str | None) -> str:
    if secret is None:
        return ""
    if isinstance(secret, SecretStr):
        return secret.get_secret_value().strip()
    return str(secret).strip()


def _normalize_origin(value: str) -> str:
    stripped = str(value).strip()
    if not stripped:
        raise ValueError("Spring callback base URL is required")

    parts = urlsplit(stripped)
    if parts.scheme not in {"http", "https"} or not parts.netloc:
        raise ValueError("Spring callback base URL must be an http(s) origin")
    if parts.username or parts.password:
        raise ValueError("Spring callback base URL must not include user info")
    try:
        parts.port
    except ValueError as exc:
        raise ValueError("Spring callback base URL has an invalid port") from exc
    if parts.path not in {"", "/"} or parts.query or parts.fragment:
        raise ValueError(
            "Spring callback base URL must not include path, query, or fragment"
        )
    return urlunsplit((parts.scheme, parts.netloc, "", "", ""))


def _normalize_callback_path(value: str) -> str:
    stripped = str(value).strip()
    if not stripped:
        raise ValueError("Spring callback path is required")

    parts = urlsplit(stripped)
    if parts.scheme or parts.netloc:
        raise ValueError("Spring callback path must not be an absolute URL")
    if parts.query or parts.fragment:
        raise ValueError("Spring callback path must not include query or fragment")

    path = "/" + stripped.lstrip("/")
    segments = [segment for segment in path.split("/") if segment]
    if not segments:
        raise ValueError("Spring callback path must not be empty")
    return "/" + "/".join(segments)


def _decode_response_body(raw_body: bytes) -> Mapping[str, Any] | None:
    if not raw_body:
        return None
    try:
        payload = json.loads(raw_body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    return payload if isinstance(payload, Mapping) else None
