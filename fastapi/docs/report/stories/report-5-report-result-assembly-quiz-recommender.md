---
title: Report 5 - Report Result Assembly and Quiz Recommender
status: done
created: 2026-06-13
owner: FastAPI AI 서버
epic: report-generation
story_key: report-5-report-result-assembly-quiz-recommender
source_docs:
  - ../report-generation-epic.md
  - ../report-generation-sprint-status.yaml
  - ./report-1-concept-chunk-catalog.md
  - ./report-2-report-chunking-topic-field-hints.md
  - ./report-3-rag-search-and-source-neighborhood.md
  - ./report-4-gemini-report-analysis-score-delta.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.2
---

# Report 5. Report Result Assembly and Quiz Recommender

## Status

done

## Story

As a FastAPI AI 서버 개발자,
I want Story 4의 `ReportAnalysis` 결과와 기존 quiz recommender 결과를 하나의 callback-ready 내부 payload로 조립하고,
so that 후속 API/SQS/Spring Boot callback 계층이 wrapper와 전송 책임만 얹어도 안전하게 리포트 결과를 전달할 수 있다.

## 목표

Story 4가 만든 리포트 분석 결과에 기존 문제 은행 기반 퀴즈 추천 결과를 붙여, Spring Boot 리포트 콜백 핵심 필드와 호환되는 내부 결과 payload를 만든다.

이번 story는 내부 service/payload 조립까지만 다룬다. API endpoint, SQS consumer, Spring Boot callback 전송, `requestId`/`userId`/`characterId`/`targetDate` wrapper는 만들지 않는다.

필수 output shape는 다음과 같다.

```json
{
  "status": "SUCCESS",
  "scoreDelta": {
    "db": 0,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 0
  },
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
  "recommendedQuizzes": [
    {
      "problemId": 123,
      "question": "...",
      "modelAnswer": "...",
      "scoreAllocation": {
        "db": 0,
        "algorithm": 0,
        "cs": 0,
        "network": 0,
        "framework": 0
      }
    }
  ]
}
```

## 배경

Report Generation Epic은 FastAPI 내부 함수 중심으로 리포트 분석 파이프라인을 나누었다.

- Story 1은 `computer-science-interview-KR` 기반 concept chunk catalog를 생성했다.
- Story 2는 일일 리포트 `title`/`content`를 deterministic `ReportChunk` 목록으로 나눈다.
- Story 3은 각 `ReportChunk`에 대해 RAG retrieval과 source-neighborhood evidence bundle을 만든다.
- Story 4는 Gemini 기반 `ReportAnalysis`를 만들고 `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`을 validation/clamp/fallback으로 방어한다.
- Story 5는 Story 4 분석 결과와 기존 quiz recommender 결과를 조합해 최종 내부 payload를 만든다.

Story 4와 Story 5의 경계는 명확해야 한다.

| 구분 | Story 4 책임 | Story 5 책임 |
|------|--------------|--------------|
| 리포트 chunking/RAG/Gemini 분석 | Story 2/3을 재사용해 `ReportAnalysis` 생성 | 새로 구현하지 않고 analyzer를 호출하거나 주입받는다 |
| `scoreDelta` | 리포트 본문 근거만으로 5필드 `0..10` validation/clamp | 값을 재계산하지 않고 Story 4 결과만 복사한다 |
| `dailyReport`/`nextRecommendation` | Gemini 분석과 metadata context를 반영해 생성 | 값을 덮어쓰지 않고 최종 payload에 배치한다 |
| `recommendedQuizzes` | 만들지 않는다 | 기존 quiz recommender 결과로 채운다 |
| fallback | 분석 실패/FALLBACK `ReportAnalysis` 생성 | analyzer 실패/FALLBACK이면 전체 payload를 안전 fallback으로 만들고 추천은 비운다 |
| API/SQS/callback wrapper | 만들지 않는다 | 만들지 않는다 |

중요한 계약:

- `scoreDelta`는 Story 4 report analysis 결과만 반영한다.
- 추천 퀴즈의 `scoreAllocation`은 "이 퀴즈를 맞혔을 때 얻을 수 있는 최대 배점" 스냅샷이다. 리포트 `scoreDelta`에 더하지 않는다.
- 퀴즈 채점 결과, quiz submission, `gradings` 배열은 리포트 결과 조립에 사용하지 않는다.
- `grade_quiz_answer()`는 호출하지 않는다.
- Spring Boot callback wrapper인 `requestId`, `userId`, `characterId`, `targetDate`는 후속 API/SQS orchestration 계층에서 감싼다.

## 구현 범위

이번 story에서 구현할 것은 `generate_daily_report_result()` 내부 service 함수와 payload normalization이다.

1. Public service 함수
   - 새 파일 후보: `fastapi/app/scoring/daily_report_service.py`
   - 권장 public 함수:

```python
def generate_daily_report_result(
    *,
    report_title: str | None,
    report_content: str,
    user_metadata: Mapping[str, Any] | None = None,
    character_personality: str | None = None,
    character_metadata: Mapping[str, Any] | None = None,
    analyzer: Any | None = None,
    quiz_recommender: Any | None = None,
) -> dict[str, Any]:
    ...
```

   - `analyzer`는 테스트에서 fake를 주입할 수 있어야 한다.
   - `quiz_recommender`도 테스트에서 fake를 주입할 수 있어야 한다.
   - 주입이 없으면 Story 4의 `analyze_daily_report()` 또는 `GeminiReportAnalyzer`를 기본으로 사용한다.
   - 주입이 없으면 기존 `fastapi/app/rag/quiz_recommender.py`의 `build_recommended_quizzes()` 또는 `recommend_quizzes()`를 기본으로 사용한다.

2. Analyzer 결과 normalization
   - Story 4 `analyze_daily_report()`는 dict를 반환한다.
   - `GeminiReportAnalyzer.analyze()`는 `ReportAnalysis` dataclass를 반환한다.
   - service는 dict, dataclass `to_dict()`, 또는 mapping-like fake analyzer 결과를 안전하게 normalizing할 수 있어야 한다.
   - 최종 payload는 `status`, `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`를 포함한다.
   - Story 4 분석 전용 필드인 `topics`, `fieldEvidence`, `confidence`는 recommender input으로 사용할 수 있지만, callback-ready 최종 payload의 필수 top-level field로 노출하지 않는다.

3. `scoreDelta` 보존
   - `scoreDelta`는 Story 4 analyzer 결과만 사용한다.
   - recommender의 `scoreAllocation`, `daily_report_recommender`의 `scoreDeltaHint`, user metadata의 `reportDirection.scoreDeltaHint`, character current stats는 `scoreDelta`에 영향을 주면 안 된다.
   - `scoreDelta`는 항상 `db`, `algorithm`, `cs`, `network`, `framework` 5개 field를 유지한다.
   - analyzer가 이미 Story 4 policy로 clamp했더라도 service에서 최종 shape 보존용으로 5필드 normalization을 한 번 더 적용하는 것은 허용된다. 단, 추천 결과나 metadata로 값을 올리면 안 된다.

4. Recommender input 구성
   - 성공한 `ReportAnalysis`와 원본 리포트 텍스트를 기존 quiz recommender에 넘긴다.
   - 권장 input mapping:

```python
{
    "reportText": combined_report_text,
    "analysis": daily_report_text,
    "feedback": daily_report_feedback,
    "detectedKeywords": analysis_topics + next_recommendation_topics,
    "detectedFields": positive_score_delta_fields,
}
```

   - `detectedFields`는 추천 query 보조 신호일 뿐 `scoreDelta` 계산 근거가 아니다.
   - `fastapi/app/rag/daily_report_recommender.py`는 topic rule과 기존 wrapper 흐름을 참고할 수 있다. 다만 그 함수가 만든 heuristic `nextRecommendation` 또는 `scoreDeltaHint`가 Story 4의 `nextRecommendation`/`scoreDelta`를 덮어쓰면 안 된다.
   - 가장 명확한 기본 경로는 `fastapi/app/rag/quiz_recommender.py`의 `build_recommended_quizzes()` 또는 `recommend_quizzes()`를 직접 호출하는 것이다.

5. 추천 퀴즈 snapshot normalization
   - 기존 recommender는 `problem_to_recommendation_snapshot()`을 통해 기존 문제 은행 문제를 snapshot으로 반환한다.
   - 최종 `recommendedQuizzes`의 각 item은 최소 다음 field를 포함한다.
     - `problemId`
     - `question`
     - `modelAnswer`
     - `scoreAllocation`
   - `scoreAllocation`은 `db`, `algorithm`, `cs`, `network`, `framework` 5개 field를 포함하도록 정규화한다.
   - Spring Boot callback 핵심 payload에 붙이기 쉽게, 최종 recommended quiz item은 위 4개 핵심 field로 trim하는 편을 우선한다. 기존 recommender가 `difficulty`, `sourcePath`, `matchScore` 같은 debug field를 반환해도 최종 payload에 반드시 필요하지 않다.
   - recommender가 invalid item을 섞어 반환하면 service는 전체 리포트 결과를 실패시키지 말고 invalid item을 건너뛰거나 추천 목록만 빈 배열로 fallback한다.

6. Fallback 처리
   - analyzer가 예외를 던지면 전체 payload는 안전한 `FALLBACK`이 되고 `recommendedQuizzes`는 `[]`다.
   - analyzer가 `status="FALLBACK"`을 반환하면 recommender를 호출하지 않거나, 호출하더라도 최종 `recommendedQuizzes`는 반드시 `[]`다. 권장은 호출하지 않는 것이다.
   - recommender가 예외를 던져도 analyzer가 성공했다면 `status`, `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`은 가능한 범위에서 유지하고 `recommendedQuizzes`만 `[]`로 fallback한다.
   - 가까운 문제가 없거나 recommender가 빈 list를 반환하면 `recommendedQuizzes: []`를 유지한다.
   - fallback payload도 `dailyReport`와 `nextRecommendation` key를 유지한다. Story 4 fallback 값을 재사용하되, null 대신 callback-ready object를 유지하는 방향을 우선한다.

7. Forbidden field guardrail
   - 최종 output에는 quiz grading result, quiz submission, `gradings` 배열이 포함되지 않는다.
   - 최종 output에는 `submissionId`, `requestId`, `userId`, `characterId`, `targetDate`가 포함되지 않는다.
   - `grade_quiz_answer()`를 호출하지 않는다.
   - API endpoint, SQS consumer, Spring Boot callback sender를 만들지 않는다.

## 주요 파일 경로

구현 후보:

- `fastapi/app/scoring/daily_report_service.py`
- `fastapi/app/scoring/schemas.py`
- `fastapi/app/scoring/__init__.py`

테스트 후보:

- `fastapi/tests/scoring/test_daily_report_service.py`

재사용 대상:

- `fastapi/app/scoring/report_analyzer.py`
- `fastapi/app/scoring/report_analyzer.py`의 `analyze_daily_report()`
- `fastapi/app/scoring/report_analyzer.py`의 `GeminiReportAnalyzer`
- `fastapi/app/scoring/report_analyzer.py`의 `fallback_report_analysis()`
- `fastapi/app/scoring/policy.py`의 `zero_score_vector()`, `clamp_report_score_delta()`, `sanitize_score_allocation()`
- `fastapi/app/scoring/schemas.py`의 `ReportAnalysis`, `DailyReportAnalysis`, `NextRecommendation`
- `fastapi/app/rag/quiz_recommender.py`의 `build_recommended_quizzes()` 또는 `recommend_quizzes()`
- `fastapi/app/rag/daily_report_recommender.py`의 topic/recommendation input conventions
- `fastapi/app/rag/problem_bank_search.py`의 `problem_to_recommendation_snapshot()`
- `fastapi/data/rag/catalog/problems.jsonl`

수정하지 않아야 할 흐름:

- `fastapi/app/scoring/quiz_grader.py`
- `fastapi/app/scoring/quiz_grader.py`의 `grade_quiz_answer()`
- Story 2 `report_chunker.py` chunking semantics
- Story 3 RAG retrieval/source-neighborhood implementation
- Story 4 Gemini prompt/analysis/clamp semantics
- API router, SQS consumer, callback sender

## 권장 데이터 Shape

권장 dataclass를 추가한다면 기존 `schemas.py`의 dataclass + `to_dict()` 패턴을 유지한다.

```python
@dataclass(frozen=True)
class RecommendedQuizSnapshot:
    problem_id: int
    question: str
    model_answer: str
    score_allocation: dict[str, int]

    def to_dict(self) -> dict[str, Any]:
        return {
            "problemId": self.problem_id,
            "question": self.question,
            "modelAnswer": self.model_answer,
            "scoreAllocation": {
                field_name: self.score_allocation.get(field_name, 0)
                for field_name in SCORE_FIELDS
            },
        }


@dataclass(frozen=True)
class DailyReportResult:
    status: Literal["SUCCESS", "FALLBACK"]
    score_delta: dict[str, int]
    emotion: str
    status_message: str
    daily_report: DailyReportAnalysis
    next_recommendation: NextRecommendation
    recommended_quizzes: tuple[RecommendedQuizSnapshot, ...]

    def to_dict(self) -> dict[str, Any]:
        ...
```

dataclass 추가가 과하면 helper 함수만으로 dict를 조립해도 된다. 다만 final shape와 forbidden field guardrail은 테스트로 고정한다.

## Acceptance Criteria

1. `generate_daily_report_result()`는 일일 리포트 `report_title`, `report_content`와 선택적 `user_metadata`, `character_personality`, `character_metadata`를 받아 dict payload를 반환한다.
2. 결과는 `status`, `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`를 포함한다.
3. `status`는 `"SUCCESS"` 또는 `"FALLBACK"`만 반환한다.
4. `scoreDelta`는 Story 4 report analysis 결과만 반영한다.
5. `scoreDelta`는 `db`, `algorithm`, `cs`, `network`, `framework` 5개 field를 항상 포함한다.
6. `scoreDelta` 값은 recommender 결과, quiz `scoreAllocation`, `scoreDeltaHint`, `currentStats`, user metadata로 증가하지 않는다.
7. `dailyReport`는 `text`, `feedback`을 포함한다.
8. `nextRecommendation`은 `topics`, `rationale`을 포함한다.
9. `recommendedQuizzes`는 기존 퀴즈 추천 함수 결과를 사용한다.
10. 추천 퀴즈는 `problemId`, `question`, `modelAnswer`, `scoreAllocation` 스냅샷을 포함한다.
11. 추천 퀴즈의 `scoreAllocation`은 `db`, `algorithm`, `cs`, `network`, `framework` 5개 field를 포함한다.
12. 가까운 문제가 없으면 `recommendedQuizzes: []`를 반환한다.
13. recommender가 실패해도 리포트 분석 결과는 가능한 범위에서 유지하고 `recommendedQuizzes`만 `[]`로 fallback한다.
14. analyzer가 예외를 던지면 전체 payload는 안전한 `FALLBACK`이며 `recommendedQuizzes`는 `[]`다.
15. analyzer가 `FALLBACK`을 반환하면 전체 payload는 안전한 `FALLBACK`이며 recommender를 호출하지 않거나 `recommendedQuizzes`를 `[]`로 유지한다.
16. analyzer가 `FALLBACK`이면 quiz recommendation을 통해 성공 payload처럼 보이게 만들지 않는다.
17. 결과에는 quiz grading result가 포함되지 않는다.
18. 결과에는 quiz submission payload가 포함되지 않는다.
19. 결과에는 `gradings` 배열이 포함되지 않는다.
20. 결과에는 `submissionId`가 포함되지 않는다.
21. 결과에는 `requestId`, `userId`, `characterId`, `targetDate` wrapper field가 포함되지 않는다.
22. `grade_quiz_answer()`를 호출하지 않는다.
23. API endpoint를 만들지 않는다.
24. SQS consumer를 만들지 않는다.
25. Spring Boot callback 전송 함수를 만들지 않는다.
26. 기존 `quiz_recommender.py`의 문제 검색/중복 제거/limit 정책을 깨지 않는다.
27. 기존 `daily_report_recommender.py`를 사용하더라도 그 결과의 `scoreDeltaHint`나 heuristic `nextRecommendation`이 Story 4 결과를 덮어쓰지 않는다.

## 테스트 기준

필수 테스트:

- fake report analyzer와 fake quiz recommender로 최종 payload shape를 검증한다.
- fake analyzer가 `SUCCESS`와 5필드 `scoreDelta`, `dailyReport`, `nextRecommendation`을 반환하면 service output이 같은 분석 값을 보존하는지 검증한다.
- recommender가 정상 추천을 반환하면 `recommendedQuizzes`가 `problemId`, `question`, `modelAnswer`, `scoreAllocation` snapshot 형태로 채워지는지 검증한다.
- recommender가 `difficulty`, `sourcePath`, `matchScore` 같은 추가 field를 반환해도 final snapshot이 Spring Boot callback core shape와 호환되는지 검증한다.
- recommender가 빈 배열을 반환하면 `recommendedQuizzes: []`가 유지되는지 검증한다.
- recommender가 예외를 던져도 report analysis 결과는 유지되고 `recommendedQuizzes`만 `[]`로 fallback되는지 검증한다.
- analyzer가 예외를 던지면 전체 결과가 안전 `FALLBACK`으로 내려가고 `recommendedQuizzes`는 `[]`인지 검증한다.
- analyzer가 `FALLBACK`을 반환하면 recommender를 호출하지 않거나 `recommendedQuizzes`를 `[]`로 유지하는지 검증한다.
- analyzer가 `FALLBACK`을 반환했는데 fake recommender가 추천을 만들 수 있어도 final output은 `recommendedQuizzes: []`인지 검증한다.
- `scoreDelta` 5필드 `db`, `algorithm`, `cs`, `network`, `framework`가 유지되는지 검증한다.
- recommender의 `scoreAllocation` 또는 user metadata `scoreDeltaHint`가 `scoreDelta`를 바꾸지 않는지 검증한다.
- 추천 퀴즈의 `scoreAllocation`도 5필드 shape로 유지되는지 검증한다.
- forbidden fields: `gradings`, `submissionId`, `requestId`, `userId`, `characterId`, `targetDate`가 output에 없는지 검증한다.
- recommended quiz item에도 `submissionId`, grading result, `gradings`가 없는지 검증한다.
- `grade_quiz_answer()`가 호출되지 않는지 monkeypatch/mock으로 검증한다.
- API router, SQS consumer, callback sender 계층이 호출되지 않는지 검증한다. 이 story에서 그런 계층을 만들지 않는 것도 File List와 import graph로 확인한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_daily_report_service
```

관련 회귀 확인:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_report_analyzer tests.rag.test_problem_bank_search
```

전체 회귀 확인:

```bash
cd fastapi
python3 -m unittest discover -s tests
```

## 제외 범위

이번 story에서 하지 않는다.

- Story 2 report chunking 알고리즘 변경
- Story 3 RAG retrieval, embedding store, source-neighborhood 변경
- Story 4 Gemini report analysis prompt, parser, score clamp 정책 변경
- `chunks.jsonl` 또는 `chunk-embeddings.jsonl` 재생성
- `problems.jsonl` 문제 은행 재생성
- 기존 문제 은행 검색/추천 ranking 정책 변경
- 신규 퀴즈 생성
- quiz grading service 호출 또는 수정
- quiz submission, grading result, `gradings` 배열 사용
- `grade_quiz_answer()` 호출
- API endpoint
- SQS consumer
- Spring Boot `POST /api/report` callback 전송
- `requestId`, `userId`, `characterId`, `targetDate` wrapper 조립
- requestId 멱등성 저장소
- SQS 메시지 삭제, 재시도, DLQ 처리
- pgvector/운영 vector store

## Dev Notes

- 이 story는 orchestration과 payload assembly 계층이다. Story 4의 `ReportAnalysis`를 다시 분석하거나 Gemini prompt를 다시 작성하지 않는다.
- `fastapi/app/scoring/report_analyzer.py`의 `analyze_daily_report()`는 이미 dict를 반환한다. `GeminiReportAnalyzer.analyze()`는 `ReportAnalysis` dataclass를 반환한다. service normalization helper는 두 형태를 모두 받아야 테스트와 실제 사용이 편하다.
- Story 4 `fallback_report_analysis()`는 안전 fallback의 기준으로 재사용하기 좋다. fallback 결과에 `recommendedQuizzes: []`만 추가하면 Story 5 fallback payload가 된다.
- `SCORE_FIELDS` 순서는 `app.rag.schemas.SCORE_FIELDS` 기준인 `db`, `algorithm`, `cs`, `network`, `framework`를 유지한다.
- `scoreDelta` normalize에는 report 전용 `clamp_report_score_delta()`를 사용할 수 있다. quiz grading용 `clamp_score_delta(raw_delta, allocation)`은 allocation 상한을 전제로 하므로 final report score를 recommender `scoreAllocation`에 묶으면 안 된다.
- 추천은 기존 문제 은행에서 가져온 문제 snapshot이어야 한다. `fastapi/data/rag/catalog/problems.jsonl`의 `ProblemRecord`는 `problemId`, `question`, `modelAnswer`, `scoreAllocation`을 이미 가진다.
- `fastapi/app/rag/problem_bank_search.py`의 `problem_to_recommendation_snapshot()`은 기존 snapshot 생성 패턴이다. 다만 Spring Boot report callback core shape에는 `problemId`, `question`, `modelAnswer`, `scoreAllocation`만 필요하다.
- `fastapi/app/rag/quiz_recommender.py`의 `build_recommended_quizzes()` 또는 `recommend_quizzes()`는 `reportText`, `detectedKeywords`, `detectedFields`, `feedback`, `analysis` 같은 mapping value를 query로 확장할 수 있다.
- `daily_report_recommender.py`에도 `build_daily_report_recommendation()`이 있지만, 이 함수는 자체 heuristic `nextRecommendation`, `scoreDeltaHint`를 만든다. Story 5에서 사용한다면 오직 `recommendedQuizzes` 추출용으로만 쓰고 Story 4 분석 결과를 덮어쓰지 않는다.
- analyzer success 여부를 먼저 판단한 뒤 recommender를 호출한다. `FALLBACK` 분석 결과에 퀴즈를 붙이면 사용자가 분석 실패 결과에서 추천만 받는 모순이 생긴다.
- recommender failure는 partial failure다. 리포트 분석 자체가 성공했다면 전체 `status`를 `FALLBACK`으로 낮추기보다 `recommendedQuizzes: []`로만 degrade하는 편이 이번 story의 의도와 맞다.
- analyzer failure는 primary failure다. `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`의 근거가 없으므로 전체 `FALLBACK`으로 내려간다.
- output guardrail 테스트는 top-level뿐 아니라 nested recommended quiz item도 함께 확인한다. `submissionId`, `gradings`, callback wrapper field가 추천 item에 섞여도 제거해야 한다.
- 이 service는 후속 API/SQS 계층에서 wrapper를 얹기 쉬운 내부 payload를 반환한다. 그러므로 `requestId`, `userId`, `characterId`, `targetDate`를 여기서 받거나 저장하려 하지 않는다.
- unit test는 실제 Gemini API, 실제 embedding API, 실제 SQS/HTTP callback을 호출하지 않는다. fake analyzer와 fake recommender만으로 핵심 branch를 덮는다.

## Tasks/Subtasks

- [x] `fastapi/app/scoring/daily_report_service.py` 생성 (AC: 1)
- [x] `generate_daily_report_result()` public 함수 구현 (AC: 1, 2)
- [x] analyzer 주입/기본 호출 경로 구현 (AC: 4, 14, 15)
- [x] analyzer dict/dataclass 결과 normalization helper 구현 (AC: 2, 3, 5, 7, 8)
- [x] Story 4 fallback 결과에 `recommendedQuizzes: []`를 붙이는 fallback payload helper 구현 (AC: 14, 15, 16)
- [x] `scoreDelta` 5필드 shape 보존 helper 구현 또는 기존 policy 재사용 (AC: 4, 5, 6)
- [x] recommender input mapping 구성 helper 구현 (AC: 9, 26, 27)
- [x] 기존 `quiz_recommender.build_recommended_quizzes()` 또는 `recommend_quizzes()` 기본 호출 경로 구현 (AC: 9, 10, 12, 13)
- [x] recommender 주입 경로 구현 (AC: 9, 13)
- [x] recommended quiz snapshot normalization helper 구현 (AC: 10, 11, 17, 18, 19, 20, 21)
- [x] analyzer `FALLBACK`이면 recommender 비호출 또는 추천 무시 guardrail 구현 (AC: 15, 16)
- [x] forbidden field 제거/검증 helper 구현 (AC: 17, 18, 19, 20, 21)
- [x] `grade_quiz_answer()`를 import/call하지 않도록 import boundary 확인 (AC: 22)
- [x] API/SQS/callback wrapper 계층을 추가하지 않았는지 확인 (AC: 23, 24, 25)
- [x] `fastapi/tests/scoring/test_daily_report_service.py` 작성 (전체 AC)
- [x] 관련 scoring/rag 회귀 테스트 실행

## Project Structure Notes

- 새 service는 `fastapi/app/scoring` 아래에 둔다. 리포트 분석 결과를 조립하는 scoring 계층 책임이기 때문이다.
- 기존 `fastapi/app/rag` 모듈은 추천 문제 검색과 snapshot 생성 책임을 유지한다. Story 5는 그 결과를 호출/정규화만 한다.
- `fastapi/app/scoring/schemas.py`에 `DailyReportResult` 또는 `RecommendedQuizSnapshot`을 추가할 수 있다. 단, 기존 `QuizGradingResult`, `ReportAnalysis`, `ReportChunk` contract를 깨면 안 된다.
- 테스트는 `fastapi/tests/scoring/test_daily_report_service.py`에 둔다. 실제 catalog 파일이나 Gemini API에 의존하지 않는 pure unit test를 우선한다.
- API router, SQS consumer, callback sender 파일을 새로 만들면 이번 story 범위 이탈이다.
- sprint status 파일은 story 구현 완료/리뷰 단계에서 별도 workflow로 갱신한다. 이 story 생성 작업에서는 story 파일만 생성한다.

## References

- Epic Story 5 요구: `fastapi/docs/report/report-generation-epic.md#Story-5-리포트-결과-조립-및-기존-quiz-recommender-연결`
- Sprint tracking: `fastapi/docs/report/report-generation-sprint-status.yaml`
- Story 1 concept catalog: `fastapi/docs/report/stories/report-1-concept-chunk-catalog.md`
- Story 2 report chunking: `fastapi/docs/report/stories/report-2-report-chunking-topic-field-hints.md`
- Story 3 RAG evidence: `fastapi/docs/report/stories/report-3-rag-search-and-source-neighborhood.md`
- Story 4 report analysis and Story 5 boundary: `fastapi/docs/report/stories/report-4-gemini-report-analysis-score-delta.md`
- Existing report analyzer: `fastapi/app/scoring/report_analyzer.py`
- Existing scoring schemas/policy: `fastapi/app/scoring/schemas.py`, `fastapi/app/scoring/policy.py`
- Existing quiz recommender: `fastapi/app/rag/quiz_recommender.py`
- Existing daily report recommender wrapper: `fastapi/app/rag/daily_report_recommender.py`
- Existing problem snapshot helper: `fastapi/app/rag/problem_bank_search.py`
- Existing problem catalog: `fastapi/data/rag/catalog/problems.jsonl`
- Architecture report callback contract: `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.2`

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- RED: `cd fastapi && python3 -m unittest tests.scoring.test_daily_report_service` 실행 시 `app.scoring.daily_report_service` import 실패를 확인했다.
- GREEN: `cd fastapi && python3 -m unittest tests.scoring.test_daily_report_service` 6개 테스트 통과.
- 회귀: `cd fastapi && python3 -m unittest tests.scoring.test_report_analyzer tests.rag.test_problem_bank_search tests.rag.test_quiz_recommender` 28개 테스트 통과.
- 전체: `cd fastapi && python3 -m unittest discover -s tests` 100개 테스트 통과.
- 경계 확인: `daily_report_service.py`는 `grade_quiz_answer()`를 import/call하지 않고 API router, SQS consumer, callback sender 파일을 추가하지 않았다.

### Completion Notes List

- `generate_daily_report_result()` 내부 서비스를 추가해 Story 4 `ReportAnalysis` dict/dataclass 결과를 callback-ready 핵심 payload로 정규화했다.
- analyzer 성공 시 `scoreDelta`, `dailyReport`, `nextRecommendation`, `emotion`, `statusMessage`는 Story 4 결과만 보존하고, recommender 입력은 추천 query 보조 신호로만 구성했다.
- analyzer 예외 또는 `FALLBACK` 결과는 전체 안전 `FALLBACK` payload와 `recommendedQuizzes: []`로 내려가며 recommender를 호출하지 않는다.
- recommender 예외는 리포트 분석 결과를 유지한 채 `recommendedQuizzes: []`로만 degrade한다.
- 추천 퀴즈는 기존 recommender snapshot에서 `problemId`, `question`, `modelAnswer`, 5필드 `scoreAllocation`만 남기고 debug/채점/wrapper 필드는 제거한다.
- `DailyReportResult`와 `RecommendedQuizSnapshot` dataclass를 추가하고 `app.scoring` lazy export에 public 함수를 연결했다.

### File List

- `fastapi/app/scoring/daily_report_service.py`
- `fastapi/app/scoring/schemas.py`
- `fastapi/app/scoring/__init__.py`
- `fastapi/tests/scoring/test_daily_report_service.py`
- `fastapi/docs/report/stories/report-5-report-result-assembly-quiz-recommender.md`
- `fastapi/docs/report/report-generation-sprint-status.yaml`

### Change Log

- 2026-06-13: Story 5 context document created and marked ready-for-dev.
- 2026-06-13: Implemented report result assembly service, quiz recommender normalization/fallback guardrails, unit coverage, and regression verification; story marked review.
