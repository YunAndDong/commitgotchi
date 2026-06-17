from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .loaders import read_text_file
from .schemas import (
    SCORE_FIELDS,
    ConceptChunkRecord,
    ConceptNeighbors,
    validate_concept_chunk_record,
)
from .text import (
    FIELD_KEYWORDS,
    clean_markdown,
    extract_terms,
    keyword_matches,
    normalize_text,
    relative_source_path,
    stable_hex,
)


FASTAPI_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA_DIR = FASTAPI_ROOT / "data" / "rag"
DEFAULT_CONCEPT_SOURCE_ROOT = Path(
    "/Users/tlsdla1235/study/md파일들/computer-science-interview-KR"
)
MARKDOWN_SUFFIXES = {".md", ".markdown"}
MIN_CHUNK_CHARS = 80
MAX_CHUNK_CHARS = 2400
HEADING_LINE_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
FENCE_LINE_RE = re.compile(r"^\s*(```|~~~)")


@dataclass(frozen=True)
class ReportEntry:
    reason: str
    source_path: str
    heading_path: tuple[str, ...] = field(default_factory=tuple)
    message: str = ""
    extra: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "reason": self.reason,
            "sourcePath": self.source_path,
        }
        if self.heading_path:
            payload["headingPath"] = list(self.heading_path)
        if self.message:
            payload["message"] = self.message
        if self.extra:
            payload["extra"] = self.extra
        return payload


@dataclass(frozen=True)
class SourceFile:
    path: Path
    source_path: str


@dataclass(frozen=True)
class DiscoveryResult:
    source_files: tuple[SourceFile, ...]
    skipped: tuple[ReportEntry, ...]


@dataclass(frozen=True)
class MarkdownSection:
    source_path: str
    heading_path: tuple[str, ...]
    heading_level: int
    raw_text: str
    char_start: int
    char_end: int
    section_index: int


@dataclass(frozen=True)
class ConceptChunkDraft:
    source_path: str
    source_root: str
    heading_path: tuple[str, ...]
    heading_level: int
    text: str
    char_start: int
    char_end: int
    section_index: int
    part_index: int


@dataclass(frozen=True)
class ConceptCatalogBuildResult:
    chunks: tuple[ConceptChunkRecord, ...]
    skipped: tuple[ReportEntry, ...]
    rejected: tuple[ReportEntry, ...]
    summary: dict[str, Any]
    output_paths: dict[str, str]


def build_concept_catalog(
    source_root: Path | None = None,
    out_dir: Path = DEFAULT_DATA_DIR,
    *,
    dry_run: bool = False,
    fastapi_root: Path = FASTAPI_ROOT,
    allowed_source_root: Path = DEFAULT_CONCEPT_SOURCE_ROOT,
    min_chunk_chars: int = MIN_CHUNK_CHARS,
    max_chunk_chars: int = MAX_CHUNK_CHARS,
) -> ConceptCatalogBuildResult:
    resolved_source_root = resolve_concept_source_root(
        source_root or DEFAULT_CONCEPT_SOURCE_ROOT,
        allowed_source_root=allowed_source_root,
    )
    output_dir = resolve_fastapi_output_dir(out_dir, fastapi_root=fastapi_root)
    discovery = discover_markdown_files(resolved_source_root)
    drafts, parse_skipped, parse_rejected = parse_concept_chunk_drafts(
        discovery.source_files,
        source_root=resolved_source_root,
        min_chunk_chars=min_chunk_chars,
        max_chunk_chars=max_chunk_chars,
    )
    chunks, rejected = build_concept_records(drafts)
    skipped = tuple(
        sorted(
            (*discovery.skipped, *parse_skipped),
            key=lambda item: (item.source_path, item.reason, item.heading_path),
        )
    )
    rejected = tuple(
        sorted(
            (*parse_rejected, *rejected),
            key=lambda item: (item.source_path, item.reason, item.heading_path),
        )
    )
    chunks = tuple(sorted(chunks, key=lambda item: (item.source_path, item.chunk_index)))
    output_paths = {
        "chunks": (output_dir / "catalog" / "chunks.jsonl").as_posix(),
        "skipped": (output_dir / "reports" / "concept-catalog-skipped.jsonl").as_posix(),
        "rejected": (output_dir / "reports" / "concept-catalog-rejected.jsonl").as_posix(),
        "summaryJson": (output_dir / "reports" / "concept-catalog-summary.json").as_posix(),
        "summaryMarkdown": (output_dir / "reports" / "concept-catalog-summary.md").as_posix(),
        "discoveryManifest": (
            output_dir / "manifests" / "concept-catalog-discovery.json"
        ).as_posix(),
    }
    summary = build_summary(
        source_root=resolved_source_root,
        output_dir=output_dir,
        discovery=discovery,
        drafts=drafts,
        chunks=chunks,
        skipped=skipped,
        rejected=rejected,
        dry_run=dry_run,
    )
    result = ConceptCatalogBuildResult(
        chunks=chunks,
        skipped=skipped,
        rejected=rejected,
        summary=summary,
        output_paths=output_paths,
    )
    if not dry_run:
        write_concept_catalog(result, discovery)
    return result


def resolve_concept_source_root(
    source_root: Path,
    *,
    allowed_source_root: Path = DEFAULT_CONCEPT_SOURCE_ROOT,
) -> Path:
    resolved = Path(source_root).expanduser().resolve()
    allowed = Path(allowed_source_root).expanduser().resolve()
    if not _is_relative_to(resolved, allowed):
        raise ValueError(f"source root must be inside {allowed}: {resolved}")
    if not resolved.exists():
        raise FileNotFoundError(f"source root does not exist: {resolved}")
    if not resolved.is_dir():
        raise ValueError(f"source root must be a directory: {resolved}")
    return resolved


def resolve_fastapi_output_dir(out_dir: Path, *, fastapi_root: Path = FASTAPI_ROOT) -> Path:
    fastapi_root = fastapi_root.expanduser().resolve()
    candidate = Path(out_dir).expanduser()
    if candidate.is_absolute():
        resolved = candidate.resolve()
    else:
        cwd = Path.cwd().resolve()
        if candidate.parts and candidate.parts[0] == fastapi_root.name and cwd == fastapi_root:
            resolved = (fastapi_root.parent / candidate).resolve()
        else:
            resolved = (cwd / candidate).resolve()
    if not _is_relative_to(resolved, fastapi_root):
        raise ValueError(f"output directory must be inside {fastapi_root}: {resolved}")
    return resolved


def discover_markdown_files(source_root: Path) -> DiscoveryResult:
    source_files: list[SourceFile] = []
    skipped: list[ReportEntry] = []
    paths = sorted(
        source_root.rglob("*"),
        key=lambda item: normalize_text(relative_source_path(item, source_root)),
    )
    for path in paths:
        if not path.is_file():
            continue
        source_path = relative_source_path(path, source_root)
        reason = excluded_file_reason(path)
        if reason:
            skipped.append(ReportEntry(reason=reason, source_path=source_path))
            continue
        if path.suffix.lower() not in MARKDOWN_SUFFIXES:
            skipped.append(ReportEntry(reason="unsupported_file_type", source_path=source_path))
            continue
        if _is_empty_file(path):
            skipped.append(ReportEntry(reason="empty_file", source_path=source_path))
            continue
        if _is_probably_binary(path):
            skipped.append(ReportEntry(reason="binary_file", source_path=source_path))
            continue
        source_files.append(SourceFile(path=path, source_path=source_path))
    return DiscoveryResult(source_files=tuple(source_files), skipped=tuple(skipped))


def excluded_file_reason(path: Path) -> str | None:
    normalized = normalize_text(path.as_posix())
    if "(대외비)" in normalized:
        return "confidential_path"
    if any(part == "node_modules" for part in path.parts):
        return "node_modules"
    if path.name == ".DS_Store":
        return "ds_store"
    if any(part.startswith(".") for part in path.parts):
        return "hidden_path"
    return None


def parse_concept_chunk_drafts(
    source_files: tuple[SourceFile, ...],
    *,
    source_root: Path,
    min_chunk_chars: int = MIN_CHUNK_CHARS,
    max_chunk_chars: int = MAX_CHUNK_CHARS,
) -> tuple[tuple[ConceptChunkDraft, ...], tuple[ReportEntry, ...], tuple[ReportEntry, ...]]:
    drafts: list[ConceptChunkDraft] = []
    skipped: list[ReportEntry] = []
    rejected: list[ReportEntry] = []
    for source_file in source_files:
        try:
            raw_text = read_text_file(source_file.path)
        except (OSError, UnicodeDecodeError) as exc:
            rejected.append(
                ReportEntry(
                    reason="read_failed",
                    source_path=source_file.source_path,
                    message=str(exc),
                )
            )
            continue
        text = normalize_text(raw_text)
        if not text.strip():
            skipped.append(ReportEntry(reason="empty_file", source_path=source_file.source_path))
            continue
        sections = parse_markdown_sections(
            text,
            source_path=source_file.source_path,
            fallback_heading=fallback_heading_for(source_file.path),
        )
        for section in sections:
            parts = split_section_text(section.raw_text, max_chars=max_chunk_chars)
            for part_index, part_text in enumerate(parts):
                cleaned = normalize_chunk_text(part_text)
                if is_too_short_chunk(cleaned, min_chars=min_chunk_chars):
                    skipped.append(
                        ReportEntry(
                            reason="chunk_too_short",
                            source_path=section.source_path,
                            heading_path=section.heading_path,
                            message="Chunk text is below the minimum substantive length.",
                            extra={
                                "sectionIndex": section.section_index,
                                "partIndex": part_index,
                                "charLength": len(cleaned),
                            },
                        )
                    )
                    continue
                drafts.append(
                    ConceptChunkDraft(
                        source_path=section.source_path,
                        source_root=source_root.as_posix(),
                        heading_path=section.heading_path,
                        heading_level=section.heading_level,
                        text=cleaned,
                        char_start=section.char_start,
                        char_end=section.char_end,
                        section_index=section.section_index,
                        part_index=part_index,
                    )
                )
    return tuple(drafts), tuple(skipped), tuple(rejected)


def parse_markdown_sections(
    text: str,
    *,
    source_path: str,
    fallback_heading: str,
) -> tuple[MarkdownSection, ...]:
    headings = _heading_matches(text)
    if not headings:
        return (
            MarkdownSection(
                source_path=source_path,
                heading_path=(fallback_heading,),
                heading_level=0,
                raw_text=text,
                char_start=0,
                char_end=len(text),
                section_index=0,
            ),
        )

    sections: list[MarkdownSection] = []
    if text[: headings[0]["start"]].strip():
        sections.append(
            MarkdownSection(
                source_path=source_path,
                heading_path=(fallback_heading,),
                heading_level=0,
                raw_text=text[: headings[0]["start"]],
                char_start=0,
                char_end=headings[0]["start"],
                section_index=0,
            )
        )

    for index, heading in enumerate(headings):
        end = headings[index + 1]["start"] if index + 1 < len(headings) else len(text)
        sections.append(
            MarkdownSection(
                source_path=source_path,
                heading_path=heading["heading_path"],
                heading_level=heading["level"],
                raw_text=text[heading["start"] : end],
                char_start=heading["start"],
                char_end=end,
                section_index=len(sections),
            )
        )
    return tuple(sections)


def _heading_matches(text: str) -> list[dict[str, Any]]:
    headings: list[dict[str, Any]] = []
    stack: list[tuple[int, str]] = []
    position = 0
    in_fence = False
    for raw_line in text.splitlines(keepends=True):
        line = raw_line.rstrip("\r\n")
        stripped = line.strip()
        if FENCE_LINE_RE.match(stripped):
            in_fence = not in_fence
            position += len(raw_line)
            continue
        match = None if in_fence else HEADING_LINE_RE.match(line)
        if match:
            level = len(match.group(1))
            title = clean_markdown(match.group(2))
            if title:
                stack = [(item_level, item_title) for item_level, item_title in stack if item_level < level]
                stack.append((level, title))
                headings.append(
                    {
                        "level": level,
                        "title": title,
                        "heading_path": tuple(item_title for _, item_title in stack),
                        "start": position,
                        "end": position + len(raw_line),
                    }
                )
        position += len(raw_line)
    return headings


def split_section_text(raw_text: str, *, max_chars: int = MAX_CHUNK_CHARS) -> tuple[str, ...]:
    cleaned = normalize_chunk_text(raw_text)
    if len(cleaned) <= max_chars:
        return (cleaned,) if cleaned else ()

    parts: list[str] = []
    current = ""
    for paragraph in re.split(r"\n\s*\n", raw_text):
        paragraph_text = normalize_chunk_text(paragraph)
        if not paragraph_text:
            continue
        if len(paragraph_text) > max_chars:
            if current:
                parts.append(current.strip())
                current = ""
            parts.extend(_hard_split_text(paragraph_text, max_chars=max_chars))
            continue
        candidate = f"{current}\n\n{paragraph_text}".strip() if current else paragraph_text
        if len(candidate) > max_chars:
            if current:
                parts.append(current.strip())
            current = paragraph_text
        else:
            current = candidate
    if current:
        parts.append(current.strip())
    return tuple(part for part in parts if part.strip())


def _hard_split_text(text: str, *, max_chars: int) -> list[str]:
    chunks: list[str] = []
    remaining = text.strip()
    while len(remaining) > max_chars:
        split_at = remaining.rfind(" ", 0, max_chars)
        if split_at < max_chars // 2:
            split_at = max_chars
        chunks.append(remaining[:split_at].strip())
        remaining = remaining[split_at:].strip()
    if remaining:
        chunks.append(remaining)
    return chunks


def normalize_chunk_text(value: str) -> str:
    value = normalize_text(value)
    value = re.sub(r"```.*?```", " ", value, flags=re.DOTALL)
    value = re.sub(r"~~~.*?~~~", " ", value, flags=re.DOTALL)
    value = re.sub(r"</?(details|summary)>", " ", value, flags=re.IGNORECASE)
    value = re.sub(r"<[^>]+>", " ", value)
    value = re.sub(r"!\[[^\]]*]\([^)]+\)", " ", value)
    value = re.sub(r"\[([^\]]+)]\([^)]+\)", r"\1", value)
    value = re.sub(r"`([^`]*)`", r"\1", value)
    value = re.sub(r"(?m)^\s{0,3}#{1,6}\s*", "", value)
    value = re.sub(r"(?m)^\s{0,3}>\s?", "", value)
    value = re.sub(r"(?m)^\s*[-*+]\s+", "", value)
    value = re.sub(r"[*_]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


def is_too_short_chunk(text: str, *, min_chars: int = MIN_CHUNK_CHARS) -> bool:
    if len(text) < min_chars:
        return True
    return len(extract_terms(text)) < 5


def build_concept_records(
    drafts: tuple[ConceptChunkDraft, ...],
) -> tuple[tuple[ConceptChunkRecord, ...], tuple[ReportEntry, ...]]:
    records: list[ConceptChunkRecord] = []
    rejected: list[ReportEntry] = []
    by_source: dict[str, list[ConceptChunkDraft]] = {}
    for draft in sorted(
        drafts,
        key=lambda item: (item.source_path, item.section_index, item.part_index),
    ):
        by_source.setdefault(draft.source_path, []).append(draft)

    for source_path in sorted(by_source):
        source_drafts = by_source[source_path]
        sibling_paths = _sibling_heading_paths(source_drafts)
        chunk_ids = [
            make_chunk_id(
                source_path=draft.source_path,
                heading_path=draft.heading_path,
                chunk_index=index,
            )
            for index, draft in enumerate(source_drafts)
        ]
        for index, draft in enumerate(source_drafts):
            record = ConceptChunkRecord(
                chunk_id=chunk_ids[index],
                source_path=draft.source_path,
                source_root=draft.source_root,
                heading_path=draft.heading_path,
                heading_level=draft.heading_level,
                chunk_index=index,
                text=draft.text,
                content_hash=make_content_hash(draft.text),
                field_hints=tuple(
                    infer_field_hints(
                        draft.source_path,
                        " ".join(draft.heading_path),
                        draft.text,
                    )
                ),
                neighbors=ConceptNeighbors(
                    previous_chunk_id=chunk_ids[index - 1] if index > 0 else None,
                    next_chunk_id=chunk_ids[index + 1] if index + 1 < len(chunk_ids) else None,
                    parent_heading_path=draft.heading_path[:-1],
                    sibling_heading_paths=tuple(
                        heading_path
                        for heading_path in sibling_paths.get(draft.heading_path[:-1], ())
                        if heading_path != draft.heading_path
                    ),
                ),
                char_start=draft.char_start,
                char_end=draft.char_end,
            )
            errors = validate_concept_chunk_record(record)
            if errors:
                rejected.append(
                    ReportEntry(
                        reason="schema_validation_failed",
                        source_path=draft.source_path,
                        heading_path=draft.heading_path,
                        message="; ".join(errors),
                        extra={"chunkId": record.chunk_id},
                    )
                )
                continue
            records.append(record)
    return tuple(records), tuple(rejected)


def _sibling_heading_paths(
    drafts: list[ConceptChunkDraft],
) -> dict[tuple[str, ...], tuple[tuple[str, ...], ...]]:
    by_parent: dict[tuple[str, ...], list[tuple[str, ...]]] = {}
    for draft in drafts:
        parent = draft.heading_path[:-1]
        siblings = by_parent.setdefault(parent, [])
        if draft.heading_path not in siblings:
            siblings.append(draft.heading_path)
    return {
        parent: tuple(paths)
        for parent, paths in by_parent.items()
    }


def make_chunk_id(
    *,
    source_path: str,
    heading_path: tuple[str, ...],
    chunk_index: int,
) -> str:
    payload = {
        "sourcePath": source_path,
        "headingPath": list(heading_path),
        "chunkIndex": chunk_index,
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return f"concept:sha256:{stable_hex(encoded)}"


def make_content_hash(text: str) -> str:
    return f"sha256:{stable_hex(normalize_chunk_text(text))}"


def infer_field_hints(*values: str) -> list[str]:
    haystack = normalize_text(" ".join(values)).lower()
    haystack_terms = set(extract_terms(haystack))
    scores: dict[str, int] = {}
    for field_name, keywords in FIELD_KEYWORDS.items():
        score = 0
        for keyword in keywords:
            normalized_keyword = normalize_text(keyword).lower()
            if keyword_matches(normalized_keyword, haystack, haystack_terms):
                score += 2 if len(normalized_keyword) > 3 else 1
        if score:
            scores[field_name] = score
    return [
        field_name
        for field_name, _ in sorted(
            scores.items(),
            key=lambda item: (-item[1], SCORE_FIELDS.index(item[0])),
        )
    ]


def fallback_heading_for(path: Path) -> str:
    title = clean_markdown(path.stem.replace("-", " ").replace("_", " "))
    return title or path.name


def build_summary(
    *,
    source_root: Path,
    output_dir: Path,
    discovery: DiscoveryResult,
    drafts: tuple[ConceptChunkDraft, ...],
    chunks: tuple[ConceptChunkRecord, ...],
    skipped: tuple[ReportEntry, ...],
    rejected: tuple[ReportEntry, ...],
    dry_run: bool,
) -> dict[str, Any]:
    skipped_by_reason: dict[str, int] = {}
    for item in skipped:
        skipped_by_reason[item.reason] = skipped_by_reason.get(item.reason, 0) + 1
    rejected_by_reason: dict[str, int] = {}
    for item in rejected:
        rejected_by_reason[item.reason] = rejected_by_reason.get(item.reason, 0) + 1
    return {
        "sourceRoot": source_root.as_posix(),
        "outputDir": output_dir.as_posix(),
        "dryRun": dry_run,
        "discoveredFileCount": len(discovery.source_files) + len(discovery.skipped),
        "candidateFileCount": len(discovery.source_files),
        "skippedFileCount": len(discovery.skipped),
        "parsedChunkDraftCount": len(drafts),
        "storedChunkCount": len(chunks),
        "skippedCount": len(skipped),
        "rejectedCount": len(rejected),
        "skippedByReason": dict(sorted(skipped_by_reason.items())),
        "rejectedByReason": dict(sorted(rejected_by_reason.items())),
    }


def write_concept_catalog(
    result: ConceptCatalogBuildResult,
    discovery: DiscoveryResult,
) -> None:
    chunks_path = Path(result.output_paths["chunks"])
    skipped_path = Path(result.output_paths["skipped"])
    rejected_path = Path(result.output_paths["rejected"])
    summary_json_path = Path(result.output_paths["summaryJson"])
    summary_markdown_path = Path(result.output_paths["summaryMarkdown"])
    discovery_manifest_path = Path(result.output_paths["discoveryManifest"])

    for directory in {
        chunks_path.parent,
        skipped_path.parent,
        rejected_path.parent,
        summary_json_path.parent,
        discovery_manifest_path.parent,
    }:
        directory.mkdir(parents=True, exist_ok=True)

    _write_jsonl(chunks_path, [chunk.to_json_dict() for chunk in result.chunks])
    _write_jsonl(skipped_path, [item.to_dict() for item in result.skipped])
    _write_jsonl(rejected_path, [item.to_dict() for item in result.rejected])
    summary_json_path.write_text(
        json.dumps(result.summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    summary_markdown_path.write_text(_summary_markdown(result.summary), encoding="utf-8")
    discovery_manifest_path.write_text(
        json.dumps(
            {
                "sourceRoot": result.summary["sourceRoot"],
                "sourceFiles": [
                    {
                        "sourcePath": source_file.source_path,
                        "sourceType": "markdown",
                    }
                    for source_file in discovery.source_files
                ],
                "skippedFiles": [item.to_dict() for item in discovery.skipped],
            },
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )


def _summary_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# Concept Catalog Summary",
        "",
        f"- Source root: `{summary['sourceRoot']}`",
        f"- Output dir: `{summary['outputDir']}`",
        f"- Dry run: `{summary['dryRun']}`",
        f"- Discovered files: {summary['discoveredFileCount']}",
        f"- Candidate files: {summary['candidateFileCount']}",
        f"- Skipped files: {summary['skippedFileCount']}",
        f"- Parsed chunk drafts: {summary['parsedChunkDraftCount']}",
        f"- Stored chunks: {summary['storedChunkCount']}",
        f"- Skipped entries: {summary['skippedCount']}",
        f"- Rejected entries: {summary['rejectedCount']}",
        "",
    ]
    return "\n".join(lines)


def _is_empty_file(path: Path) -> bool:
    try:
        return path.stat().st_size == 0
    except OSError:
        return False


def _is_probably_binary(path: Path) -> bool:
    try:
        sample = path.read_bytes()[:4096]
    except OSError:
        return True
    if b"\x00" in sample:
        return True
    if not sample:
        return False
    control_bytes = sum(1 for byte in sample if byte < 9 or (13 < byte < 32))
    return control_bytes / len(sample) > 0.30


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False
