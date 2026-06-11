from __future__ import annotations

import csv
import json
import re
from dataclasses import dataclass, field
from io import StringIO
from pathlib import Path
from typing import Any

from .loaders import SUPPORTED_SUFFIXES, read_text_file, source_type_for
from .schemas import (
    DIFFICULTIES,
    SCORE_FIELDS,
    ProblemRecord,
    ProblemRubric,
    validate_problem_record,
)
from .text import (
    HEADING_RE,
    clean_markdown,
    difficulty_from_text,
    extract_terms,
    infer_fields,
    normalize_text,
    relative_source_path,
    score_allocation_for_fields,
    stable_hex,
    stable_int_id,
)


FASTAPI_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA_DIR = FASTAPI_ROOT / "data" / "rag"

IMAGE_SUFFIXES = {
    ".apng",
    ".avif",
    ".gif",
    ".heic",
    ".ico",
    ".jpeg",
    ".jpg",
    ".png",
    ".svg",
    ".webp",
}
ANSWER_MARKER_RE = re.compile(
    r"^\s*(?:#{1,6}\s*)?(?:\*\*)?\s*(?:[▶>✅\s]+)?"
    r"(?:모범\s*답변|모범답변|답안|정답|해설|answer)"
    r"\s*(?:\*\*)?\s*$",
    re.IGNORECASE | re.MULTILINE,
)
SCORING_MARKER_RE = re.compile(
    r"^\s*(?:#{1,6}\s*)?(?:\*\*)?\s*(?:[▶⭐💡✅\s]+)?"
    r"(?:채점\s*포인트|핵심\s*키워드|백엔드/풀스택\s*연계\s*포인트|"
    r"연계\s*포인트|면접관이\s*주목하는\s*포인트|꼬리\s*질문\s*대비)\b",
    re.IGNORECASE | re.MULTILINE,
)
DIFFICULTY_LABEL_RE = re.compile(r"^\[[^\]]*(?:기본|중급|심화|고급)[^\]]*]\s*")
LEADING_NUMBER_RE = re.compile(r"^(?:Q\s*)?\d+(?:[-.]\d+)*\s*[.)]\s*", re.IGNORECASE)
QUESTION_HINT_RE = re.compile(r"(?:^|\s)Q\s*\d+(?:[-.]\d+)*\s*[.)]", re.IGNORECASE)
NUMBERED_HINT_RE = re.compile(r"\d+(?:[-.]\d+)*\s*[.)]")

KNOWN_RUBRIC_PHRASES = (
    "N+1",
    "Lazy Loading",
    "Eager Loading",
    "fetch join",
    "Batch Size",
    "EntityGraph",
    "트랜잭션",
    "격리 수준",
    "정규화",
    "인덱스",
    "B-Tree",
    "ACID",
    "REST",
    "HTTP",
    "JWT",
    "OAuth",
    "CORS",
    "TCP",
    "UDP",
    "DFS",
    "BFS",
    "시간 복잡도",
    "공간 복잡도",
    "정적 분석",
    "트리 쉐이킹",
    "코드 스플리팅",
    "의존성",
    "동시성",
    "캐시",
    "컨테이너",
    "Kubernetes",
    "Docker",
)
MUST_NOT_CONFUSE_BY_FIELD = {
    "db": ("SQL Injection", "HTTP 상태 코드"),
    "algorithm": ("프레임워크 설정", "데이터베이스 인덱스"),
    "cs": ("라이브러리 API 사용법",),
    "network": ("메서드 오버로딩", "정렬 안정성"),
    "framework": ("TCP 혼잡 제어",),
}
RUBRIC_STOP_TERMS = {
    "면접관",
    "주목하",
    "포인트",
    "핵심",
    "답변",
    "설명하",
    "사용하",
    "어떤",
    "있는지",
    "예시",
}


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
    source_type: str


@dataclass(frozen=True)
class DiscoveryResult:
    source_files: tuple[SourceFile, ...]
    skipped: tuple[ReportEntry, ...]


@dataclass(frozen=True)
class ProblemDraft:
    question: str
    model_answer: str | None
    difficulty: str
    source_path: str
    heading_path: tuple[str, ...]
    source_type: str
    rubric_source: str = ""


@dataclass(frozen=True)
class ProblemBankBuildResult:
    problems: tuple[ProblemRecord, ...]
    skipped: tuple[ReportEntry, ...]
    rejected: tuple[ReportEntry, ...]
    summary: dict[str, Any]
    output_paths: dict[str, str]


def build_problem_bank(
    source_root: Path,
    out_dir: Path = DEFAULT_DATA_DIR,
    *,
    dry_run: bool = False,
    fastapi_root: Path = FASTAPI_ROOT,
) -> ProblemBankBuildResult:
    source_root = Path(source_root).expanduser().resolve()
    if not source_root.exists():
        raise FileNotFoundError(f"source root does not exist: {source_root}")
    if not source_root.is_dir():
        raise ValueError(f"source root must be a directory: {source_root}")

    output_dir = resolve_fastapi_output_dir(out_dir, fastapi_root=fastapi_root)
    discovery = discover_source_files(source_root)
    drafts, parse_skipped = parse_problem_drafts(discovery.source_files)
    problems, rejected, draft_skipped = build_problem_records(drafts)
    skipped = tuple(sorted(
        (*discovery.skipped, *parse_skipped, *draft_skipped),
        key=lambda item: (item.source_path, item.reason, item.heading_path),
    ))
    rejected = tuple(sorted(rejected, key=lambda item: (item.source_path, item.reason, item.heading_path)))
    problems = tuple(sorted(problems, key=lambda item: (item.source_path, item.heading_path, item.source_key)))

    output_paths = {
        "problems": (output_dir / "catalog" / "problems.jsonl").as_posix(),
        "skipped": (output_dir / "reports" / "problem-bank-skipped.jsonl").as_posix(),
        "rejected": (output_dir / "reports" / "problem-bank-rejected.jsonl").as_posix(),
        "summaryJson": (output_dir / "reports" / "problem-bank-summary.json").as_posix(),
        "summaryMarkdown": (output_dir / "reports" / "problem-bank-summary.md").as_posix(),
        "discoveryManifest": (output_dir / "manifests" / "problem-bank-discovery.json").as_posix(),
    }
    summary = build_summary(
        source_root=source_root,
        output_dir=output_dir,
        discovery=discovery,
        drafts=drafts,
        problems=problems,
        skipped=skipped,
        rejected=rejected,
        dry_run=dry_run,
    )
    result = ProblemBankBuildResult(
        problems=problems,
        skipped=skipped,
        rejected=rejected,
        summary=summary,
        output_paths=output_paths,
    )
    if not dry_run:
        write_problem_bank(result, discovery)
    return result


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


def discover_source_files(source_root: Path) -> DiscoveryResult:
    source_files: list[SourceFile] = []
    skipped: list[ReportEntry] = []
    for path in sorted(source_root.rglob("*"), key=lambda item: normalize_text(item.as_posix())):
        if not path.is_file():
            continue
        source_path = relative_source_path(path, source_root)
        reason = excluded_file_reason(path)
        if reason:
            skipped.append(ReportEntry(reason=reason, source_path=source_path))
            continue
        if path.suffix.lower() not in SUPPORTED_SUFFIXES:
            reason = "image_file" if path.suffix.lower() in IMAGE_SUFFIXES else "unsupported_file_type"
            skipped.append(ReportEntry(reason=reason, source_path=source_path))
            continue
        if _is_empty_file(path):
            skipped.append(ReportEntry(reason="empty_file", source_path=source_path))
            continue
        if _is_probably_binary(path):
            skipped.append(ReportEntry(reason="binary_file", source_path=source_path))
            continue
        source_files.append(
            SourceFile(path=path, source_path=source_path, source_type=source_type_for(path))
        )
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


def parse_problem_drafts(source_files: tuple[SourceFile, ...]) -> tuple[tuple[ProblemDraft, ...], tuple[ReportEntry, ...]]:
    drafts: list[ProblemDraft] = []
    skipped: list[ReportEntry] = []
    for source_file in source_files:
        text = read_text_file(source_file.path)
        if not normalize_text(text).strip():
            skipped.append(ReportEntry(reason="empty_file", source_path=source_file.source_path))
            continue
        if source_file.path.suffix.lower() == ".csv":
            parsed, csv_skipped = parse_csv_problem_drafts(source_file, text)
        else:
            parsed, csv_skipped = parse_markdown_problem_drafts(source_file, text)
        drafts.extend(parsed)
        skipped.extend(csv_skipped)
    return tuple(drafts), tuple(skipped)


def parse_markdown_problem_drafts(source_file: SourceFile, text: str) -> tuple[list[ProblemDraft], list[ReportEntry]]:
    normalized_text = normalize_text(text)
    line_matches = list(_iter_problem_lines(normalized_text))
    drafts: list[ProblemDraft] = []
    skipped: list[ReportEntry] = []
    for index, match in enumerate(line_matches):
        block_start = match["line_end"]
        block_end = line_matches[index + 1]["line_start"] if index + 1 < len(line_matches) else len(normalized_text)
        block = normalized_text[block_start:block_end]
        model_answer, rubric_source = extract_model_answer(block)
        heading_path = heading_path_for_position(
            normalized_text,
            match["line_start"],
            match["question"],
            match["heading_level"],
        )
        draft = ProblemDraft(
            question=match["question"],
            model_answer=model_answer,
            difficulty=match["difficulty"],
            source_path=source_file.source_path,
            heading_path=heading_path,
            source_type=source_file.source_type,
            rubric_source=rubric_source,
        )
        if model_answer is None:
            skipped.append(
                ReportEntry(
                    reason="missing_model_answer",
                    source_path=source_file.source_path,
                    heading_path=heading_path,
                    message="Detected a question-like block, but no substantive model answer was found.",
                    extra={"question": match["question"]},
                )
            )
        drafts.append(draft)
    return drafts, skipped


def parse_csv_problem_drafts(source_file: SourceFile, text: str) -> tuple[list[ProblemDraft], list[ReportEntry]]:
    reader = csv.DictReader(StringIO(text))
    if not reader.fieldnames:
        return [], (ReportEntry(reason="csv_without_header", source_path=source_file.source_path),)

    question_columns = ("question", "Question", "질문", "문제")
    answer_columns = ("modelAnswer", "model_answer", "answer", "Answer", "모범답변", "모범 답변", "답변", "정답", "해설")
    difficulty_columns = ("difficulty", "Difficulty", "난이도")
    available = set(reader.fieldnames)
    question_column = next((column for column in question_columns if column in available), None)
    answer_column = next((column for column in answer_columns if column in available), None)
    difficulty_column = next((column for column in difficulty_columns if column in available), None)
    if question_column is None:
        return [], (ReportEntry(reason="csv_metadata_only", source_path=source_file.source_path),)

    drafts: list[ProblemDraft] = []
    skipped: list[ReportEntry] = []
    for row_number, row in enumerate(reader, start=2):
        question = clean_markdown(row.get(question_column, ""))
        if not question:
            continue
        answer = clean_markdown(row.get(answer_column, "")) if answer_column else ""
        model_answer = answer[:1800] if is_substantive_answer(answer) else None
        raw_difficulty = row.get(difficulty_column, "") if difficulty_column else ""
        difficulty = difficulty_from_text(raw_difficulty or question)
        heading_path = (f"CSV row {row_number}", question)
        if model_answer is None:
            skipped.append(
                ReportEntry(
                    reason="missing_model_answer",
                    source_path=source_file.source_path,
                    heading_path=heading_path,
                    message="CSV row has a question but no substantive model answer.",
                    extra={"row": row_number, "question": question},
                )
            )
        drafts.append(
            ProblemDraft(
                question=question,
                model_answer=model_answer,
                difficulty=difficulty,
                source_path=source_file.source_path,
                heading_path=heading_path,
                source_type="csv",
            )
        )
    return drafts, skipped


def build_problem_records(
    drafts: tuple[ProblemDraft, ...],
) -> tuple[tuple[ProblemRecord, ...], tuple[ReportEntry, ...], tuple[ReportEntry, ...]]:
    problems: list[ProblemRecord] = []
    rejected: list[ReportEntry] = []
    skipped: list[ReportEntry] = []
    seen_source_keys: set[str] = set()
    for draft in drafts:
        if draft.model_answer is None:
            continue
        fields = tuple(_dedupe(infer_fields(
            draft.source_path,
            " ".join(draft.heading_path),
            draft.question,
            draft.model_answer[:500],
        )))
        primary_field = fields[0] if fields else "cs"
        difficulty = draft.difficulty if draft.difficulty in DIFFICULTIES else "basic"
        score_allocation = score_allocation_for_fields(list(fields), difficulty)
        rubric = build_rubric(draft.model_answer, draft.rubric_source, primary_field)
        source_key = make_source_key(draft.source_path, draft.heading_path, draft.question)
        problem = ProblemRecord(
            problem_id=stable_int_id(source_key),
            source_key=source_key,
            question=draft.question,
            model_answer=draft.model_answer,
            difficulty=difficulty,
            primary_field=primary_field,
            fields=fields or ("cs",),
            score_allocation=score_allocation,
            source_path=draft.source_path,
            heading_path=draft.heading_path,
            rubric=rubric,
            content_hash=make_content_hash(
                question=draft.question,
                model_answer=draft.model_answer,
                difficulty=difficulty,
                fields=fields or ("cs",),
                score_allocation=score_allocation,
                rubric=rubric,
                source_path=draft.source_path,
                heading_path=draft.heading_path,
            ),
        )
        if source_key in seen_source_keys:
            rejected.append(
                ReportEntry(
                    reason="duplicate_source_key",
                    source_path=draft.source_path,
                    heading_path=draft.heading_path,
                    extra={"sourceKey": source_key, "question": draft.question},
                )
            )
            continue
        errors = validate_problem_record(problem)
        if errors:
            rejected.append(
                ReportEntry(
                    reason="schema_validation_failed",
                    source_path=draft.source_path,
                    heading_path=draft.heading_path,
                    message="; ".join(errors),
                    extra={"sourceKey": source_key, "question": draft.question},
                )
            )
            continue
        seen_source_keys.add(source_key)
        problems.append(problem)
    return tuple(problems), tuple(rejected), tuple(skipped)


def extract_model_answer(block: str) -> tuple[str | None, str]:
    prepared = normalize_text(block)
    summary_end = re.search(r"</summary\s*>", prepared, flags=re.IGNORECASE)
    if summary_end:
        prepared = prepared[summary_end.end():]
        details_end = re.search(r"</details\s*>", prepared, flags=re.IGNORECASE)
        if details_end:
            prepared = prepared[:details_end.start()]
    prepared = re.sub(r"<summary\b.*?</summary>", " ", prepared, flags=re.IGNORECASE | re.DOTALL)
    prepared = re.sub(r"</?details\b[^>]*>", " ", prepared, flags=re.IGNORECASE)
    answer_start = 0
    answer_marker = ANSWER_MARKER_RE.search(prepared)
    if answer_marker:
        answer_start = answer_marker.end()
    answer_region = prepared[answer_start:]
    scoring_marker = SCORING_MARKER_RE.search(answer_region)
    rubric_source = ""
    if scoring_marker:
        answer_region, rubric_source = (
            answer_region[:scoring_marker.start()],
            answer_region[scoring_marker.start():],
        )
    answer = clean_markdown(answer_region.replace("답변 보기", " "))
    answer = re.sub(r"\s+", " ", answer).strip()
    if not is_substantive_answer(answer):
        return None, rubric_source
    return answer[:1800], rubric_source


def is_substantive_answer(answer: str) -> bool:
    if len(answer) < 80:
        return False
    terms = re.findall(r"[A-Za-z가-힣0-9+#./_-]{2,}", answer)
    if len(terms) < 10:
        return False
    return True


def build_rubric(model_answer: str, rubric_source: str, primary_field: str) -> ProblemRubric:
    must_source = rubric_source or model_answer
    must_mention = _keyword_candidates(must_source, limit=6)
    optional_mention = [
        item
        for item in _keyword_candidates(model_answer, limit=10)
        if item not in must_mention
    ][:6]
    if not must_mention and optional_mention:
        must_mention = optional_mention[:3]
        optional_mention = optional_mention[3:]
    return ProblemRubric(
        must_mention=tuple(must_mention),
        optional_mention=tuple(optional_mention),
        must_not_confuse=tuple(MUST_NOT_CONFUSE_BY_FIELD.get(primary_field, ())[:3]),
    )


def make_source_key(source_path: str, heading_path: tuple[str, ...], question: str) -> str:
    seed = "\n".join((source_path, " > ".join(heading_path), question))
    return f"sha256:{stable_hex(seed)}"


def make_content_hash(
    *,
    question: str,
    model_answer: str,
    difficulty: str,
    fields: tuple[str, ...],
    score_allocation: dict[str, int],
    rubric: ProblemRubric,
    source_path: str,
    heading_path: tuple[str, ...],
) -> str:
    payload = {
        "question": question,
        "modelAnswer": model_answer,
        "difficulty": difficulty,
        "fields": list(fields),
        "scoreAllocation": score_allocation,
        "rubric": rubric.to_dict(),
        "sourcePath": source_path,
        "headingPath": list(heading_path),
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return f"sha256:{stable_hex(encoded)}"


def build_summary(
    *,
    source_root: Path,
    output_dir: Path,
    discovery: DiscoveryResult,
    drafts: tuple[ProblemDraft, ...],
    problems: tuple[ProblemRecord, ...],
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
        "parsedCandidateCount": len(drafts),
        "storedProblemCount": len(problems),
        "skippedCount": len(skipped),
        "rejectedCount": len(rejected),
        "skippedByReason": dict(sorted(skipped_by_reason.items())),
        "rejectedByReason": dict(sorted(rejected_by_reason.items())),
    }


def write_problem_bank(result: ProblemBankBuildResult, discovery: DiscoveryResult) -> None:
    problems_path = Path(result.output_paths["problems"])
    skipped_path = Path(result.output_paths["skipped"])
    rejected_path = Path(result.output_paths["rejected"])
    summary_json_path = Path(result.output_paths["summaryJson"])
    summary_markdown_path = Path(result.output_paths["summaryMarkdown"])
    discovery_manifest_path = Path(result.output_paths["discoveryManifest"])

    for directory in {
        problems_path.parent,
        skipped_path.parent,
        rejected_path.parent,
        summary_json_path.parent,
        discovery_manifest_path.parent,
    }:
        directory.mkdir(parents=True, exist_ok=True)

    _write_jsonl(problems_path, [problem.to_json_dict() for problem in result.problems])
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
                "sourceFiles": [
                    {
                        "sourcePath": source_file.source_path,
                        "sourceType": source_file.source_type,
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


def heading_path_for_position(
    text: str,
    position: int,
    question: str,
    current_heading_level: int = 0,
) -> tuple[str, ...]:
    stack: list[tuple[int, str]] = []
    code_fence_ranges = _code_fence_ranges(text)
    for heading in HEADING_RE.finditer(text):
        if heading.start() >= position:
            break
        if _position_in_ranges(heading.start(), code_fence_ranges):
            continue
        if parse_problem_line(heading.group(0)) is not None:
            continue
        level = len(heading.group(1))
        if current_heading_level and level >= current_heading_level:
            continue
        title = clean_markdown(heading.group(2))
        if not title:
            continue
        stack = [(item_level, item_title) for item_level, item_title in stack if item_level < level]
        stack.append((level, title))
    heading_path = tuple(item_title for _, item_title in stack)
    return (*heading_path, question) if heading_path else (question,)


def parse_problem_line(line: str) -> tuple[str, str, int] | None:
    stripped = normalize_text(line).strip()
    if not stripped:
        return None
    stripped = re.sub(r"^[-*]\s+", "", stripped)
    heading_match = re.match(r"^(#{1,6})\s+(.+?)\s*$", stripped)
    heading_level = len(heading_match.group(1)) if heading_match else 0
    body = heading_match.group(2) if heading_match else stripped
    body = body.strip().strip("*_").strip()
    if body.startswith("|") or not body:
        return None
    has_difficulty = bool(DIFFICULTY_LABEL_RE.search(body)) or body.startswith(("🟢", "🟡", "🔴"))
    has_question_number = bool(QUESTION_HINT_RE.search(body))
    has_numbered_difficulty = has_difficulty and bool(NUMBERED_HINT_RE.search(body))
    if not has_question_number and not has_numbered_difficulty:
        return None
    question = normalize_question(body)
    if not question:
        return None
    return question, difficulty_from_problem_line(body), heading_level


def normalize_question(body: str) -> str:
    question = body.strip().strip("*_").strip()
    question = DIFFICULTY_LABEL_RE.sub("", question)
    question = re.sub(r"^[🟢🟡🔴]\s*", "", question)
    question = LEADING_NUMBER_RE.sub("", question)
    question = re.sub(r"[⭐★]+", " ", question)
    return clean_markdown(question).strip()


def difficulty_from_problem_line(body: str) -> str:
    if "🔴" in body:
        return "advanced"
    if "🟡" in body:
        return "intermediate"
    if "🟢" in body:
        return "basic"
    return difficulty_from_text(body)


def _iter_problem_lines(text: str) -> list[dict[str, Any]]:
    matches: list[dict[str, Any]] = []
    position = 0
    in_code_fence = False
    for raw_line in text.splitlines(keepends=True):
        line = raw_line.rstrip("\r\n")
        stripped = line.strip()
        if stripped.startswith("```"):
            in_code_fence = not in_code_fence
            position += len(raw_line)
            continue
        parsed = None if in_code_fence else parse_problem_line(line)
        if parsed is not None:
            question, difficulty, heading_level = parsed
            matches.append(
                {
                    "line_start": position,
                    "line_end": position + len(raw_line),
                    "question": question,
                    "difficulty": difficulty,
                    "heading_level": heading_level,
                }
            )
        position += len(raw_line)
    return matches


def _keyword_candidates(text: str, *, limit: int) -> list[str]:
    lowered = normalize_text(text).lower()
    candidates: list[str] = []
    for phrase in KNOWN_RUBRIC_PHRASES:
        if phrase.lower() in lowered:
            candidates.append(phrase)
    for term in extract_terms(text):
        if term in SCORE_FIELDS or term in RUBRIC_STOP_TERMS or len(term) < 3:
            continue
        candidates.append(term)
    return _dedupe(candidates)[:limit]


def _dedupe(values: list[str] | tuple[str, ...]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        normalized = normalize_text(value).lower()
        if normalized in seen:
            continue
        seen.add(normalized)
        result.append(value)
    return result


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )


def _summary_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# Problem Bank Summary",
        "",
        f"- Source root: `{summary['sourceRoot']}`",
        f"- Output dir: `{summary['outputDir']}`",
        f"- Dry run: `{summary['dryRun']}`",
        f"- Discovered files: {summary['discoveredFileCount']}",
        f"- Candidate files: {summary['candidateFileCount']}",
        f"- Skipped files: {summary['skippedFileCount']}",
        f"- Parsed candidates: {summary['parsedCandidateCount']}",
        f"- Stored problems: {summary['storedProblemCount']}",
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


def _code_fence_ranges(text: str) -> list[tuple[int, int]]:
    ranges: list[tuple[int, int]] = []
    start: int | None = None
    for match in re.finditer(r"^```.*$", text, flags=re.MULTILINE):
        if start is None:
            start = match.start()
        else:
            ranges.append((start, match.end()))
            start = None
    if start is not None:
        ranges.append((start, len(text)))
    return ranges


def _position_in_ranges(position: int, ranges: list[tuple[int, int]]) -> bool:
    return any(start <= position <= end for start, end in ranges)


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False
