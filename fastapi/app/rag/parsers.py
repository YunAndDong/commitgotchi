from __future__ import annotations

import re

from .schemas import KnowledgeChunk, ProblemCandidate, RagIndex
from .text import (
    HEADING_RE,
    clean_markdown,
    difficulty_from_text,
    infer_fields,
    normalize_text,
    score_allocation_for_fields,
    stable_hex,
    stable_int_id,
)


QNA_HEADING_RE = re.compile(r"^##\s+Q(?P<num>\d+)\.\s*(?P<question>.+?)\s*$", re.MULTILINE)
QUIZ_QUESTION_RE = re.compile(
    r"^(?:#{1,6}\s*)?\[(?P<level>기본|중급|심화|고급)]\s*Q?(?P<num>\d+)\.\s*(?P<question>.+?)\s*$",
    re.MULTILINE,
)


def parse_sources(sources: list[tuple[str, str, str]]) -> RagIndex:
    chunks: list[KnowledgeChunk] = []
    problems_by_key: dict[str, ProblemCandidate] = {}

    for relative_path, source_type, text in sources:
        if source_type == "csv":
            continue
        parsed_chunks, parsed_problems = parse_markdown_source(relative_path, source_type, text)
        chunks.extend(parsed_chunks)
        for problem in parsed_problems:
            problems_by_key.setdefault(problem.source_key, problem)

    return RagIndex(chunks=tuple(chunks), problems=tuple(problems_by_key.values()))


def parse_markdown_source(
    relative_path: str,
    source_type: str,
    text: str,
) -> tuple[list[KnowledgeChunk], list[ProblemCandidate]]:
    normalized_text = normalize_text(text)
    chunks = parse_heading_chunks(relative_path, source_type, normalized_text)
    problems = parse_qna_problems(relative_path, normalized_text)
    problems.extend(parse_quiz_problems(relative_path, normalized_text))
    return chunks, problems


def parse_heading_chunks(relative_path: str, source_type: str, text: str) -> list[KnowledgeChunk]:
    matches = list(HEADING_RE.finditer(text))
    if not matches:
        cleaned = clean_markdown(text)
        if len(cleaned) < 80:
            return []
        field_name = infer_fields(relative_path, cleaned)[0]
        return [
            KnowledgeChunk(
                chunk_id=stable_hex(f"{relative_path}:root")[:16],
                source_path=relative_path,
                heading_path=(relative_path,),
                text=cleaned,
                field=field_name,
                source_type=source_type,
            )
        ]

    chunks: list[KnowledgeChunk] = []
    heading_stack: list[tuple[int, str]] = []
    for index, match in enumerate(matches):
        level = len(match.group(1))
        title = clean_markdown(match.group(2))
        section_start = match.end()
        section_end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        section_text = clean_markdown(text[section_start:section_end])
        heading_stack = [(item_level, item_title) for item_level, item_title in heading_stack if item_level < level]
        heading_stack.append((level, title))
        heading_path = tuple(item_title for _, item_title in heading_stack)
        if len(section_text) < 80:
            continue
        field_name = infer_fields(relative_path, " ".join(heading_path), section_text)[0]
        chunks.append(
            KnowledgeChunk(
                chunk_id=stable_hex(f"{relative_path}:{' > '.join(heading_path)}")[:16],
                source_path=relative_path,
                heading_path=heading_path,
                text=section_text,
                field=field_name,
                source_type=source_type,
            )
        )
    return chunks


def parse_qna_problems(relative_path: str, text: str) -> list[ProblemCandidate]:
    matches = list(QNA_HEADING_RE.finditer(text))
    problems: list[ProblemCandidate] = []
    for index, match in enumerate(matches):
        question = clean_markdown(match.group("question"))
        block_start = match.end()
        block_end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        block = text[block_start:block_end]
        model_answer = extract_model_answer(block)
        if not model_answer:
            continue
        fields = infer_fields(relative_path, question, model_answer[:320])
        difficulty = difficulty_from_text(match.group(0))
        source_key = stable_hex(f"{relative_path}:qna:{question}")
        problems.append(
            ProblemCandidate(
                problem_id=stable_int_id(source_key),
                source_key=source_key,
                question=question,
                model_answer=model_answer,
                score_allocation=score_allocation_for_fields(fields, difficulty),
                field=fields[0],
                difficulty=difficulty,
                source_path=relative_path,
                heading_path=(question,),
            )
        )
    return problems


def parse_quiz_problems(relative_path: str, text: str) -> list[ProblemCandidate]:
    matches = list(QUIZ_QUESTION_RE.finditer(text))
    problems: list[ProblemCandidate] = []
    for index, match in enumerate(matches):
        question = clean_markdown(match.group("question"))
        block_start = match.end()
        block_end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        block = text[block_start:block_end]
        model_answer = extract_model_answer(block)
        if not model_answer:
            continue
        fields = infer_fields(relative_path, question, model_answer[:320])
        difficulty = difficulty_from_text(match.group("level"))
        source_key = stable_hex(f"{relative_path}:quiz:{question}")
        problems.append(
            ProblemCandidate(
                problem_id=stable_int_id(source_key),
                source_key=source_key,
                question=question,
                model_answer=model_answer,
                score_allocation=score_allocation_for_fields(fields, difficulty),
                field=fields[0],
                difficulty=difficulty,
                source_path=relative_path,
                heading_path=(question,),
            )
        )
    return problems


def extract_model_answer(block: str) -> str | None:
    cleaned = clean_markdown(block)
    cleaned = cleaned.replace("답변 보기", " ")
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    if len(cleaned) < 80:
        return None
    if len(cleaned.split()) < 10 and len(re.findall(r"[가-힣]{2,}", cleaned)) < 8:
        return None
    return cleaned[:1200]
