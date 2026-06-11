from __future__ import annotations

from pathlib import Path
from typing import Any

from .config import RagPreviewSettings
from .loaders import load_sources, source_type_for
from .parsers import parse_sources
from .schemas import KnowledgeChunk, ProblemCandidate, SearchHit, problem_to_contract_dict
from .search import KeywordRagSearcher
from .text import extract_terms, infer_fields, make_excerpt, normalize_text


def build_preview_response(
    report_text: str,
    *,
    source_root: Path | None = None,
    max_concepts: int | None = None,
    max_quizzes: int | None = None,
    min_problem_score: float | None = None,
) -> dict[str, Any]:
    settings = RagPreviewSettings.from_env()
    if source_root is not None:
        settings = RagPreviewSettings(
            source_root=source_root,
            max_concepts=settings.max_concepts,
            max_quizzes=settings.max_quizzes,
            min_problem_score=settings.min_problem_score,
            min_concept_score=settings.min_concept_score,
        )

    normalized_report = normalize_text(report_text).strip()
    sources = [
        (relative_path, source_type_for(path), text)
        for path, relative_path, text in load_sources(settings.source_root)
    ]
    rag_index = parse_sources(sources)
    searcher = KeywordRagSearcher(rag_index)

    concept_limit = max_concepts or settings.max_concepts
    quiz_limit = max_quizzes or settings.max_quizzes
    problem_threshold = min_problem_score or settings.min_problem_score

    concept_hits = searcher.search_chunks(
        normalized_report,
        limit=concept_limit,
        min_score=settings.min_concept_score,
    )
    problem_hits = searcher.search_problems(
        normalized_report,
        limit=quiz_limit,
        min_score=problem_threshold,
        require_model_answer=True,
    )

    query_terms = extract_terms(normalized_report)
    detected_fields = infer_fields(normalized_report)

    return {
        "inputReport": normalized_report,
        "detectedKeywords": query_terms[:12],
        "detectedFields": detected_fields,
        "answer": _compose_answer(normalized_report, concept_hits, problem_hits),
        "nearbyConcepts": [
            _concept_to_dict(hit, query_terms)
            for hit in concept_hits
            if isinstance(hit.item, KnowledgeChunk)
        ],
        "recommendedQuizzes": [
            problem_to_contract_dict(hit.item, hit.score)
            for hit in problem_hits
            if isinstance(hit.item, ProblemCandidate)
        ],
        "debug": {
            "sourceRoot": settings.source_root.as_posix(),
            "loadedSources": len(sources),
            "chunkCount": len(rag_index.chunks),
            "problemCount": len(rag_index.problems),
            "minProblemScore": problem_threshold,
        },
    }


def _compose_answer(
    report_text: str,
    concept_hits: list[SearchHit],
    problem_hits: list[SearchHit],
) -> str:
    if not report_text:
        return "리포트 문장이 비어 있어 주변 개념이나 문제를 찾지 않았습니다."

    if not concept_hits:
        return (
            "제출한 리포트와 충분히 가까운 학습 자료를 찾지 못했습니다. "
            "이번 응답에서는 개념 설명과 문제 추천을 생략합니다."
        )

    top_concepts = [
        hit.item.title
        for hit in concept_hits[:3]
        if isinstance(hit.item, KnowledgeChunk)
    ]
    concept_phrase = ", ".join(top_concepts)
    if problem_hits:
        quiz_sentence = "가까운 기존 문제가 있어 추천 퀴즈 후보도 함께 반환했습니다."
    else:
        quiz_sentence = "가까운 기존 문제는 찾지 못해 이번 응답에서는 문제를 내지 않습니다."

    return (
        f"리포트 내용과 가까운 주변 개념은 {concept_phrase} 쪽입니다. "
        "아래 nearbyConcepts의 summary를 리포트 피드백이나 다음 학습 추천의 근거로 사용할 수 있습니다. "
        f"{quiz_sentence}"
    )


def _concept_to_dict(hit: SearchHit, query_terms: list[str]) -> dict[str, Any]:
    chunk = hit.item
    if not isinstance(chunk, KnowledgeChunk):
        raise TypeError("concept hit must wrap a KnowledgeChunk")
    return {
        "title": chunk.title,
        "summary": make_excerpt(chunk.text, query_terms),
        "field": chunk.field,
        "sourceType": chunk.source_type,
        "sourcePath": chunk.source_path,
        "matchScore": round(hit.score, 3),
        "matchedTerms": list(hit.matched_terms),
    }
