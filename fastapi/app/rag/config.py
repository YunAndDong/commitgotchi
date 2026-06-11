from __future__ import annotations

import os
import unicodedata
from dataclasses import dataclass
from pathlib import Path


def _nfc(value: str) -> str:
    return unicodedata.normalize("NFC", value)


def resolve_default_source_root() -> Path:
    env_value = os.getenv("RAG_SOURCE_ROOT")
    if env_value:
        return Path(env_value).expanduser()

    candidate = Path.home() / "study" / "md파일들"
    if candidate.exists():
        return candidate

    study_dir = Path.home() / "study"
    if study_dir.exists():
        for child in study_dir.iterdir():
            normalized = _nfc(child.name)
            if child.is_dir() and "md" in normalized.lower() and "파일" in normalized:
                return child

    return candidate


@dataclass(frozen=True)
class RagPreviewSettings:
    source_root: Path
    max_concepts: int = 4
    max_quizzes: int = 3
    min_problem_score: float = 3.5
    min_concept_score: float = 2.0

    @classmethod
    def from_env(cls) -> "RagPreviewSettings":
        return cls(source_root=resolve_default_source_root())
