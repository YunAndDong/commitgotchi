---
title: Report 3 - RAG Search and Source Neighborhood
status: done
created: 2026-06-13
owner: FastAPI AI 서버
epic: report-generation
source_docs:
  - ../report-generation-epic.md
  - ../report-generation-sprint-status.yaml
  - ./report-1-concept-chunk-catalog.md
  - ./report-2-report-chunking-topic-field-hints.md
  - ../../quiz-bank/problem-bank-implementation.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4
  - https://ai.google.dev/gemini-api/docs/embeddings
---

# Report 3. RAG Search and Source Neighborhood

## Status

done

## 목표

Story 2의 `ReportChunk`마다 query embedding을 만들고, Story 1의 `fastapi/data/rag/catalog/chunks.jsonl` concept chunk에 대해 미리 생성해 둔 embedding index에서 semantic top-k를 검색한다. 검색된 knowledge chunk 주변의 출처 기반 컨텍스트를 확장한다.

최종 산출물은 후속 Story 4의 Gemini 리포트 분석에 그대로 넣을 수 있는 evidence bundle 목록이다. evidence는 단순 top-1 chunk가 아니라 embedding 검색 hit와 같은 문서의 이전/다음 chunk, 부모/형제 heading, 같은 폴더의 관련 문서까지 cap 안에서 포함해야 한다.

## 배경

Story 1은 `computer-science-interview-KR` Markdown을 `ConceptChunkRecord` catalog로 변환하고, `ConceptCatalogStore` loader/store를 구현했다. 완료 기록 기준으로 다음 패턴을 이미 사용할 수 있다.

- `fastapi/app/rag/concept_store.py`의 `read_concept_jsonl()`, `load_concept_catalog_store()`, `ConceptCatalogStore.get()`, `by_source_path()`, `filter_by_field_hint()`
- `fastapi/app/rag/schemas.py`의 `ConceptChunkRecord`, `ConceptNeighbors`, `validate_concept_chunk_record()`
- `fastapi/app/rag/text.py`의 `normalize_text`, `clean_markdown`, `extract_terms`, `term_counter`, `infer_fields`, `keyword_matches`, `make_excerpt`
- catalog의 stable `chunkId`, relative `sourcePath`, `headingPath`, `chunkIndex`, `fieldHints`, `neighbors.previousChunkId`, `neighbors.nextChunkId`, `neighbors.parentHeadingPath`, `neighbors.siblingHeadingPaths`

Story 2는 리포트 `title`/`content`를 deterministic `ReportChunk` 목록으로 나누었다. 이번 story는 이 결과를 입력으로 받는다.

- `fastapi/app/scoring/report_chunker.py`의 `chunk_daily_report()`
- `fastapi/app/scoring/schemas.py`의 `ReportChunk`
- `ReportChunk.report_chunk_id`는 `report:0`, `report:1`, ... 형식이며, RAG evidence bundle의 join key로 사용한다.
- `topicHints`와 `fieldHints`는 retrieval/filter 보조 정보다. 최종 점수로 직접 쓰면 안 된다.

Architecture 기준으로 이번 epic은 FastAPI 내부 Python 함수 중심이다. API endpoint, SQS consumer, Spring Boot callback, DB 직접 접근은 이번 story 범위가 아니다. FastAPI는 Spring Boot DB를 공유하지 않고, report callback 계약의 `scoreDelta`는 후속 Story 4/5에서 다룬다.

이번 story의 retrieval은 keyword-first가 아니라 embedding-first다. Google 공식 Gemini Embeddings 문서 기준으로 `GEMINI_API_KEY`를 `embedContent` 호출에 사용할 수 있고, 최신 stable embedding model은 `gemini-embedding-2`다. quiz grading에 쓰는 `GEMINI_QUIZ_GRADER_MODEL=gemini-3.1-flash-lite`는 생성 모델 설정이므로 embedding에는 재사용하지 않는다. 별도 환경변수를 둔다.

권장 `.env` 추가값:

```env
GEMINI_EMBEDDING_MODEL=gemini-embedding-2
GEMINI_EMBEDDING_DIMENSIONS=768
```

`gemini-embedding-2`의 default dimension은 3072이고 권장 축소 dimension에 768이 포함된다. MVP에서는 768차원을 기본으로 저장해 메모리 사용량과 검색 속도를 줄인다. chunk embedding과 query embedding은 같은 model/dimension으로 생성되어야 하며, model 또는 dimension이 바뀌면 전체 concept chunk를 다시 embed해야 한다.

## 구현 범위

이번 story에서 구현할 것은 concept chunk embedding 생성/저장, in-memory embedding 검색, source-neighborhood 확장, evidence bundle serialization이다.

1. Embedding 설정과 client
   - 기존 `GEMINI_API_KEY`를 사용한다.
   - `GEMINI_EMBEDDING_MODEL`은 기본값 `gemini-embedding-2`로 둔다.
   - `GEMINI_EMBEDDING_DIMENSIONS`는 기본값 `768`로 둔다.
   - `GEMINI_QUIZ_GRADER_MODEL`은 사용하지 않는다.
   - `google-genai` 패키지를 사용한다. 이미 `fastapi/requirements.txt`에 존재한다.
   - 테스트에서는 실제 Gemini API를 호출하지 않고 fake embedder를 주입한다.
   - embedding client interface는 document embedding과 query embedding을 분리해 호출할 수 있게 한다. `gemini-embedding-2`는 task type parameter 대신 text instruction/prefix를 쓰는 방향을 고려한다.

2. Concept embedding index 생성
   - `chunks.jsonl`을 읽어 concept chunk별 document embedding을 생성한다.
   - embedding 입력은 `headingPath`, `sourcePath`, `fieldHints`, `text`를 조합한다.
   - 저장 record에는 `chunkId`, `contentHash`, `model`, `outputDimensionality`, `sourcePath`, `headingPath`, `embedding`을 포함한다.
   - `contentHash`가 Story 1의 chunk와 다르면 stale embedding으로 간주한다.
   - 동일 chunk/model/dimension/contentHash가 이미 있으면 재사용하고, 누락/stale record만 재생성할 수 있게 한다.
   - 저장 파일은 MVP에서 JSONL을 우선한다. 예: `fastapi/data/rag/catalog/chunk-embeddings.jsonl`
   - JSONL vector는 크기가 커질 수 있지만 현재 chunk 수가 많지 않으므로 디버깅과 단순성을 우선한다. 필요해지면 후속 story에서 `.npz` 또는 pgvector로 이전한다.
   - API rate limit을 고려해 chunk 단위 실패를 report에 남기고 전체 build가 어디까지 진행됐는지 알 수 있게 한다.

3. In-memory embedding store
   - app 시작 또는 검색 시점에 `chunks.jsonl`과 `chunk-embeddings.jsonl`을 함께 로드한다.
   - `chunkId`로 `ConceptChunkRecord`와 embedding record를 join한다.
   - model, dimension, contentHash mismatch record는 검색 대상에서 제외하고 issue로 수집한다.
   - 작은 규모에서는 모든 vector를 메모리에 올려 전수 cosine similarity를 계산한다.
   - PostgreSQL/pgvector는 이번 story에서 사용하지 않는다.

4. Concept embedding 검색기
   - `ConceptCatalogStore`를 입력으로 받는 in-memory 검색기를 만든다.
   - 새 검색기는 `problems.jsonl` 문제 은행 검색과 분리한다.
   - 검색 알고리즘은 Gemini embedding cosine similarity를 기본으로 한다.
   - query embedding 입력은 `ReportChunk.text`, `topic_hints`, `field_hints`를 함께 사용한다.
   - cosine score는 normalized vector dot product로 계산한다. vector normalization이 보장되지 않는 경우 Python에서 정규화한다.
   - `fieldHints`, `matchedTerms`, keyword overlap은 tie-breaker 또는 보조 boost로만 사용한다.
   - embedding index가 없거나 Gemini query embedding 호출에 실패하면 keyword fallback을 사용할 수 있다. fallback도 evidence bundle을 반환해야 한다.

5. Report chunk별 retrieval
   - report chunk마다 독립적으로 top-k 검색을 수행한다.
   - public 함수는 개별 chunk와 chunk list 둘 다 지원하는 형태가 좋다.
   - 같은 입력, 같은 catalog에서는 hit 순서와 score가 deterministic해야 한다.
   - `limit <= 0`, 빈 query, 빈 catalog, 빈 embedding index는 예외 대신 빈 결과 또는 keyword fallback 결과를 반환한다.
   - default catalog를 암묵적으로 강제하지 않는다. 테스트에서는 명시 store를 주입할 수 있어야 한다.

6. Search hit shape
   - hit에는 최소한 `chunkId`, `score`, `searchMode`, `matchedTerms`, `sourcePath`, `headingPath`, `text`, `fieldHints`를 포함한다.
   - score는 내부 ranking용 float다. 후속 Gemini prompt에 넣기 전 `round(score, 3)`처럼 안정적으로 직렬화한다.
   - text는 원문 chunk 전체를 유지하되, evidence bundle cap 계산에 포함한다.

7. Source-neighborhood 확장
   - 검색 hit를 seed로 삼아 출처 기반 후보를 확장한다.
   - 같은 문서의 `neighbors.previousChunkId`와 `neighbors.nextChunkId`를 우선 포함한다.
   - `neighbors.parentHeadingPath`에 해당하는 같은 문서 chunk를 포함할 수 있다.
   - `neighbors.siblingHeadingPaths` 또는 같은 parent heading 아래의 sibling chunk를 포함할 수 있다.
   - 같은 `sourcePath` 안에서 `chunkIndex`가 seed 주변인 chunk를 fallback neighborhood로 사용할 수 있다.
   - 같은 폴더의 관련 문서는 field/topic/matched term 신호가 있을 때만 보조 컨텍스트로 포함한다.
   - neighborhood 후보는 `reason`을 명시한다. 예: `previous_chunk`, `next_chunk`, `parent_heading`, `sibling_heading`, `same_source_nearby`, `same_folder_related`.
   - 중복 `chunkId`는 한 번만 포함하고, matches에 이미 들어간 chunk는 neighborhood에서 제외하거나 reason merge로 중복 직렬화를 피한다.

8. Context cap
   - evidence bundle은 Gemini 입력에 넣기 쉬운 크기로 제한한다.
   - MVP에서는 tokenizer 없이 character cap으로 충분하다.
   - 권장 기본값:
     - `top_k=5`
     - `max_neighborhood_chunks=8`
     - `max_bundle_chars=6000`
     - `max_text_chars_per_item=1200`
   - cap 적용 순서는 deterministic해야 한다. 권장 우선순위는 match score 높은 순서, previous/next, parent/sibling, same-source nearby, same-folder related 순서다.
   - cap 때문에 제외한 후보가 있다면 debug/test용 count를 남길 수 있으나 필수 payload에는 과도한 내부 정보를 넣지 않는다.

9. Evidence bundle 생성
   - 후속 Story 4가 Gemini prompt에 바로 넣을 수 있는 dict serialization을 제공한다.
   - 검색 실패, catalog 없음, query embedding 실패, query terms 없음은 실패가 아니라 빈 evidence bundle 또는 keyword fallback evidence bundle이다.
   - bundle에는 후속 scoring 필드(`scoreDelta`, `emotion`, `dailyReport`, `recommendedQuizzes`)를 넣지 않는다.

## 주요 파일 경로

구현 대상:

- `fastapi/app/rag/concept_embeddings.py`
- `fastapi/app/rag/build_concept_embeddings.py`
- `fastapi/app/rag/embedding_store.py`
- `fastapi/app/rag/concept_search.py`
- `fastapi/app/rag/source_neighborhood.py`
- `fastapi/app/rag/schemas.py`

수정 가능하지만 조심할 파일:

- `fastapi/app/rag/concept_store.py`
  - 기존 `ConceptCatalogStore` 조회 기능이 부족할 때만 작은 helper를 추가한다.
  - 기존 loader의 파일 없음/빈 파일/strict 동작을 깨면 안 된다.
- `fastapi/app/rag/text.py`
  - 검색에 필요한 공통 helper가 명확히 중복될 때만 추가한다.

테스트 대상:

- `fastapi/tests/rag/test_concept_embeddings.py`
- `fastapi/tests/rag/test_embedding_store.py`
- `fastapi/tests/rag/test_concept_search.py`
- `fastapi/tests/rag/test_source_neighborhood.py`
- 필요 시 `fastapi/tests/rag/test_concept_store.py`

재사용 fixture:

- `fastapi/tests/fixtures/rag/concept_catalog/`
- 테스트에서 실제 `/Users/tlsdla1235/study/md파일들/computer-science-interview-KR` 전체를 읽지 않는다.

입력 catalog:

- `fastapi/data/rag/catalog/chunks.jsonl`

생성 embedding artifact:

- `fastapi/data/rag/catalog/chunk-embeddings.jsonl`
- `fastapi/data/rag/manifests/concept-embeddings-manifest.json`
- `fastapi/data/rag/reports/concept-embeddings-summary.json`
- `fastapi/data/rag/reports/concept-embeddings-summary.md`

참고만 할 기존 검색 구현:

- `fastapi/app/rag/problem_bank_search.py`
- `fastapi/app/rag/search.py`
- `fastapi/tests/rag/test_problem_bank_search.py`

수정하지 않아야 할 흐름:

- `fastapi/app/rag/problem_bank.py`
- `fastapi/app/rag/build_problem_bank.py`
- `fastapi/app/rag/problem_bank_store.py`
- `fastapi/app/rag/daily_report_recommender.py`
- `fastapi/app/rag/quiz_recommender.py`
- `fastapi/app/scoring/report_chunker.py`의 chunking semantics

## 권장 데이터 Shape

권장 dataclass는 `fastapi/app/rag/schemas.py`에 두거나, 더 응집도가 좋으면 `concept_search.py`/`source_neighborhood.py`에 둔다. 기존 프로젝트 패턴에 맞춰 dataclass와 `to_dict()`를 우선한다.

```python
@dataclass(frozen=True)
class ConceptEmbeddingRecord:
    chunk_id: str
    content_hash: str
    model: str
    output_dimensionality: int
    source_path: str
    heading_path: tuple[str, ...]
    embedding: tuple[float, ...]

@dataclass(frozen=True)
class ConceptSearchHit:
    chunk: ConceptChunkRecord
    score: float
    search_mode: str
    matched_terms: tuple[str, ...]

@dataclass(frozen=True)
class EvidenceMatch:
    chunk_id: str
    score: float
    matched_terms: tuple[str, ...]
    source_path: str
    heading_path: tuple[str, ...]
    text: str
    field_hints: tuple[str, ...]

@dataclass(frozen=True)
class NeighborhoodEvidence:
    reason: str
    chunk_id: str
    source_path: str
    heading_path: tuple[str, ...]
    text: str
    field_hints: tuple[str, ...]

@dataclass(frozen=True)
class ReportEvidenceBundle:
    report_chunk_id: str
    query_text: str
    topic_hints: tuple[str, ...]
    field_hints: tuple[str, ...]
    matches: tuple[EvidenceMatch, ...]
    neighborhood: tuple[NeighborhoodEvidence, ...]
```

권장 public 함수:

```python
class EmbeddingClient(Protocol):
    def embed_document(self, text: str) -> tuple[float, ...]:
        ...

    def embed_query(self, text: str) -> tuple[float, ...]:
        ...

def build_concept_embeddings(
    *,
    catalog_path: Path = DEFAULT_CATALOG_PATH,
    embeddings_path: Path = DEFAULT_EMBEDDINGS_PATH,
    client: EmbeddingClient | None = None,
    model: str = "gemini-embedding-2",
    output_dimensionality: int = 768,
) -> ConceptEmbeddingBuildResult:
    ...

def load_concept_embedding_store(
    *,
    store: ConceptCatalogStore,
    embeddings_path: Path = DEFAULT_EMBEDDINGS_PATH,
    expected_model: str | None = None,
    expected_dimensionality: int | None = None,
) -> ConceptEmbeddingStore:
    ...

def search_concept_chunks(
    query: str,
    *,
    store: ConceptCatalogStore | None = None,
    embedding_store: ConceptEmbeddingStore | None = None,
    client: EmbeddingClient | None = None,
    topic_hints: tuple[str, ...] = (),
    field_hints: tuple[str, ...] = (),
    limit: int = 3,
    min_score: float = 1.0,
    allow_keyword_fallback: bool = True,
) -> list[ConceptSearchHit]:
    ...

def build_source_neighborhood(
    hits: list[ConceptSearchHit],
    *,
    store: ConceptCatalogStore,
    query_terms: tuple[str, ...] = (),
    field_hints: tuple[str, ...] = (),
    max_chunks: int = 8,
    max_chars: int = 6000,
    max_text_chars_per_item: int = 1200,
) -> list[NeighborhoodEvidence]:
    ...

def build_report_evidence_bundle(
    report_chunk: ReportChunk,
    *,
    store: ConceptCatalogStore | None = None,
    embedding_store: ConceptEmbeddingStore | None = None,
    client: EmbeddingClient | None = None,
    top_k: int = 5,
    max_neighborhood_chunks: int = 8,
    max_bundle_chars: int = 6000,
    max_text_chars_per_item: int = 1200,
    allow_keyword_fallback: bool = True,
) -> ReportEvidenceBundle:
    ...

def build_report_evidence_bundles(
    report_chunks: list[ReportChunk],
    *,
    store: ConceptCatalogStore | None = None,
    embedding_store: ConceptEmbeddingStore | None = None,
    client: EmbeddingClient | None = None,
) -> list[ReportEvidenceBundle]:
    ...
```

권장 JSON shape:

```json
{
  "reportChunkId": "report:0",
  "query": {
    "text": "JPA N+1이 Lazy Loading에서 왜 발생하는지 정리했다.",
    "topicHints": ["JPA N+1", "Lazy Loading"],
    "fieldHints": ["db", "framework"]
  },
  "matches": [
    {
      "chunkId": "concept:sha256:...",
      "score": 8.25,
      "searchMode": "embedding",
      "matchedTerms": ["jpa", "n+1", "lazy", "loading"],
      "sourcePath": "03-framework/jpa.md",
      "headingPath": ["Framework", "JPA", "N+1"],
      "fieldHints": ["db", "framework"],
      "text": "..."
    }
  ],
  "neighborhood": [
    {
      "reason": "previous_chunk",
      "chunkId": "concept:sha256:...",
      "sourcePath": "03-framework/jpa.md",
      "headingPath": ["Framework", "JPA", "Fetch Join"],
      "fieldHints": ["db", "framework"],
      "text": "..."
    }
  ]
}
```

Embedding JSONL record shape:

```json
{
  "chunkId": "concept:sha256:...",
  "contentHash": "sha256:...",
  "model": "gemini-embedding-2",
  "outputDimensionality": 768,
  "sourcePath": "03-framework/jpa.md",
  "headingPath": ["Framework", "JPA", "N+1"],
  "embedding": [0.0123, -0.0456]
}
```

## Acceptance Criteria

- `GEMINI_API_KEY`를 사용해 concept chunk embedding을 생성할 수 있다.
- embedding model은 `GEMINI_EMBEDDING_MODEL`로 설정하며 기본값은 `gemini-embedding-2`다.
- embedding dimension은 `GEMINI_EMBEDDING_DIMENSIONS`로 설정하며 기본값은 `768`이다.
- `GEMINI_QUIZ_GRADER_MODEL`은 embedding 생성이나 검색에 사용하지 않는다.
- `chunk-embeddings.jsonl`은 `chunkId`, `contentHash`, `model`, `outputDimensionality`, `embedding`을 포함한다.
- embedding store는 `chunks.jsonl`과 `chunk-embeddings.jsonl`을 `chunkId`로 join한다.
- `contentHash`, model, dimension mismatch embedding은 stale로 처리하고 검색 대상에서 제외한다.
- 각 `ReportChunk`마다 독립적으로 retrieval이 수행된다.
- retrieval 결과는 `reportChunkId`별 evidence bundle로 반환된다.
- 검색 결과에는 `chunkId`, `score`, `searchMode`, `matchedTerms`, `sourcePath`, `headingPath`, `text`, `fieldHints`가 포함된다.
- 검색 query는 `ReportChunk.text`, `topicHints`, `fieldHints`를 사용한다.
- 기본 검색 mode는 embedding cosine similarity다.
- keyword/token 검색은 fallback 또는 tie-breaker/boost로만 사용한다.
- 검색 ranking은 deterministic하다.
- `fieldHints`는 ranking/filter 보조로만 사용하고 `scoreDelta`를 만들지 않는다.
- `ConceptCatalogStore`와 Story 1 loader를 재사용한다.
- catalog 파일 없음, embedding 파일 없음, 빈 catalog, 빈 query, 검색 결과 없음은 예외가 아니라 빈 `matches`/`neighborhood` evidence bundle 또는 keyword fallback으로 처리된다.
- source-neighborhood 확장은 벡터 유사도만 사용하지 않고 `sourcePath`, `headingPath`, `neighbors`, `chunkIndex` 구조를 사용한다.
- 같은 문서의 이전/다음 chunk가 존재하면 cap 안에서 neighborhood에 포함될 수 있다.
- parent heading 또는 sibling heading 후보가 존재하면 cap 안에서 neighborhood에 포함될 수 있다.
- 같은 폴더의 관련 문서는 field/topic/matched term 신호가 있을 때만 cap 안에서 보조 컨텍스트로 포함된다.
- evidence bundle은 `top_k`, `max_neighborhood_chunks`, `max_bundle_chars`, `max_text_chars_per_item` 같은 cap을 지킨다.
- 같은 `chunkId`가 matches/neighborhood에 중복 직렬화되지 않는다.
- JSON/dict serialization은 후속 Gemini prompt에 바로 넣을 수 있는 camelCase key를 제공한다.
- 기존 `problems.jsonl` 문제 은행 검색과 `daily_report_recommender.py` 추천 contract를 변경하지 않는다.
- Gemini embedding 호출은 embedding 생성/query embedding에만 사용한다.
- Gemini text generation, report analysis, pgvector, API/SQS/Spring Boot callback을 구현하지 않는다.

## 테스트 기준

필수 테스트:

- fake embedding client로 concept embedding JSONL을 생성할 수 있는지 검증한다.
- 생성된 embedding record가 `chunkId`, `contentHash`, `model`, `outputDimensionality`, `embedding`을 포함하는지 검증한다.
- contentHash/model/dimension mismatch record가 stale issue로 수집되고 검색에서 제외되는지 검증한다.
- 실제 Gemini API 호출 없이 fake embedding client만으로 테스트가 통과하는지 검증한다.
- fixture catalog store로 `JPA N+1`, `Lazy Loading`, `fetch join` query가 관련 concept chunk를 top result로 찾는지 검증한다.
- 검색 결과의 기본 `searchMode`가 `embedding`인지 검증한다.
- embedding store가 없거나 query embedding client가 실패할 때 `allow_keyword_fallback=True`이면 keyword fallback 결과 또는 안전한 빈 bundle을 반환하는지 검증한다.
- `report_chunk_id="report:0"`와 `report_chunk_id="report:1"`인 `ReportChunk`에 대해 서로 다른 evidence bundle이 생성되고 `reportChunkId`가 보존되는지 검증한다.
- 검색 hit가 `sourcePath`, `headingPath`, `chunkId`, `score`, `searchMode`, `matchedTerms`를 포함하는지 검증한다.
- 같은 `sourcePath` 안의 `previousChunkId`와 `nextChunkId`가 neighborhood로 확장되는지 검증한다.
- 같은 parent heading 아래 sibling chunk가 `sibling_heading` 또는 동등한 reason으로 포함되는지 검증한다.
- 같은 폴더의 관련 문서가 field/topic/matched term 신호가 있을 때만 `same_folder_related` 후보로 선택되는지 검증한다.
- `top_k`가 1이면 match가 1개 이하로 제한되는지 검증한다.
- `max_neighborhood_chunks`와 `max_bundle_chars`가 지켜지는지 검증한다.
- duplicate `chunkId`가 matches/neighborhood에 중복되지 않는지 검증한다.
- 빈 `ConceptCatalogStore()`는 빈 evidence bundle을 반환하는지 검증한다.
- missing/empty catalog path를 통한 `load_concept_catalog_store()` 조합도 crash하지 않는지 smoke test한다.
- `fieldHints`가 있어도 evidence bundle에 `scoreDelta`, `scoreAllocation`, `emotion`, `dailyReport`, `recommendedQuizzes`가 생기지 않는지 검증한다.
- 기존 `tests.rag.test_concept_store`, `tests.scoring.test_report_chunker`, 문제 은행 검색 테스트가 깨지지 않는지 회귀 확인한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.rag.test_concept_embeddings tests.rag.test_embedding_store tests.rag.test_concept_search tests.rag.test_source_neighborhood
```

관련 회귀 확인:

```bash
cd fastapi
python3 -m unittest tests.rag.test_concept_store tests.scoring.test_report_chunker tests.rag.test_problem_bank_search
```

전체 회귀 확인:

```bash
cd fastapi
python3 -m unittest discover -s tests
```

## 제외 범위

이번 story에서 하지 않는다.

- `chunks.jsonl` catalog 재생성 로직 변경
- 실제 source root 전체를 unit test fixture로 사용
- `problems.jsonl` 문제 은행 검색 로직 변경
- 기존 quiz recommender 또는 daily report recommender 수정
- Gemini text generation 또는 report analysis 호출
- prompt 작성 또는 model output parsing
- pgvector, 운영 vector index, DB persistence
- 최종 `scoreDelta` 산정
- `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes` 생성
- API endpoint
- SQS consumer
- Spring Boot callback 전송
- requestId 멱등성 저장소, 재시도, DLQ 처리

## 개발 메모

- `ProblemBankSearcher`의 deterministic ranking/tie-breaker 패턴은 참고만 한다. 이번 story의 기본 retrieval은 embedding cosine similarity다.
- `fastapi/app/rag/search.py`의 `KeywordRagSearcher`는 legacy/general search 성격이다. Story 1의 `ConceptChunkRecord`와 embedding sidecar를 직접 다루는 `concept_search.py`/`embedding_store.py`를 새로 두는 편이 명확하다.
- document embedding 입력을 만들 때 heading/source/field/text를 함께 넣는다. query embedding 입력에는 report chunk text와 topic/field hints를 함께 넣는다.
- `gemini-embedding-2`는 embedding space가 다른 model과 호환되지 않는다. model 또는 dimension을 바꾸면 기존 `chunk-embeddings.jsonl`을 전부 재생성해야 한다.
- `chunk-embeddings.jsonl`에는 API key나 원문 전체를 중복 저장하지 않는다. 원문 text는 `chunks.jsonl`의 `chunkId` join으로 가져온다.
- JSONL vector 저장은 단순하지만 파일 크기가 커진다. 현재 chunk 수가 작다는 전제의 MVP 선택이며, 후속 규모 증가 시 `.npz`, FAISS, pgvector 등으로 이전할 수 있게 loader/searcher 경계를 분리한다.
- query text를 만들 때 topic hints를 별도 prefix로 반영하면 `JPA N+1`, `OAuth/JWT`, `프로세스/스레드` 같은 복합 표현이 embedding 입력에서도 보존된다.
- `infer_fields()`는 신호가 없으면 `["cs"]` fallback을 반환할 수 있다. 이번 story에서는 fallback 때문에 `cs` hit가 과하게 boost되지 않도록 `ReportChunk.field_hints`와 실제 keyword evidence를 우선한다.
- same-folder related는 과하면 prompt noise가 된다. field/topic/matched term이 겹치지 않는 같은 폴더 chunk는 포함하지 않는다.
- neighborhood ordering은 후속 Gemini prompt 품질에 영향을 준다. 가까운 출처 구조와 강한 검색 hit 주변을 먼저 넣고, 같은 폴더 문서는 뒤에 둔다.
- cap은 byte offset이 아니라 Python string length 기준 character cap으로 충분하다. 후속 LLM story에서 모델 tokenizer 기준 cap이 필요하면 교체 가능하게 상수/인자로 둔다.
- `sourcePath`는 Story 1 기준 상대 경로다. 절대 경로 비교나 OS separator 비교에 기대지 말고 `PurePosixPath` 또는 `/` 기준 문자열 처리를 사용한다.
- 테스트 fixture는 Story 1의 concept catalog fixture를 복사해서 임시 catalog를 빌드하는 패턴을 재사용한다. 실제 catalog가 없어도 unit test가 통과해야 한다.
- 검색 결과와 neighborhood serialization은 내부 dataclass에서 snake_case를 쓰더라도 `to_dict()`는 기존 JSON style처럼 camelCase를 사용한다.
- 이번 story의 evidence는 grounding 자료다. 후속 Story 4에서 Gemini가 점수와 문안을 만들기 전까지 어떤 scoring payload도 만들지 않는다.

## Tasks/Subtasks

- [x] Gemini embedding settings와 `EmbeddingClient` protocol 설계
- [x] `GeminiEmbeddingClient` 구현
- [x] `ConceptEmbeddingRecord`와 embedding JSONL serialization/validation 구현
- [x] `build_concept_embeddings()`와 CLI 구현
- [x] stale embedding detection(`contentHash`, model, dimension mismatch) 구현
- [x] `ConceptEmbeddingStore` loader와 issue collection 구현
- [x] `ConceptSearchHit`와 evidence bundle dataclass 및 `to_dict()` serialization 설계
- [x] embedding cosine similarity 기반 `ConceptCatalogSearcher` 또는 `search_concept_chunks()` 구현
- [x] `ReportChunk` 기반 query embedding input assembly 구현
- [x] keyword fallback과 field/topic hint tie-breaker 구현
- [x] previous/next chunk neighborhood 확장 구현
- [x] parent/sibling heading neighborhood 확장 구현
- [x] same-source nearby fallback 구현
- [x] same-folder related 후보 선택 구현
- [x] context cap과 duplicate 제거 구현
- [x] 빈 catalog/빈 embedding index/빈 query/검색 결과 없음 fallback 구현
- [x] `build_report_evidence_bundle()`와 `build_report_evidence_bundles()` 구현
- [x] concept embedding unit test 작성
- [x] embedding store unit test 작성
- [x] concept search unit test 작성
- [x] source neighborhood unit test 작성
- [x] 관련 rag/scoring 회귀 테스트 실행
- [x] Story 3 범위 밖 필드(`scoreDelta`, Gemini output, recommended quizzes)가 생성되지 않는지 확인

## Dev Agent Record

### Debug Log

- RED: `cd fastapi && python3 -m unittest tests.rag.test_concept_embeddings tests.rag.test_embedding_store tests.rag.test_concept_search tests.rag.test_source_neighborhood` 실행 시 신규 모듈 import 실패를 확인했다.
- GREEN: fake embedding client 기반 신규 테스트 12개가 통과했다.
- 회귀: `tests.rag.test_concept_store`, `tests.scoring.test_report_chunker`, `tests.rag.test_problem_bank_search` 19개가 통과했다.
- 전체: `python3 -m unittest discover -s tests` 77개가 통과했다.
- 실제 embedding build: `gemini-embedding-2` 768차원으로 870개 chunk embedding을 생성했고 실패 0개, store issue 0개를 확인했다.
- REVIEW FIX: embedding env metadata 불일치와 stale chunk keyword fallback 회귀 테스트를 추가했고, 신규 테스트 14개와 전체 테스트 79개가 통과했다.

### Implementation Plan

- Gemini embedding 설정/client와 embedding JSONL record를 기존 dataclass/JSONL 패턴으로 추가했다.
- `chunks.jsonl`과 embedding sidecar를 `chunkId`로 join하고 contentHash/model/dimension mismatch를 stale issue로 수집해 검색에서 제외했다.
- embedding cosine similarity를 기본 ranking으로 사용하고, keyword search는 embedding index 없음 또는 query embedding 실패 시 fallback으로만 사용했다.
- 검색 hit에서 previous/next, parent/sibling heading, same-source nearby, same-folder related 순서로 source-neighborhood를 확장하고 character cap과 duplicate 제거를 적용했다.

### Completion Notes

- Story 3 RAG retrieval 파이프라인을 FastAPI 내부 함수 중심으로 구현했다.
- `GEMINI_API_KEY`, `GEMINI_EMBEDDING_MODEL`, `GEMINI_EMBEDDING_DIMENSIONS` 기반 Gemini embedding client를 추가했고, `GEMINI_QUIZ_GRADER_MODEL`은 embedding 경로에서 사용하지 않는다.
- `build_concept_embeddings()`는 existing matching embedding을 재사용하고 missing/stale chunk만 재생성하며 기본 sidecar 경로는 `fastapi/data/rag/catalog/chunk-embeddings.jsonl`이다.
- `ConceptEmbeddingStore`는 stale/missing/unknown embedding issue를 수집하고 유효한 vector만 메모리에 올려 cosine 전수 검색 대상으로 사용한다.
- `build_report_evidence_bundle()`/`build_report_evidence_bundles()`는 Story 4 Gemini prompt에 바로 넣을 수 있는 camelCase evidence bundle을 반환하며 `scoreDelta`, Gemini report output, `recommendedQuizzes`를 포함하지 않는다.
- 실제 Gemini API 호출은 테스트에서 수행하지 않았고, 모든 신규 테스트는 fake embedding client로 검증했다.
- Story 4 준비용 실제 sidecar `fastapi/data/rag/catalog/chunk-embeddings.jsonl`을 생성했다.
- Review fix로 embedding 생성 metadata가 `GEMINI_EMBEDDING_MODEL`/`GEMINI_EMBEDDING_DIMENSIONS`와 일치하도록 정리했고, stale embedding chunk는 keyword fallback에서도 제외한다.

## File List

- `fastapi/app/rag/concept_embeddings.py`
- `fastapi/app/rag/build_concept_embeddings.py`
- `fastapi/app/rag/embedding_store.py`
- `fastapi/app/rag/concept_search.py`
- `fastapi/app/rag/source_neighborhood.py`
- `fastapi/app/rag/schemas.py`
- `fastapi/tests/rag/test_concept_embeddings.py`
- `fastapi/tests/rag/test_embedding_store.py`
- `fastapi/tests/rag/test_concept_search.py`
- `fastapi/tests/rag/test_source_neighborhood.py`
- `fastapi/data/rag/catalog/chunk-embeddings.jsonl`
- `fastapi/docs/report/stories/report-3-rag-search-and-source-neighborhood.md`
- `fastapi/docs/report/report-generation-sprint-status.yaml`

## Change Log

- 2026-06-13: Story 3 context document created and marked ready-for-dev.
- 2026-06-13: Implemented embedding-first concept retrieval, source-neighborhood evidence bundles, and fake-client unit tests; marked ready for review.
- 2026-06-13: Generated real `gemini-embedding-2` 768-dimensional concept embedding sidecar for Story 4 preparation.
- 2026-06-13: Raised default evidence match cap from `top_k=3` to `top_k=5` so mixed-topic reports can carry secondary concepts such as SSE alongside JWT.
- 2026-06-13: Applied review fixes for embedding config consistency and stale fallback exclusion; marked done.
