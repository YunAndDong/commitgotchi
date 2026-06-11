from __future__ import annotations

from collections import Counter

from .schemas import KnowledgeChunk, ProblemCandidate, RagIndex, SearchHit
from .text import clean_markdown, extract_terms, infer_fields, term_counter


class KeywordRagSearcher:
    def __init__(self, rag_index: RagIndex):
        self.rag_index = rag_index
        self._chunk_terms = {
            chunk.chunk_id: term_counter(
                " ".join((*chunk.heading_path, chunk.source_path, chunk.text))
            )
            for chunk in rag_index.chunks
        }
        self._problem_terms = {
            problem.source_key: term_counter(
                " ".join(
                    (
                        problem.question,
                        problem.model_answer or "",
                        problem.field,
                        problem.source_path,
                    )
                )
            )
            for problem in rag_index.problems
        }

    def search_chunks(
        self,
        query: str,
        *,
        limit: int,
        min_score: float,
    ) -> list[SearchHit]:
        query_terms = extract_terms(query)
        query_fields = infer_fields(query)
        hits: list[SearchHit] = []
        for chunk in self.rag_index.chunks:
            score, matched_terms = self._score(
                query_terms,
                self._chunk_terms[chunk.chunk_id],
                clean_markdown(" ".join((*chunk.heading_path, chunk.text))),
            )
            if chunk.field in query_fields:
                score += 1.0
            if score >= min_score:
                hits.append(SearchHit(item=chunk, score=score, matched_terms=tuple(matched_terms)))
        return sorted(hits, key=lambda hit: hit.score, reverse=True)[:limit]

    def search_problems(
        self,
        query: str,
        *,
        limit: int,
        min_score: float,
        require_model_answer: bool = True,
    ) -> list[SearchHit]:
        query_terms = extract_terms(query)
        query_fields = infer_fields(query)
        hits: list[SearchHit] = []
        for problem in self.rag_index.problems:
            if require_model_answer and not problem.model_answer:
                continue
            score, matched_terms = self._score(
                query_terms,
                self._problem_terms[problem.source_key],
                clean_markdown(f"{problem.question} {problem.model_answer or ''}"),
            )
            if problem.field in query_fields:
                score += 1.5
            if score >= min_score:
                hits.append(SearchHit(item=problem, score=score, matched_terms=tuple(matched_terms)))
        return sorted(hits, key=lambda hit: hit.score, reverse=True)[:limit]

    def _score(
        self,
        query_terms: list[str],
        target_terms: Counter[str],
        target_text: str,
    ) -> tuple[float, list[str]]:
        if not query_terms:
            return 0.0, []
        score = 0.0
        matched_terms: list[str] = []
        lowered_target = target_text.lower()
        for term in query_terms:
            count = target_terms.get(term, 0)
            if count:
                score += min(count, 4) * 1.0
                matched_terms.append(term)
            elif term in lowered_target:
                score += 0.5
                matched_terms.append(term)
        unique_matches = len(set(matched_terms))
        if unique_matches >= 2:
            score += unique_matches * 0.75
        return score, sorted(set(matched_terms))
