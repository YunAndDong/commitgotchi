---
title: 일일 리포트 분석 및 추천 생성 Epic
status: planned
created: 2026-06-13
owner: FastAPI AI 서버
scope: 내부 서비스 함수 중심, API/SQS/Spring Boot 콜백 제외
related_docs:
  - fastapi/docs/report/README.md
  - fastapi/docs/report/report-quiz-scoring-plan.md
  - fastapi/docs/quiz-bank/problem-bank-implementation.md
  - fastapi/docs/quiz-bank/quiz-grading-service-plan.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4
---

# 일일 리포트 분석 및 추천 생성 Epic

## 1. 목적

FastAPI AI 서버 안에서 사용자의 일일 학습 리포트를 분석하고, Spring Boot 리포트 콜백 계약에 붙이기 쉬운 결과를 만드는 내부 서비스 함수들을 설계한다.

이번 epic은 API 엔드포인트, SQS consumer, Spring Boot 콜백 전송까지 한 번에 구현하지 않는다. 우선 파일 기반 RAG catalog, 리포트 chunking, chunk별 retrieval, 출처 기반 주변 컨텍스트 확장, Gemini 기반 분석, 기존 퀴즈 추천 함수 연결을 내부 함수 단위로 완성한다.

최종 내부 결과는 다음 shape를 쉽게 만들 수 있어야 한다.

```json
{
  "scoreDelta": { "db": 0, "algorithm": 0, "cs": 0, "network": 0, "framework": 0 },
  "emotion": "JOY",
  "statusMessage": "오늘 학습 기록이 알찼어요!",
  "dailyReport": {
    "text": "오늘 학습은 ...",
    "feedback": "학습 강점: ... 보완점: ..."
  },
  "nextRecommendation": {
    "topics": ["..."],
    "rationale": "..."
  },
  "recommendedQuizzes": []
}
```

## 2. 현재 기준

완료된 것으로 간주한다.

- 정적 문제 은행 생성
- `fastapi/data/rag/catalog/problems.jsonl`
- 문제 은행 loader/search
- 리포트 기반 퀴즈 추천 내부 함수
- Gemini 기반 퀴즈 채점 서비스 함수

아직 미구현이다.

- 리포트 분석
- 리포트 점수화
- 리포트 콜백 오케스트레이션
- 리포트용 개념 RAG catalog

## 3. 핵심 원칙

- RAG 원천은 MVP에서 `/Users/tlsdla1235/study/md파일들/computer-science-interview-KR`로 제한한다.
- Markdown 문서는 개념 chunk로 변환해 FastAPI 내부 파일인 `fastapi/data/rag/catalog/chunks.jsonl`에 저장한다.
- 사용자의 리포트는 여러 주제를 담을 수 있으므로 리포트 텍스트를 여러 report chunk로 나눈다.
- 각 report chunk마다 관련 knowledge chunk를 검색한다.
- 피드백과 다음 학습 추천은 단순 top-1 검색 결과만 보고 만들지 않는다. 검색된 chunk, 원문 출처, 같은 문서의 주변 heading/chunk, 필요 시 인접 문서 또는 같은 카테고리 문서를 함께 고려한다.
- 여기서 "주변"은 벡터 거리만이 아니라 `sourcePath`, `headingPath` 부모/형제, 문서 내 이전/다음 heading, 같은 폴더의 관련 문서 같은 출처 기반 인접 컨텍스트를 의미한다.
- Gemini는 리포트 분석, 피드백, 추천 문안 생성에 사용하되 모델 출력은 신뢰하지 않는다.
- 서버는 `scoreDelta`를 필드별 `0..10`으로 clamp하고 schema validation을 반드시 수행한다.
- 리포트 분석 점수는 리포트 내용 자체만 기준으로 산정한다.
- 리포트 점수와 퀴즈 채점 점수는 이중계상하지 않는다.
- `recommendedQuizzes`는 기존 퀴즈 추천 함수 결과를 사용한다.
- pgvector, 운영 embedding store, API/SQS/Spring Boot 콜백 전송은 이번 epic에서 제외한다.

## 4. Story 목록

권장 구현 순서:

1. `computer-science-interview-KR` 개념 chunk catalog 생성
2. 리포트 chunking 및 주제/필드 후보 추출
3. report chunk별 RAG 검색과 source-neighborhood 확장
4. Gemini 기반 리포트 분석/피드백/`scoreDelta` 생성
5. 리포트 결과 조립 및 기존 quiz recommender 연결

---

## Story 1. `computer-science-interview-KR` 개념 chunk catalog 생성

### 목표

`/Users/tlsdla1235/study/md파일들/computer-science-interview-KR` 하위 Markdown 문서를 FastAPI 내부 RAG 원천으로 사용할 수 있도록 개념 chunk catalog를 생성한다.

최종 산출물은 `fastapi/data/rag/catalog/chunks.jsonl`이며, 각 chunk는 원문 출처와 heading 구조를 보존해야 한다.

### 구현 범위

- 지정된 source root 하위 Markdown 파일 discovery
- Markdown heading 기반 개념 chunk 분리
- chunk별 stable id, source path, heading path, 문서 내 순서, content hash 생성
- 이전/다음 chunk, 부모 heading, 형제 heading을 추적할 수 있는 메타데이터 저장
- 너무 짧거나 비어 있는 chunk skip
- catalog 생성 summary/rejected/skipped 리포트 생성
- MVP 검색을 위한 normalized text 또는 keyword tokens 저장

권장 chunk 필드:

```json
{
  "chunkId": "concept:...",
  "sourcePath": "Database/Transaction.md",
  "sourceRoot": "/Users/tlsdla1235/study/md파일들/computer-science-interview-KR",
  "headingPath": ["Database", "Transaction", "ACID"],
  "headingLevel": 3,
  "chunkIndex": 12,
  "text": "...",
  "contentHash": "...",
  "fieldHints": ["db"],
  "neighbors": {
    "previousChunkId": "...",
    "nextChunkId": "...",
    "parentHeadingPath": ["Database", "Transaction"]
  }
}
```

### 주요 파일 경로

- 원천: `/Users/tlsdla1235/study/md파일들/computer-science-interview-KR`
- 생성 catalog: `fastapi/data/rag/catalog/chunks.jsonl`
- 생성 manifest: `fastapi/data/rag/manifests/concept-catalog-discovery.json`
- 생성 summary: `fastapi/data/rag/reports/concept-catalog-summary.md`
- 구현 후보: `fastapi/app/rag/concept_catalog.py`
- 구현 후보: `fastapi/app/rag/build_concept_catalog.py`
- 테스트 후보: `fastapi/tests/rag/test_concept_catalog.py`

### Acceptance Criteria

- source root는 `/Users/tlsdla1235/study/md파일들/computer-science-interview-KR`로 제한된다.
- Markdown heading 구조가 `headingPath`로 보존된다.
- `chunks.jsonl`의 각 line은 schema validation을 통과한다.
- 동일 입력으로 여러 번 생성해도 `chunkId`, `contentHash`, chunk 순서가 안정적이다.
- chunk마다 `sourcePath`와 문서 내 위치를 추적할 수 있다.
- 같은 문서 내 이전/다음 chunk와 부모 heading을 찾을 수 있는 메타데이터가 있다.
- 빈 파일, 비 Markdown, 너무 짧은 chunk는 저장하지 않고 report에 남긴다.
- 기존 `problems.jsonl` 생성 흐름을 수정하지 않는다.

### 테스트 기준

- fixture Markdown의 heading tree가 기대한 `headingPath`로 변환되는지 검증한다.
- 동일 fixture를 두 번 빌드했을 때 `chunkId`와 `contentHash`가 동일한지 검증한다.
- 빈 heading, 코드블록, 목록, 표가 포함된 문서도 chunk text가 깨지지 않는지 검증한다.
- 너무 짧은 chunk가 skip report에 기록되는지 검증한다.
- `chunks.jsonl`이 없거나 비어 있을 때 loader가 안전하게 빈 catalog를 반환하는지 검증한다.

### 제외 범위

- pgvector 저장
- 운영 embedding 생성
- 외부 LLM 호출
- 리포트 점수화
- 퀴즈 추천 로직 수정
- API 엔드포인트
- SQS consumer
- Spring Boot callback 전송

---

## Story 2. 리포트 chunking 및 주제/필드 후보 추출

### 목표

사용자의 일일 리포트가 하나의 주제만 담는다고 가정하지 않고, 분석 가능한 여러 report chunk로 나눈다. 각 report chunk에는 검색과 분석에 사용할 주제 후보, 필드 후보, 원문 위치를 함께 담는다.

### 구현 범위

- 일일 리포트 `title`/`content` 입력을 정규화한다.
- 문단, bullet, heading, 문장 경계를 고려해 report chunk를 만든다.
- 너무 긴 chunk는 의미 단위가 크게 깨지지 않는 선에서 분할한다.
- 너무 짧은 chunk는 인접 chunk와 병합하거나 낮은 confidence 후보로 둔다.
- chunk마다 `chunkId`, `text`, `charStart`, `charEnd`, `topicHints`, `fieldHints`를 만든다.
- `fieldHints`는 최종 점수가 아니라 retrieval/filter 보조 정보로만 사용한다.
- 무관하거나 빈 리포트도 안전하게 처리한다.

권장 report chunk shape:

```json
{
  "reportChunkId": "report:0",
  "text": "JPA N+1이 Lazy Loading에서 왜 발생하는지 정리했다.",
  "charStart": 0,
  "charEnd": 39,
  "topicHints": ["JPA N+1", "Lazy Loading"],
  "fieldHints": ["db", "framework"]
}
```

### 주요 파일 경로

- 구현 후보: `fastapi/app/scoring/report_chunker.py`
- 구현 후보: `fastapi/app/scoring/schemas.py`
- 테스트 후보: `fastapi/tests/scoring/test_report_chunker.py`

### Acceptance Criteria

- 여러 주제를 포함한 리포트는 순서를 보존한 여러 report chunk로 분리된다.
- 각 report chunk는 원문 위치(`charStart`, `charEnd`)를 가진다.
- 원문 text는 chunk 재조합 시 주요 내용이 손실되지 않는다.
- `topicHints`와 `fieldHints`는 비어 있어도 허용되지만 schema는 유지된다.
- `fieldHints`가 최종 `scoreDelta`로 직접 반영되지 않는다.
- 빈 리포트나 공백 리포트는 분석 실패가 아니라 빈 chunk 또는 fallback 가능한 결과로 처리된다.

### 테스트 기준

- `JPA N+1`, `OAuth/JWT`, `프로세스/스레드`가 섞인 리포트가 2개 이상의 chunk로 나뉘는지 검증한다.
- bullet list와 일반 문단이 섞인 입력에서 chunk 순서가 보존되는지 검증한다.
- 아주 짧은 입력, 공백 입력, 긴 단일 문단 입력을 안전하게 처리하는지 검증한다.
- field 후보가 있어도 최종 점수 객체를 만들지 않는지 검증한다.

### 제외 범위

- knowledge chunk catalog 생성
- RAG 검색
- Gemini 호출
- 최종 `scoreDelta` 산정
- 퀴즈 추천
- API/SQS/Spring Boot callback

---

## Story 3. report chunk별 RAG 검색과 source-neighborhood 확장

### 목표

각 report chunk마다 `chunks.jsonl`에서 관련 knowledge chunk를 검색하고, 검색 결과 주변의 출처 기반 인접 컨텍스트를 확장한다. 피드백과 다음 학습 추천이 단순히 가장 가까운 chunk 하나에만 의존하지 않도록 grounding context를 만든다.

### 구현 범위

- `chunks.jsonl` loader와 in-memory store 구현
- MVP용 keyword, normalized token, 간단한 vector/TF-IDF 중 하나 이상의 검색 구현
- report chunk별 top-k knowledge chunk 검색
- 검색 결과의 source-neighborhood 확장
  - 같은 `sourcePath`
  - 같은 `headingPath`의 부모/형제 heading
  - 문서 내 이전/다음 heading 또는 chunk
  - 같은 폴더의 관련 문서
- 확장 컨텍스트에 token/문자 수 cap 적용
- Gemini 입력에 넣기 쉬운 evidence bundle 생성
- 검색 실패 시 빈 evidence bundle 반환

권장 evidence bundle shape:

```json
{
  "reportChunkId": "report:0",
  "matches": [
    {
      "chunkId": "concept:...",
      "score": 0.82,
      "sourcePath": "Database/Transaction.md",
      "headingPath": ["Database", "Transaction", "ACID"],
      "text": "..."
    }
  ],
  "neighborhood": [
    {
      "reason": "previous_heading",
      "chunkId": "concept:...",
      "sourcePath": "Database/Transaction.md",
      "headingPath": ["Database", "Transaction", "Isolation"]
    }
  ]
}
```

### 주요 파일 경로

- 입력 catalog: `fastapi/data/rag/catalog/chunks.jsonl`
- 구현 후보: `fastapi/app/rag/concept_store.py`
- 구현 후보: `fastapi/app/rag/concept_search.py`
- 구현 후보: `fastapi/app/rag/source_neighborhood.py`
- 테스트 후보: `fastapi/tests/rag/test_concept_store.py`
- 테스트 후보: `fastapi/tests/rag/test_concept_search.py`
- 테스트 후보: `fastapi/tests/rag/test_source_neighborhood.py`

### Acceptance Criteria

- report chunk마다 독립적으로 retrieval이 수행된다.
- 검색 결과에는 `sourcePath`, `headingPath`, `chunkId`, score가 포함된다.
- source-neighborhood 확장은 벡터 유사도만 사용하지 않고 출처 구조를 사용한다.
- 같은 문서의 이전/다음 chunk와 부모/형제 heading을 evidence에 포함할 수 있다.
- 같은 폴더의 관련 문서는 cap 안에서만 보조 컨텍스트로 포함된다.
- retrieval 결과가 없어도 전체 리포트 분석이 실패하지 않는다.
- evidence bundle은 Gemini에 전달 가능한 크기로 제한된다.

### 테스트 기준

- 동일 sourcePath 안의 이전/다음 chunk가 neighborhood로 확장되는지 검증한다.
- 같은 parent heading 아래 sibling chunk가 확장 후보에 들어오는지 검증한다.
- 같은 폴더의 문서가 관련 문서 후보로 선택되는지 검증한다.
- top-k와 context cap이 지켜지는지 검증한다.
- catalog 파일 없음, 빈 catalog, 검색 결과 없음 케이스를 검증한다.

### 제외 범위

- pgvector
- 운영 embedding index
- Gemini 분석 문안 생성
- 최종 점수 산정
- 기존 문제 은행 검색 로직 변경
- API/SQS/Spring Boot callback

---

## Story 4. Gemini 기반 리포트 분석/피드백/`scoreDelta` 생성

### 목표

report chunk, retrieval 결과, source-neighborhood evidence를 바탕으로 Gemini가 리포트 분석, 피드백, 다음 학습 추천 후보, `scoreDelta` 초안을 생성하게 한다. 서버는 모델 출력을 schema validation과 clamp로 검증해 신뢰 가능한 `ReportAnalysis` 결과만 반환한다.

### 구현 범위

- Gemini 리포트 분석 프롬프트 작성
- report chunk와 evidence bundle을 구조화 입력으로 전달
- chunk별 분석 결과와 전체 리포트 분석 결과를 분리
- 여러 chunk 점수를 단순 합산하지 않고 필드별 `0..10` 범위 안에서 aggregate
- 리포트 내용 자체에 근거한 `scoreDelta` 산정
- 검색된 지식은 grounding과 추천 근거로 사용하되, 지식 chunk와 가까운 것만으로 높은 점수를 주지 않도록 rubric 적용
- 모델 출력 schema validation
- 서버 측 `scoreDelta` clamp
- confidence 낮음, JSON parsing 실패, Gemini 호출 실패 시 fallback
- `emotion`, `statusMessage`, `dailyReport.text`, `dailyReport.feedback`, `nextRecommendation` 후보 생성

권장 출력 모델:

```json
{
  "status": "SUCCESS",
  "topics": ["JPA N+1", "fetch join"],
  "fieldEvidence": {
    "db": "N+1 문제의 원인과 조회 전략을 설명했다.",
    "framework": "JPA fetch join과 BatchSize를 비교했다."
  },
  "scoreDelta": {
    "db": 8,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 7
  },
  "confidence": 0.84,
  "emotion": "JOY",
  "statusMessage": "핵심 원인과 해결책을 잘 연결했어요.",
  "dailyReport": {
    "text": "오늘은 JPA N+1의 발생 원인과 해결 전략을 중심으로 학습했습니다.",
    "feedback": "fetch join과 BatchSize 차이를 비교한 점이 좋습니다. 다음에는 페이징 한계까지 확인해 보세요."
  },
  "nextRecommendation": {
    "topics": ["EntityGraph", "fetch join 페이징 한계"],
    "rationale": "N+1 해결 도구를 비교했으므로 적용 한계로 확장하면 좋습니다."
  }
}
```

### 주요 파일 경로

- 구현 후보: `fastapi/app/scoring/report_analyzer.py`
- 구현 후보: `fastapi/app/scoring/schemas.py`
- 구현 후보: `fastapi/app/scoring/policy.py`
- 프롬프트 후보: `fastapi/app/scoring/prompts/report_analysis.md`
- 테스트 후보: `fastapi/tests/scoring/test_report_analyzer.py`
- 테스트 후보: `fastapi/tests/scoring/test_scoring_policy.py`

### Acceptance Criteria

- `scoreDelta`는 5개 필드 `db`, `algorithm`, `cs`, `network`, `framework`를 항상 포함한다.
- `scoreDelta[field]`는 서버에서 최종적으로 `0..10`으로 clamp된다.
- 여러 report chunk가 같은 필드에 걸쳐 있어도 필드별 최종 점수는 10을 넘지 않는다.
- 리포트 점수는 리포트 본문 근거만으로 산정하며 퀴즈 채점 결과를 사용하지 않는다.
- retrieval evidence는 주제 grounding, 피드백, 다음 추천에 사용되지만 점수 직접 산식으로 쓰지 않는다.
- Gemini가 없는 필드를 만들거나 잘못된 JSON을 반환하면 서버 validation이 실패 처리 또는 교정한다.
- confidence가 낮으면 보수적으로 낮은 점수 또는 전 필드 0점 fallback을 반환한다.
- Gemini 호출 실패 시 안전한 fallback 결과를 반환한다.
- 피드백은 검색된 chunk와 source-neighborhood 근거를 반영한다.

### 테스트 기준

- fake Gemini client가 `scoreDelta` 10 초과 값을 반환해도 clamp되는지 검증한다.
- fake Gemini client가 필드를 누락하거나 알 수 없는 필드를 반환해도 schema validation이 동작하는지 검증한다.
- Gemini 호출 실패 시 fallback이 전 필드 0점과 낮은 confidence를 반환하는지 검증한다.
- 얕은 리포트와 구체적인 리포트가 다른 점수대로 평가되는지 fixture로 검증한다.
- 여러 chunk 분석 결과를 aggregate해도 필드별 10점을 넘지 않는지 검증한다.
- 퀴즈 채점 service 함수 또는 quiz submission 데이터를 호출하지 않는지 검증한다.

### 제외 범위

- 퀴즈 채점 서비스 함수 구현
- 기존 퀴즈 추천 함수 수정
- API 엔드포인트
- SQS consumer
- Spring Boot callback 전송
- 멱등성/재시도/DLQ 구현
- pgvector

---

## Story 5. 리포트 결과 조립 및 기존 quiz recommender 연결

### 목표

리포트 분석 결과와 기존 퀴즈 추천 내부 함수를 조합해 Spring Boot 리포트 콜백 계약에 붙이기 쉬운 내부 결과를 만든다.

이번 story는 콜백을 전송하지 않는다. requestId/userId/characterId/targetDate 같은 메타데이터는 이후 API/SQS 오케스트레이터에서 감싸기 쉽게 두고, 여기서는 핵심 AI payload 조립에 집중한다.

### 구현 범위

- 내부 public service 함수 설계
- report chunking, retrieval, Gemini report analysis 호출 순서 조립
- 기존 리포트 기반 퀴즈 추천 함수 호출
- `recommendedQuizzes`를 기존 추천 함수 결과로 채우기
- 추천 실패가 리포트 분석 실패로 번지지 않도록 fallback 처리
- 최종 payload schema validation
- Spring Boot 계약의 핵심 필드와 호환되는 결과 반환

권장 내부 함수 shape:

```python
def generate_daily_report_result(
    *,
    report_title: str | None,
    report_content: str,
    current_stats: dict[str, int] | None = None,
    character_personality: str | None = None,
) -> dict:
    ...
```

권장 결과 shape:

```json
{
  "status": "SUCCESS",
  "scoreDelta": { "db": 8, "algorithm": 0, "cs": 0, "network": 0, "framework": 7 },
  "emotion": "JOY",
  "statusMessage": "핵심 원인과 해결책을 잘 연결했어요.",
  "dailyReport": {
    "text": "오늘은 JPA N+1의 발생 원인과 해결 전략을 중심으로 학습했습니다.",
    "feedback": "fetch join과 BatchSize 차이를 비교한 점이 좋습니다."
  },
  "nextRecommendation": {
    "topics": ["EntityGraph", "fetch join 페이징 한계"],
    "rationale": "N+1 해결 도구를 비교했으므로 적용 한계로 확장하면 좋습니다."
  },
  "recommendedQuizzes": [
    {
      "problemId": 123,
      "question": "...",
      "modelAnswer": "...",
      "scoreAllocation": {
        "db": 10,
        "algorithm": 0,
        "cs": 0,
        "network": 0,
        "framework": 10
      }
    }
  ]
}
```

### 주요 파일 경로

- 구현 후보: `fastapi/app/scoring/daily_report_service.py`
- 구현 후보: `fastapi/app/scoring/schemas.py`
- 기존 호출 대상: `fastapi/app/rag/daily_report_recommender.py`
- 기존 호출 대상: `fastapi/app/rag/quiz_recommender.py`
- 기존 catalog: `fastapi/data/rag/catalog/problems.jsonl`
- 테스트 후보: `fastapi/tests/scoring/test_daily_report_service.py`

### Acceptance Criteria

- 결과는 `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`를 포함한다.
- `scoreDelta`는 리포트 분석 결과만 반영한다.
- `recommendedQuizzes`는 기존 퀴즈 추천 함수 결과를 사용한다.
- 추천 퀴즈는 `problemId`, `question`, `modelAnswer`, `scoreAllocation` 스냅샷을 포함한다.
- 가까운 문제가 없으면 `recommendedQuizzes: []`를 반환한다.
- 퀴즈 추천 실패 시에도 리포트 분석 결과는 가능한 범위에서 반환된다.
- 리포트 분석 실패 시 fallback payload는 전 필드 0점, 보수적 emotion/statusMessage, `recommendedQuizzes: []`를 반환한다.
- 결과에는 퀴즈 채점 결과나 `gradings` 배열이 포함되지 않는다.
- 이후 SQS/Spring Boot 콜백 계층에서 `requestId`, `userId`, `characterId`, `targetDate`를 감싸기 쉬운 구조다.

### 테스트 기준

- fake report analyzer와 fake quiz recommender를 사용해 최종 payload shape를 검증한다.
- recommender가 빈 배열을 반환할 때 `recommendedQuizzes: []`가 유지되는지 검증한다.
- recommender가 예외를 던져도 리포트 분석 결과가 fallback 없이 유지되는지 검증한다.
- analyzer가 실패하면 전체 결과가 안전한 fallback으로 내려가는지 검증한다.
- 최종 payload가 Spring Boot 리포트 콜백 핵심 필드와 호환되는지 schema 테스트로 검증한다.
- 퀴즈 채점 service 함수가 호출되지 않는지 검증한다.

### 제외 범위

- FastAPI API 엔드포인트
- SQS consumer
- Spring Boot `POST /api/report` callback 전송
- SQS 메시지 삭제, 재시도, DLQ 처리
- requestId 기반 멱등성 저장소
- 퀴즈 답안 채점
- 문제 은행 재생성
- pgvector/운영 vector store

## 5. Epic 완료 기준

- `fastapi/data/rag/catalog/chunks.jsonl`을 기반으로 리포트 RAG 분석을 수행할 수 있다.
- 다중 주제 리포트가 여러 report chunk로 나뉘고 chunk별 retrieval이 수행된다.
- source-neighborhood 확장이 벡터 검색 결과와 별도로 동작한다.
- Gemini 기반 리포트 분석 결과는 서버 schema validation과 clamp를 통과한다.
- 최종 내부 결과가 Spring Boot 리포트 콜백 핵심 필드에 맞춰 조립된다.
- `recommendedQuizzes`는 기존 문제 은행/퀴즈 추천 함수 결과를 사용한다.
- 리포트 점수와 퀴즈 채점 점수는 이중계상되지 않는다.
- API/SQS/Spring Boot callback 전송은 구현하지 않고 후속 epic/story로 남긴다.
