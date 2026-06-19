---
title: Report 4 - Gemini Report Analysis and Score Delta
status: done
created: 2026-06-13
owner: FastAPI AI 서버
epic: report-generation
story_key: report-4-gemini-report-analysis-score-delta
source_docs:
  - ../report-generation-epic.md
  - ../report-generation-sprint-status.yaml
  - ./report-1-concept-chunk-catalog.md
  - ./report-2-report-chunking-topic-field-hints.md
  - ./report-3-rag-search-and-source-neighborhood.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.2
  - https://ai.google.dev/gemini-api/docs/structured-output
  - https://ai.google.dev/gemini-api/docs/embeddings
---

# Report 4. Gemini Report Analysis and Score Delta

## Status

done

## Story

As a FastAPI AI 서버 개발자,
I want 사용자의 일일 리포트 `title`/`content`와 Story 3 RAG evidence bundle을 Gemini 분석 입력으로 넣어 검증된 `ReportAnalysis`를 만들고,
so that 후속 Story 5가 Spring Boot 리포트 콜백 계약에 맞는 최종 payload를 안전하게 조립할 수 있다.

## 목표

사용자 일일 리포트 본문을 분석해 Gemini 기반 `ReportAnalysis`를 만든다. Story 2의 `ReportChunk`와 Story 3의 evidence bundle을 Gemini prompt에 구조화 입력으로 넣되, Gemini output은 절대 그대로 믿지 않는다.

Spring Boot 리포트 요청 계약의 `userMetadata`와 `characterMetadata`에 담긴 스트릭, 최근 학습 방향, 최근 분야별 점수 변화, 현재 캐릭터 능력치가 유의미하면 피드백과 `statusMessage`, `nextRecommendation` 문안에 반영한다. 예를 들어 연속 작성 중이면 꾸준함을 칭찬하고, 오랜만에 돌아왔거나 접속/작성 간격이 뜸하면 캐릭터 성격에 맞춰 부드럽지만 분명하게 다시 리듬을 잡도록 말한다.

서버는 Gemini output을 JSON parsing, schema validation, `scoreDelta` 5필드 보정, `0..10` clamp, confidence 기반 보수 정책, fallback 생성으로 방어해야 한다.

이번 story는 내부 분석 함수와 Gemini 분석 결과 schema validation/clamp/fallback까지만 다룬다. API endpoint, SQS consumer, Spring Boot callback 전송, request/user/character/date wrapper, `recommendedQuizzes` 연결은 구현하지 않는다. Story 5가 최종 payload 조립과 `recommendedQuizzes` 연결을 담당한다.

## 배경

Report Generation Epic은 FastAPI 내부 함수 중심으로 리포트 분석 파이프라인을 단계적으로 만든다. Story 1은 `computer-science-interview-KR` concept catalog를 만들었고, Story 2는 daily report를 deterministic `ReportChunk` 목록으로 나누었고, Story 3은 각 `ReportChunk`에 대해 embedding-first retrieval과 source-neighborhood evidence bundle을 완성했다.

Architecture의 리포트 결과 콜백 계약은 최종적으로 다음 핵심 필드를 요구한다: `status`, `scoreDelta`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`. 이 중 Story 4는 `recommendedQuizzes`를 제외한 분석 결과만 만든다. 캐릭터 감정은 Spring Boot가 `characterMetadata.emotion`으로 결정해 요청에 넣고, FastAPI는 그 값을 문체/톤 컨텍스트로만 소비한다. Story 5가 Story 4 분석 결과와 기존 quiz recommender 결과를 조립해 Spring Boot callback shape에 가까운 내부 payload를 만든다.

중요한 계약:

- `scoreDelta`는 학습 리포트 분석분만이다.
- `scoreDelta` 필드는 `db`, `algorithm`, `cs`, `network`, `framework` 5개를 항상 포함한다.
- 각 `scoreDelta[field]`는 `0..10` 범위를 넘지 않는다.
- `dailyReport`는 리포트 본문과 학습 피드백만 담는다.
- 퀴즈 채점 결과, quiz submission, `gradings` 배열은 리포트 분석에 사용하지 않는다.
- Spring Boot callback wrapper인 `requestId`, `userId`, `characterId`, `targetDate`는 이번 story의 출력이 아니다.
- `userMetadata`, `characterMetadata.currentStats`, 최근 학습/스트릭/최근 점수 변화는 문안과 추천 맥락으로만 사용한다. `scoreDelta` 직접 산식이나 가산점으로 사용하지 않는다.

## 구현 범위

이번 story에서 구현할 것은 `ReportAnalysis` 내부 분석 계층이다.

1. Public analyzer 함수/클래스
   - 권장 함수: `analyze_daily_report(...)`
   - 권장 클래스: `GeminiReportAnalyzer`
   - fake Gemini client를 주입할 수 있는 `Protocol` 또는 인터페이스를 둔다.
   - 입력은 `report_title`, `report_content`, 선택적 `report_chunks`, 선택적 `evidence_bundles`, 선택적 `user_metadata`, 선택적 `character_personality`, 선택적 `character_metadata`를 받을 수 있어야 한다.
   - `character_metadata`가 제공되고 `character_metadata.personality`가 있으면 `character_personality` fallback으로 사용할 수 있어야 한다.

2. Story 2/3 재사용
   - 직접 chunking/retrieval을 재구현하지 않는다.
   - `chunk_daily_report()`로 `title`/`content`를 `ReportChunk` 목록으로 나눈다.
   - `load_concept_catalog_store()`와 `load_concept_embedding_store()`로 기존 catalog와 embedding sidecar를 로드한다.
   - `build_report_evidence_bundle()` 또는 `build_report_evidence_bundles()`로 Story 3 evidence bundle을 만든다.
   - 테스트에서는 실제 Gemini embedding/API 호출 없이 fake client 또는 prebuilt evidence bundle을 주입할 수 있어야 한다.

3. RAG/embedding 흐름 연결
   - Story 3는 embedding-first retrieval이다.
   - 사용자 report input은 Story 2의 `ReportChunk`로 나뉜다.
   - 각 `ReportChunk` query는 Story 3 public 함수 내부에서 Gemini embedding으로 변환되어 concept catalog를 검색한다.
   - Gemini embedding model은 고정값 `gemini-embedding-2`를 사용한다.
   - embedding dimension은 `768`을 사용한다.
   - 기존 sidecar는 `fastapi/data/rag/catalog/chunk-embeddings.jsonl`이다.
   - Story 4는 retrieval 구현을 새로 만들지 않고, 이 흐름을 analyzer orchestration에서 재사용한다.

4. Gemini report analysis prompt
   - prompt 파일 후보: `fastapi/app/scoring/prompts/report_analysis.md`
   - prompt에는 리포트 원문, `ReportChunk` dict 목록, Story 3 evidence bundle dict 목록, `userMetadata`/최근 학습 컨텍스트, character personality, `characterMetadata.emotion`, scoring rubric, output JSON shape를 넣는다.
   - Gemini에게 `scoreDelta`는 리포트 본문 근거만으로 산정하도록 지시한다.
   - retrieval evidence, `fieldHints`, streak, recent study context, `scoreDeltaHint`, `currentStats`는 grounding, 피드백, 다음 추천 근거일 뿐 점수 직접 산식이 아니라고 명시한다.
   - prompt에도 quiz grading result, quiz submission, `gradings`, `recommendedQuizzes`를 사용하지 말라고 명시한다.

5. Gemini output parsing/validation
   - Gemini가 mapping을 반환하거나 JSON string을 반환하는 두 경우를 지원한다.
   - markdown code fence가 감싸진 JSON string도 기존 `quiz_grader.py` 패턴처럼 안전하게 추출할 수 있다.
   - JSON object가 아니거나 필수 필드가 없으면 fallback으로 내려간다.
   - 알 수 없는 top-level field는 최종 output에서 제거한다.

6. `scoreDelta` validation/clamp
   - 서버 최종 결과는 `db`, `algorithm`, `cs`, `network`, `framework` 5개 field를 항상 포함한다.
   - Gemini가 필드를 누락하면 해당 field는 `0`으로 보정한다.
   - Gemini가 알 수 없는 field를 반환하면 제거한다.
   - Gemini가 음수, 10 초과, float/string 값을 반환해도 서버에서 정수로 보수 변환 후 `0..10`으로 clamp한다.
   - 기존 `fastapi/app/scoring/policy.py`의 `zero_score_vector()`, `clamp_confidence()` 패턴을 재사용하고, 필요하면 report 전용 `clamp_report_score_delta()`를 추가한다. quiz grading의 `clamp_score_delta(raw_delta, allocation)`은 allocation 상한을 전제로 하므로, 이번 story에서는 field별 상한 `10`을 명확히 적용해야 한다.

7. Confidence/fallback 정책
   - `confidence`는 서버에서 `0.0..1.0`으로 clamp한다.
   - confidence가 낮으면 보수적 score를 적용한다. 권장 기준은 `confidence < 0.35`이면 fallback 또는 전 필드 0점, `0.35 <= confidence < 0.55`이면 점수 상한을 낮추는 보수 정책이다.
   - Gemini 호출 실패, JSON parsing 실패, schema validation 실패, 빈 리포트, evidence 생성 실패는 안전한 `FALLBACK` `ReportAnalysis`로 내려간다.
   - fallback은 전 필드 0점, 낮은 confidence, 보수적 `statusMessage`, 최소 nextRecommendation을 반환한다.

8. 캐릭터 personality 반영
   - analyzer 입력 또는 함수 인자로 `character_personality`를 받을 수 있어야 한다.
   - `character_metadata: Mapping[str, Any] | None`를 받는다면 `character_metadata.get("personality")`도 사용할 수 있어야 한다.
   - `dailyReport.feedback`, `statusMessage`, `nextRecommendation.rationale` 문안은 캐릭터 personality를 반영한다.
   - 단, 학습 평가의 정확성을 흐리면 안 된다. 예를 들어 "칭찬을 많이 하지만 틀린 부분은 명확하게 지적하는 성격"이면 문안은 따뜻하게 말하되 부족한 점은 분명히 짚어야 한다.
   - personality는 문체/톤 입력이지 점수 산정 가중치가 아니다.

9. 사용자/학습 이력 컨텍스트 반영
   - `user_metadata: Mapping[str, Any] | None`를 받을 수 있어야 한다.
   - Spring Boot SQS 계약의 `userMetadata.weeklyStudyStreak`, `userMetadata.reportDirection.scoreDeltaHint`, `userMetadata.reportDirection.focus`를 구조화 prompt input에 포함할 수 있어야 한다.
   - 최근 공부한 주제/리포트/분야별 변화가 별도 key로 들어오면 예외 없이 받아 prompt context에 포함한다. 권장 key는 `recentStudyTopics`, `recentReports`, `recentScoreDelta`, `recentFieldChanges`처럼 의미가 드러나는 이름이다.
   - `character_metadata.currentStats`는 현재 분야별 상태를 이해하는 컨텍스트로 prompt에 넣을 수 있다.
   - 연속 리포트 작성, 오랜만의 복귀, 뜸한 접속/작성 패턴, 최근 다른 분야의 뚜렷한 성장/정체가 유의미하면 `statusMessage`, `dailyReport.feedback`, `nextRecommendation.rationale`에 반영한다.
   - 유의미하지 않거나 근거가 약한 metadata는 억지로 언급하지 않는다.
   - 스트릭/최근 점수 변화/현재 능력치는 학습 동기와 피드백 맥락이다. `scoreDelta`는 여전히 오늘 리포트 본문 근거만으로 산정한다.

## 권장 파일 경로

구현 후보:

- `fastapi/app/scoring/report_analyzer.py`
- `fastapi/app/scoring/schemas.py`
- `fastapi/app/scoring/policy.py`
- `fastapi/app/scoring/prompts/report_analysis.md`
- `fastapi/app/scoring/__init__.py`

테스트 후보:

- `fastapi/tests/scoring/test_report_analyzer.py`
- 필요 시 `fastapi/tests/scoring/test_score_policy.py`

재사용 대상:

- `fastapi/app/scoring/report_chunker.py`
- `fastapi/app/scoring/schemas.py`의 `ReportChunk`
- `fastapi/app/rag/concept_store.py`
- `fastapi/app/rag/embedding_store.py`
- `fastapi/app/rag/concept_search.py`
- `fastapi/app/rag/schemas.py`의 `ReportEvidenceBundle`
- `fastapi/data/rag/catalog/chunk-embeddings.jsonl`

수정하지 않아야 할 흐름:

- `fastapi/app/scoring/quiz_grader.py`
- `fastapi/app/rag/daily_report_recommender.py`
- `fastapi/app/rag/quiz_recommender.py`
- `fastapi/app/rag/problem_bank*.py`
- API/SQS/Spring Boot callback 계층

## 권장 Public API

```python
class ReportAnalysisModelClient(Protocol):
    def analyze(self, prompt: str) -> Mapping[str, Any] | str:
        ...


def analyze_daily_report(
    *,
    report_title: str | None,
    report_content: str | None,
    user_metadata: Mapping[str, Any] | None = None,
    character_personality: str | None = None,
    character_metadata: Mapping[str, Any] | None = None,
    report_chunks: list[ReportChunk] | None = None,
    evidence_bundles: list[ReportEvidenceBundle] | list[Mapping[str, Any]] | None = None,
    client: ReportAnalysisModelClient | None = None,
) -> dict[str, Any]:
    ...
```

선택 구조:

```python
@dataclass(frozen=True)
class ReportAnalysis:
    status: Literal["SUCCESS", "FALLBACK"]
    topics: tuple[str, ...]
    field_evidence: dict[str, str]
    score_delta: dict[str, int]
    confidence: float
    status_message: str
    daily_report: DailyReportAnalysis | None
    next_recommendation: NextRecommendation | None

    def to_dict(self) -> dict[str, Any]:
        ...
```

`to_dict()`는 Spring Boot callback 핵심 필드와 연결하기 쉬운 camelCase를 반환한다. 다만 Story 4 output은 wrapper나 `recommendedQuizzes`를 포함하지 않는다.

## 권장 Output Shape

성공:

```json
{
  "status": "SUCCESS",
  "topics": ["JPA N+1", "fetch join"],
  "fieldEvidence": {
    "db": "N+1 문제의 원인과 조회 전략을 설명했다.",
    "algorithm": "",
    "cs": "",
    "network": "",
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
  "statusMessage": "연속 학습 흐름을 이어가면서 핵심 원인과 해결책을 잘 연결했어요.",
  "dailyReport": {
    "text": "오늘은 JPA N+1의 발생 원인과 해결 전략을 중심으로 학습했습니다.",
    "feedback": "최근 framework 쪽 학습이 꾸준히 이어지고 있고, fetch join과 BatchSize 차이를 비교한 점이 좋습니다. 다음에는 페이징 한계까지 확인해 보세요."
  },
  "nextRecommendation": {
    "topics": ["EntityGraph", "fetch join 페이징 한계"],
    "rationale": "N+1 해결 도구를 비교했으므로 적용 한계로 확장하면 좋습니다. 최근 db/framework 흐름이 이어지고 있어 같은 맥락으로 이어가면 효율적입니다."
  }
}
```

Fallback:

```json
{
  "status": "FALLBACK",
  "topics": [],
  "fieldEvidence": {
    "db": "",
    "algorithm": "",
    "cs": "",
    "network": "",
    "framework": ""
  },
  "scoreDelta": {
    "db": 0,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 0
  },
  "confidence": 0.0,
  "statusMessage": "오늘은 리포트를 안정적으로 분석하지 못했어요. 조금 속상하지만, 내일 다시 차근차근 봐줄게요.",
  "dailyReport": {
    "text": "",
    "feedback": "분석할 만한 리포트 근거가 아직 부족해서 점수는 살짝 아껴둘게요."
  },
  "nextRecommendation": {
    "topics": [],
    "rationale": "리포트 근거가 더 또렷해지면 다음 학습 주제를 콕 집어 추천할게요."
  }
}
```

Story 4 output에는 다음을 포함하지 않는다.

- `recommendedQuizzes`
- quiz grading result
- `gradings`
- `submissionId`, quiz submission payload
- SQS metadata
- `requestId`, `userId`, `characterId`, `targetDate` callback wrapper
- Spring Boot callback 전송 로직

## Acceptance Criteria

1. `analyze_daily_report()` 또는 `GeminiReportAnalyzer`가 일일 리포트 `title`/`content`로 `ReportAnalysis` dict 또는 dataclass를 반환한다.
2. 입력 `title`/`content`는 `chunk_daily_report()`를 통해 Story 2 `ReportChunk` 목록으로 나뉜다.
3. 각 `ReportChunk`는 Story 3 public 함수인 `build_report_evidence_bundle()` 또는 `build_report_evidence_bundles()`로 evidence bundle을 만든다.
4. user report chunk가 query embedding으로 검색되어 RAG evidence가 만들어지는 흐름을 Story 3 함수 재사용으로 연결한다.
5. Gemini embedding 설정은 Story 3의 `gemini-embedding-2`, dimension `768`, sidecar `fastapi/data/rag/catalog/chunk-embeddings.jsonl` 흐름과 호환된다.
6. Story 4는 retrieval, embedding store, source-neighborhood 로직을 새로 구현하지 않는다.
7. 테스트에서는 실제 Gemini embedding/API 호출 없이 fake client 또는 prebuilt evidence bundle을 주입할 수 있다.
8. Gemini prompt input은 리포트 원문, `ReportChunk.to_dict()` 목록, Story 3 evidence bundle dict 목록, character personality를 구조화해서 포함한다.
9. Gemini prompt와 server validation은 `scoreDelta`가 리포트 본문 근거만으로 산정되도록 제한한다.
10. retrieval evidence와 `fieldHints`는 grounding, 피드백, 추천 근거로만 사용하고 `scoreDelta` 직접 산식으로 사용하지 않는다.
11. `scoreDelta`는 `db`, `algorithm`, `cs`, `network`, `framework` 5개 field를 항상 포함한다.
12. `scoreDelta[field]`는 서버 최종 결과에서 항상 정수이며 `0..10` 범위다.
13. Gemini가 `scoreDelta` 필드를 누락하거나 알 수 없는 field를 추가해도 서버가 기본 field 보정과 unknown field 제거를 수행한다.
14. Gemini가 `scoreDelta`에 10 초과, 음수, float, numeric string을 반환해도 서버 clamp가 적용된다.
15. Gemini가 잘못된 JSON, JSON이 아닌 텍스트, object가 아닌 JSON을 반환하면 `FALLBACK` 결과를 반환한다.
16. Gemini 호출 실패 시 `FALLBACK` 결과를 반환한다.
17. confidence가 낮으면 보수적 score 또는 fallback이 적용된다.
18. `fieldEvidence`는 5개 score field를 모두 포함하며, 근거가 부족한 field는 빈 문자열 또는 보수적 메시지를 둔다.
19. `topics`, `statusMessage`, `dailyReport.text`, `dailyReport.feedback`, `nextRecommendation.topics`, `nextRecommendation.rationale`가 output에 포함된다.
20. `character_personality` 또는 `character_metadata.personality`가 prompt/input에 포함된다.
21. `character_metadata.emotion`이 제공되면 prompt/input에 포함되며, output field로는 반환하지 않는다.
22. `dailyReport.feedback`, `statusMessage`, `nextRecommendation.rationale`는 character personality와 emotion을 문체에 반영하되 학습 평가 정확성을 흐리지 않는다.
23. `user_metadata`가 제공되면 `weeklyStudyStreak`, `reportDirection.scoreDeltaHint`, `reportDirection.focus`, 최근 공부한 주제/리포트, 최근 분야별 점수 변화가 prompt/input에 포함된다.
24. `character_metadata.currentStats`가 제공되면 prompt/input에 포함된다.
25. 연속 작성, 오랜만의 복귀, 뜸한 작성/접속, 최근 다른 분야의 뚜렷한 성장/정체가 유의미하면 `statusMessage`, `dailyReport.feedback`, `nextRecommendation.rationale`에 반영된다.
26. `user_metadata`, streak, 최근 학습/점수 변화, `scoreDeltaHint`, `currentStats`는 `scoreDelta` 직접 산식이나 가산점으로 사용하지 않는다.
27. 퀴즈 채점 결과, quiz submission, `gradings` 배열을 읽거나 사용하지 않는다.
28. 기존 `grade_quiz_answer()` 또는 quiz grading service를 호출하지 않는다.
29. Story 4 output은 `recommendedQuizzes`를 포함하지 않는다.
30. Story 4는 API endpoint, SQS consumer, Spring Boot callback 전송, request/user/character/date wrapper를 만들지 않는다.

## 테스트 기준

필수 테스트:

- fake Gemini client가 정상 JSON output을 반환하면 `ReportAnalysis`가 `SUCCESS`로 만들어지는지 검증한다.
- fake Gemini client가 mapping 객체를 반환하는 경우와 JSON string을 반환하는 경우를 모두 검증한다.
- fake Gemini client가 markdown code fence로 감싼 JSON을 반환해도 parsing되는지 검증한다.
- Gemini가 `scoreDelta` 10 초과, 음수, 누락 field, 알 수 없는 field를 반환해도 서버 clamp와 기본 field 보정이 되는지 검증한다.
- Gemini가 잘못된 JSON을 반환하면 `FALLBACK`으로 내려가는지 검증한다.
- Gemini client가 예외를 던지면 `FALLBACK`으로 내려가는지 검증한다.
- confidence가 낮으면 보수적 score 또는 fallback이 적용되는지 검증한다.
- `character_personality`가 prompt/input에 포함되는지 fake client가 받은 prompt를 통해 검증한다.
- `character_metadata={"personality": "..."}`만 넘긴 경우에도 personality가 prompt/input에 포함되는지 검증한다.
- personality가 있는 경우 `dailyReport.feedback`, `statusMessage`, `nextRecommendation.rationale`에 톤이 반영되는 경로를 검증한다. 테스트는 문구 전체 고정보다 personality marker나 prompt 포함 여부, fake response 보존/validation을 함께 검증한다.
- `user_metadata`의 `weeklyStudyStreak`, `reportDirection.scoreDeltaHint`, `reportDirection.focus`, 최근 공부한 주제/리포트, 최근 분야별 점수 변화가 prompt/input에 포함되는지 검증한다.
- `character_metadata.currentStats`가 prompt/input에 포함되는지 검증한다.
- 연속 작성/오랜만의 복귀/뜸한 작성 같은 streak context가 fake response를 통해 `statusMessage` 또는 `dailyReport.feedback`에 반영될 수 있는 경로를 검증한다.
- `scoreDeltaHint` 또는 최근 점수 변화만으로 `scoreDelta`가 올라가지 않는지, 동일 리포트 본문에서는 metadata 유무와 무관하게 서버 score clamp/validation 원칙이 유지되는지 검증한다.
- Story 3 evidence bundle이 Gemini prompt input에 포함되는지 fake client prompt capture로 검증한다.
- prebuilt evidence bundle을 주입하면 실제 Story 3 retrieval과 Gemini embedding 호출 없이 분석 테스트가 통과하는지 검증한다.
- prebuilt evidence bundle을 주입하지 않는 경로에서는 `chunk_daily_report()`, `load_concept_catalog_store()`, `load_concept_embedding_store()`, `build_report_evidence_bundles()` 재사용 흐름으로 연결되는지 monkeypatch/fake로 검증한다.
- user report chunk query embedding/RAG retrieval 흐름이 Story 3 public 함수 재사용으로 연결되는지 검증한다.
- output dict에 `recommendedQuizzes`, `gradings`, `submissionId`, `requestId`, `userId`, `characterId`, `targetDate`가 없는지 검증한다.
- `grade_quiz_answer()`, quiz submission loader, quiz recommender, API/SQS/Spring Boot callback 함수가 호출되지 않는지 검증한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_report_analyzer
```

관련 회귀 확인:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_score_policy tests.scoring.test_report_chunker tests.rag.test_concept_search tests.rag.test_source_neighborhood
```

전체 회귀 확인:

```bash
cd fastapi
python3 -m unittest discover -s tests
```

## 제외 범위

이번 story에서 하지 않는다.

- ReportChunk chunking 알고리즘 재구현
- Story 3 retrieval, embedding store, source-neighborhood 구현 변경
- `chunks.jsonl` 또는 `chunk-embeddings.jsonl` 재생성
- Gemini embedding build CLI 구현
- 실제 Gemini API를 unit test에서 호출
- quiz grading service 호출 또는 수정
- quiz submission, grading result, `gradings` 배열 사용
- 기존 quiz recommender 연결
- `recommendedQuizzes` 생성
- `daily_report_recommender.py` contract 변경
- API endpoint
- SQS consumer
- Spring Boot `POST /api/report` callback 전송
- `requestId`, `userId`, `characterId`, `targetDate` wrapper 조립
- requestId 멱등성 저장소, 재시도, DLQ 처리
- pgvector/운영 vector store

## Dev Notes

- 기존 scoring 구현은 dataclass, `to_dict()`, camelCase output, injectable client, markdown prompt file 패턴을 사용한다. `quiz_grader.py`의 `GradingModelClient`, `GeminiGradingModelClient`, `_parse_model_response()`, `_extract_json_object()`, prompt loader 패턴을 참고하되 quiz grading 로직 자체는 호출하지 않는다.
- `fastapi/app/scoring/policy.py`는 `zero_score_vector()`와 `clamp_confidence()`를 이미 제공한다. `clamp_score_delta(raw_delta, allocation)`은 quiz allocation 상한을 받는 함수이므로 report 전용 `0..10` clamp helper를 추가하는 편이 명확하다.
- `SCORE_FIELDS`는 `app.rag.schemas.SCORE_FIELDS`에서 온다. field order는 기존 테스트처럼 `db`, `algorithm`, `cs`, `network`, `framework` 순서를 유지한다.
- `fieldEvidence`도 5개 field order를 유지하면 테스트와 Spring Boot contract 연결이 단순해진다.
- Story 3 `ReportEvidenceBundle.to_dict()`는 camelCase key를 제공한다. prompt에는 dataclass 자체보다 dict를 넣는 편이 fake client 테스트와 JSON serialization이 쉽다.
- user input/report chunk가 query embedding으로 검색되어 RAG evidence가 만들어진다. 이 query embedding과 retrieval은 Story 3 public 함수가 담당하며, Story 4는 analyzer에서 그 결과를 구조화 prompt input으로 사용한다.
- Gemini structured output은 prompt에 JSON shape를 명시하고 `response_mime_type="application/json"` 또는 JSON schema 설정을 사용할 수 있다. 그래도 서버 validation/clamp/fallback은 반드시 유지한다.
- Gemini Embeddings 공식 문서는 `gemini-embedding-2`와 `output_dimensionality=768` 예시를 제공하고, `gemini-embedding-2`의 stable model code와 권장 dimension 768을 명시한다. Story 4는 이 설정을 바꾸지 않는다.
- 리포트 점수는 "학습자가 오늘 리포트 본문에 무엇을 얼마나 구체적으로 설명했는가"만 본다. evidence는 정답 기준, 피드백 근거, 다음 추천 확장에 쓰되, 가까운 chunk가 검색되었다는 이유만으로 점수를 올리지 않는다.
- `fieldHints`는 Story 2/3에서 retrieval/filter 보조 정보다. `fieldHints=["db"]`라는 이유만으로 `scoreDelta["db"]`를 올리면 안 된다.
- 캐릭터 personality는 tone control이다. 친절한 성격이면 더 따뜻하게 쓰고, 엄격한 성격이면 더 분명하게 쓰되, 틀린 이해를 맞다고 말하거나 근거 없는 점수를 주면 안 된다.
- Architecture의 SQS 입력 계약에는 `userMetadata.weeklyStudyStreak`, `userMetadata.reportDirection.scoreDeltaHint`, `userMetadata.reportDirection.focus`, `characterMetadata.currentStats`가 있다. 이 값들은 리포트 문안이 사용자의 흐름을 이해하도록 돕는 context다.
- `weeklyStudyStreak`는 문자열 패턴으로 들어올 수 있다. 예: `0100011`. 끝부분이 연속 `1`이면 꾸준함을 칭찬하고, 최근 `0`이 많거나 오랜 공백 뒤 `1`이면 캐릭터 성격에 맞춰 다시 리듬을 잡도록 말한다. 단, 비난/조롱이 아니라 학습 동기를 살리는 피드백이어야 한다.
- 최근 공부한 주제나 최근 분야별 점수 변화가 있으면, 오늘 리포트 내용과 연결될 때만 언급한다. 예: 최근 network가 정체되고 오늘 JWT/OAuth를 학습했다면 network 흐름을 다시 잡는 추천을 할 수 있다.
- `scoreDeltaHint`, 최근 점수 변화, `currentStats`는 "이 분야를 언급하면 좋다"는 맥락일 수는 있지만, 오늘 리포트 본문이 부실한데 점수를 올리는 근거가 될 수 없다.
- fallback도 Spring Boot 최종 callback shape로 조립하기 쉬워야 하지만, Story 4 자체에서는 wrapper와 `recommendedQuizzes`를 붙이지 않는다.
- Story 5 경계: Story 5가 `ReportAnalysis`를 받아 기존 `quiz_recommender.py`/`daily_report_recommender.py` 계열 추천과 연결하고 `recommendedQuizzes`를 붙인다. Story 4에서 이 작업을 선행하지 않는다.

## Tasks / Subtasks

- [x] `ReportAnalysis`, `DailyReportAnalysis`, `NextRecommendation` schema/dataclass와 `to_dict()` 구현 (AC: 1, 11, 18, 19)
- [x] report 전용 `scoreDelta` 5필드 보정/clamp helper 구현 (AC: 11, 12, 13, 14)
- [x] confidence clamp와 low-confidence 보수/fallback policy 구현 (AC: 16, 17)
- [x] `ReportAnalysisModelClient` protocol과 Gemini client wrapper 구현 (AC: 1, 7)
- [x] `fastapi/app/scoring/prompts/report_analysis.md` 작성 (AC: 8, 9, 10, 20, 21)
- [x] Gemini JSON parsing/validation helper 구현 (AC: 13, 14, 15)
- [x] fallback `ReportAnalysis` 생성 helper 구현 (AC: 15, 16, 17)
- [x] `analyze_daily_report()` 또는 `GeminiReportAnalyzer.analyze()` 구현 (AC: 1)
- [x] `chunk_daily_report()` 재사용 연결 (AC: 2)
- [x] `load_concept_catalog_store()`, `load_concept_embedding_store()`, `build_report_evidence_bundles()` 재사용 연결 (AC: 3, 4, 5, 6)
- [x] prebuilt `report_chunks`/`evidence_bundles` 주입 경로 구현 (AC: 7)
- [x] `character_personality`와 `character_metadata.personality` 입력 처리 구현 (AC: 20, 21)
- [x] `user_metadata`, `character_metadata.currentStats`, 최근 학습/스트릭/최근 분야별 점수 변화 prompt context 처리 구현 (AC: 22, 23, 24)
- [x] metadata가 `scoreDelta` 직접 산식으로 들어가지 않도록 guardrail과 테스트 구현 (AC: 25)
- [x] Story 4 output에서 `recommendedQuizzes`, `gradings`, callback wrapper field 제거 보장 (AC: 26, 27, 28, 29)
- [x] `fastapi/tests/scoring/test_report_analyzer.py` unit test 작성 (전체 AC)
- [x] 관련 scoring/rag 회귀 테스트 실행

## Project Structure Notes

- 새 구현은 `fastapi/app/scoring` 아래에 둔다. RAG retrieval 로직은 `fastapi/app/rag`의 기존 Story 3 모듈을 import해서 사용한다.
- prompt는 기존 `fastapi/app/scoring/prompts/quiz_grading.md`와 같은 폴더에 `report_analysis.md`로 둔다.
- test는 기존 unittest 구조에 맞춰 `fastapi/tests/scoring/test_report_analyzer.py`에 둔다.
- 이 story가 `fastapi/app/rag/quiz_recommender.py`, `fastapi/app/rag/daily_report_recommender.py`, API router, SQS consumer를 건드리면 범위 이탈 가능성이 높다.

## References

- Report Generation Epic Story 4/5 경계: `fastapi/docs/report/report-generation-epic.md#Story-4-Gemini-기반-리포트-분석피드백scoreDelta-생성`, `fastapi/docs/report/report-generation-epic.md#Story-5-리포트-결과-조립-및-기존-quiz-recommender-연결`
- Sprint tracking: `fastapi/docs/report/report-generation-sprint-status.yaml`
- Story 1 concept catalog: `fastapi/docs/report/stories/report-1-concept-chunk-catalog.md`
- Story 2 report chunking: `fastapi/docs/report/stories/report-2-report-chunking-topic-field-hints.md`
- Story 3 embedding-first retrieval/evidence bundle: `fastapi/docs/report/stories/report-3-rag-search-and-source-neighborhood.md`
- Architecture 리포트 요청/콜백 계약: `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.1`, `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.2`
- Gemini structured output docs: `https://ai.google.dev/gemini-api/docs/structured-output`
- Gemini embeddings docs: `https://ai.google.dev/gemini-api/docs/embeddings`

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- RED: `cd fastapi && python3 -m unittest tests.scoring.test_report_analyzer` 실행 시 `app.scoring.report_analyzer` import 실패를 확인했다.
- GREEN: `cd fastapi && python3 -m unittest tests.scoring.test_report_analyzer` 13개 테스트 통과.
- 회귀: `cd fastapi && python3 -m unittest tests.scoring.test_score_policy tests.scoring.test_report_chunker tests.rag.test_concept_search tests.rag.test_source_neighborhood` 23개 테스트 통과.
- 전체: `cd fastapi && python3 -m unittest discover -s tests` 92개 테스트 통과.

### Completion Notes List

- `ReportAnalysis`, `DailyReportAnalysis`, `NextRecommendation` dataclass와 camelCase `to_dict()` output을 추가했다.
- `GeminiReportAnalyzer`와 `analyze_daily_report()`를 추가해 Story 2 `chunk_daily_report()`와 Story 3 `load_concept_catalog_store()`, `load_concept_embedding_store()`, `build_report_evidence_bundles()` 흐름을 재사용한다.
- fake 주입 가능한 `ReportAnalysisModelClient` protocol과 Gemini wrapper를 구현했고, unit test는 fake client와 prebuilt evidence bundle만 사용해 실제 Gemini/embedding API를 호출하지 않는다.
- Gemini JSON mapping/string/code fence parsing, schema validation, unknown top-level 제거, fallback, confidence 기반 보수 정책을 구현했다.
- report 전용 `scoreDelta` clamp는 5개 필드를 항상 유지하고 `0..10` 정수로 보정하며, 낮은 confidence 구간은 field별 최대 3점으로 낮춘다.
- `user_metadata`의 `weeklyStudyStreak`, `reportDirection.scoreDeltaHint`, `reportDirection.focus`, 최근 공부한 주제/리포트/분야별 변화와 `character_metadata.currentStats`를 prompt context에 포함했다.
- metadata, streak, scoreDeltaHint, currentStats는 prompt guardrail과 서버 경로에서 점수 산식에 사용하지 않도록 분리했고, metadata만으로 점수가 오르지 않는 테스트를 추가했다.
- Story 4 output에는 `recommendedQuizzes`, `gradings`, quiz submission/callback wrapper field가 포함되지 않도록 보장했다.

### File List

- `fastapi/app/scoring/__init__.py`
- `fastapi/app/scoring/policy.py`
- `fastapi/app/scoring/prompts/report_analysis.md`
- `fastapi/app/scoring/report_analyzer.py`
- `fastapi/app/scoring/schemas.py`
- `fastapi/tests/scoring/test_report_analyzer.py`
- `fastapi/docs/report/stories/report-4-gemini-report-analysis-score-delta.md`
- `fastapi/docs/report/report-generation-sprint-status.yaml`

## Change Log

- 2026-06-13: Story 4 context document created and marked ready-for-dev.
- 2026-06-13: Implemented Gemini report analysis layer with report score clamp, user/character context prompt guardrails, fallback handling, and unit/regression coverage; story marked review.
