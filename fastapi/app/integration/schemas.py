from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping


@dataclass(frozen=True)
class SpringCallbackResponse:
    status_code: int
    body: Mapping[str, Any] | None = None


@dataclass(frozen=True)
class SpringCallbackResult:
    status_code: int | None
    ok: bool
    retryable: bool
    body: Mapping[str, Any] | None = None
    error: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "statusCode": self.status_code,
            "ok": self.ok,
            "retryable": self.retryable,
            "body": dict(self.body) if self.body is not None else None,
            "error": self.error,
        }
