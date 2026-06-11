from __future__ import annotations

from pathlib import Path

from .text import is_excluded_path, normalize_text, relative_source_path


SUPPORTED_SUFFIXES = {".md", ".csv"}


def iter_source_files(source_root: Path) -> list[Path]:
    if not source_root.exists():
        return []
    files = []
    for path in source_root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() not in SUPPORTED_SUFFIXES:
            continue
        if is_excluded_path(path):
            continue
        files.append(path)
    return sorted(files, key=lambda item: normalize_text(item.as_posix()))


def read_text_file(path: Path) -> str:
    for encoding in ("utf-8-sig", "utf-8", "cp949"):
        try:
            return path.read_text(encoding=encoding)
        except UnicodeDecodeError:
            continue
    return path.read_text(encoding="utf-8", errors="ignore")


def source_type_for(path: Path) -> str:
    if path.suffix.lower() == ".csv":
        return "csv"
    normalized = normalize_text(path.as_posix()).lower()
    if "qna-" in normalized or "/qna" in normalized:
        return "qna"
    if "/quiz/" in normalized or "문제" in normalized:
        return "quiz"
    return "markdown"


def load_sources(source_root: Path) -> list[tuple[Path, str, str]]:
    loaded: list[tuple[Path, str, str]] = []
    for path in iter_source_files(source_root):
        text = read_text_file(path)
        if not text.strip():
            continue
        relative_path = relative_source_path(path, source_root)
        loaded.append((path, relative_path, text))
    return loaded
