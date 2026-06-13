---
title: Commitgotchi 리포트·퀴즈·정량화 구현 계획
status: draft
created: 2026-06-11
owner: FastAPI AI 서버
output_scope: fastapi 하위 산출물
related_docs:
  - fastapi/docs/rag-implementation-plan.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4
---

# Commitgotchi 리포트·퀴즈·정량화 구현 계획

## 1. 목적

이 문서는 FastAPI AI 서버에서 다음 세 가지를 어떻게 구현할지 정리한다.

1. 사용자의 일일 학습 리포트를 분석해 `scoreDelta`, 피드백, 다음 학습 추천을 생성한다.
2. 사용자의 리포트에 맞는 기존 퀴즈를 추천하고, 퀴즈 답안을 채점한다.
3. 리포트와 퀴즈 결과를 5개 능력치 점수로 정량화한다.

핵심 방향은 **문제 뱅크 + 청크 grounding + LLM 구조화 출력 + 서버 검증**이다. 키워드 규칙만으로 사용자 리포트를 분류하면 표현 변형과 주제 조합 때문에 카디널리티가 폭발하므로, 키워드는 메인 로직이 아니라 fallback과 보조 feature로만 둔다.

## 2. 점수 정책 명세

### 2.1 필드

점수 필드는 아키텍처 계약과 동일하게 5개로 고정한다.

```json
{
  "db": 0,
  "algorithm": 0,
  "cs": 0,
  "network": 0,
  "framework": 0
}
```

### 2.2 필드별 독립 최대점

점수는 **총합 10점이 아니다.** 각 필드는 독립적으로 `0..10` 범위를 가진다.

따라서 리포트를 아주 충실히 작성하거나, 퀴즈가 여러 필드에 걸친 고난도 문제라면 이론상 하루 리포트 또는 단일 결과에서 다음처럼 총 50점까지 가능하다.

```json
{
  "db": 10,
  "algorithm": 10,
  "cs": 10,
  "network": 10,
  "framework": 10
}
```

### 2.3 Clamp 규칙

서버는 LLM이나 검색 결과가 어떤 값을 내더라도 마지막에 필드별 clamp를 적용한다.

```python
delta[field] = max(0, min(raw_delta[field], max_allowed[field]))
```

리포트 분석에서는 `max_allowed[field] = 10`이다.

퀴즈 채점에서는 `max_allowed[field] = scoreAllocation[field]`이다. `scoreAllocation[field]` 역시 필드별 최대 `10`을 넘을 수 없다.

### 2.4 이중계상 금지

리포트 점수와 퀴즈 점수는 별도 출처다.

| 출처 | FastAPI 산출물 | 반영 시점 | 최대 범위 |
| --- | --- | --- | --- |
| 일일 리포트 | `reportScoreDelta` | 흐름 A 리포트 콜백 | 필드별 `0..10` |
| 퀴즈 답안 | `quizScoreDelta` | 흐름 B 채점 웹훅 | 필드별 `0..scoreAllocation[field]` |

리포트 분석이 퀴즈 채점 점수를 다시 더하면 안 된다. 리포트는 사용자가 작성한 학습 기록 자체만 점수화한다.

## 3. 전체 흐름

```text
사용자 리포트
  -> Report Analyzer
     -> 관련 MD 청크 검색(top-k)
     -> 청크를 기준 근거로 제공
     -> LLM structured output
     -> 서버 검증/clamp
     -> reportScoreDelta, dailyReport, nextRecommendation
  -> Quiz Recommender
     -> FastAPI 내부 문제 뱅크에서 기존 문제 검색
     -> 가까운 문제가 있으면 recommendedQuizzes
     -> 가까운 문제가 없으면 []

사용자 퀴즈 답안
  -> Quiz Grader
     -> question/modelAnswer/scoreAllocation 기준 채점
     -> 필요 시 problemId로 근거 검색
     -> LLM structured output
     -> 서버 검증/clamp
     -> quizScoreDelta, feedback
```

## 4. 리포트 분석 구현 계획

### 4.1 역할

리포트 분석은 사용자가 쓴 자연어 학습 기록을 다음 정형 결과로 바꾼다.

```json
{
  "topics": ["JPA N+1", "fetch join", "ORM 성능 최적화"],
  "fieldEvidence": {
    "db": "N+1 문제와 쿼리 최적화는 DB 접근 성능 주제입니다.",
    "framework": "JPA와 fetch join은 Spring/JPA 사용 주제입니다."
  },
  "scoreDelta": {
    "db": 7,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 5
  },
  "confidence": 0.86,
  "summary": "JPA N+1의 원인과 fetch join 해결 전략을 학습했습니다.",
  "feedback": "원인과 해결책을 함께 다뤄 실무적인 학습으로 볼 수 있습니다."
}
```

### 4.2 LLM 구조화 출력

키워드 규칙만으로 리포트를 분류하지 않는다. 메인 판단은 LLM이 맡되, 반드시 JSON schema 또는 Pydantic 모델로 검증한다.

LLM 입력에는 리포트 원문만 넣지 않는다. 먼저 MD 청크 검색으로 관련 개념 top-k를 찾고, 그 청크의 제목/요약/출처를 함께 넣는다. 단, **리포트와 청크의 거리 점수를 그대로 학습 점수로 환산하지 않는다.** 청크 거리는 주제 grounding이고, 점수는 리포트가 해당 개념을 얼마나 구체적으로 이해했는지에 대한 LLM/rubric 판단이다.

권장 출력 모델:

```python
class ReportAnalysis(BaseModel):
    topics: list[str]
    field_evidence: dict[ScoreField, str]
    score_delta: ScoreVector
    confidence: float
    summary: str
    feedback: str
```

검증 규칙:

- `score_delta`는 5개 필드를 모두 포함한다.
- 각 필드는 `0..10`이다.
- `confidence`는 `0..1`이다.
- `field_evidence`가 없는 필드는 점수를 0으로 낮추거나 경고로 남긴다.
- `confidence < 0.5`이면 보수적으로 전 필드 0점 또는 최대 2점 이하로 제한한다.

### 4.3 MD 청크의 역할

MD 청크는 리포트 점수의 직접 산식이 아니라 평가 기준을 안정화하는 근거다.

| 용도 | 설명 |
| --- | --- |
| 주제 grounding | 리포트가 어떤 CS 주제와 가까운지 찾는다. |
| 현재 공부 보강 | 사용자가 언급했지만 얕게 다룬 주변 개념을 피드백에 넣는다. |
| 다음 공부 추천 | 리포트와 가까운 청크의 인접 주제를 추천한다. |
| 퀴즈 추천 연결 | 문제 뱅크에서 가까운 문제를 찾는 검색 쿼리/필터로 사용한다. |

주의:

- 청크와 가까운 리포트가 항상 높은 점수를 받아야 하는 것은 아니다.
- 예: "N+1을 봤는데 잘 모르겠다"는 N+1 청크와 가깝지만 높은 점수를 주면 안 된다.
- 따라서 청크 검색은 “무슨 주제인가”를 찾고, LLM/rubric은 “얼마나 이해했는가”를 판단한다.

권장 리포트 분석 입력:

```json
{
  "reportText": "오늘 JPA N+1이 Lazy Loading에서 왜 발생하는지 정리했고 fetch join과 BatchSize 차이를 비교했다.",
  "retrievedConcepts": [
    {
      "title": "N+1 문제와 해결 방법",
      "summary": "N+1 문제는 연관 엔티티 조회 시 1번의 기본 조회와 N번의 추가 조회가 발생하는 현상이다. fetch join, EntityGraph, BatchSize 등으로 완화할 수 있다.",
      "sourcePath": "..."
    }
  ]
}
```

### 4.4 점수 산정 기준

리포트 점수는 “얼마나 많이 썼는가”가 아니라 “학습 내용이 얼마나 구체적이고 검증 가능한가”를 본다.

| 점수 | 기준 |
| ---: | --- |
| 0 | 해당 필드 학습 근거 없음 |
| 1-2 | 키워드만 언급, 학습 내용 불명확 |
| 3-4 | 개념을 간단히 설명 |
| 5-6 | 원인/동작 방식/예시 중 2개 이상 설명 |
| 7-8 | 해결책, 비교, 트레이드오프까지 설명 |
| 9-10 | 실무 적용, 한계, 대안, 반례까지 포함 |

예시:

```text
오늘 JPA N+1 문제와 fetch join 해결 방법을 공부했다.
```

예상:

```json
{
  "db": 4,
  "framework": 3
}
```

```text
오늘 JPA N+1이 Lazy Loading에서 왜 발생하는지 정리했고,
fetch join, EntityGraph, BatchSize의 차이와 페이징 한계를 비교했다.
```

예상:

```json
{
  "db": 8,
  "framework": 8
}
```

## 5. 퀴즈 추천 구현 계획

### 5.1 원칙

퀴즈 추천은 리포트 분석 결과를 바탕으로 FastAPI 내부 문제 뱅크 파일에서 가까운 문제를 찾는다.

중요한 정책:

- 가까운 문제가 없으면 문제를 내지 않는다.
- 신규 문제 생성은 MVP에서는 하지 않는다.
- 문제 추천은 점수 산정의 필수 의존성이 아니다.
- 문제 뱅크 항목의 `problemId`는 추적용 ID이며, Spring Boot에는 `question`, `modelAnswer`, `scoreAllocation`까지 포함한 문제 스냅샷 전체를 보낸다.
- RAG/Retrieval은 추천 문제와 주변 개념 근거를 찾는 데 사용한다.

### 5.2 추천 입력

```json
{
  "reportText": "오늘 JPA N+1 문제와 fetch join 해결 방법을 공부했다.",
  "reportAnalysis": {
    "topics": ["JPA N+1", "fetch join"],
    "scoreDelta": {
      "db": 4,
      "framework": 3
    }
  },
  "currentStats": {
    "db": 120,
    "algorithm": 200,
    "cs": 80,
    "network": 60,
    "framework": 140
  }
}
```

### 5.3 추천 출력

아키텍처 4.2의 `recommendedQuizzes`와 호환한다. `problemId`만 보내지 않고 문제 스냅샷 전체를 보낸다.

```json
{
  "recommendedQuizzes": [
    {
      "problemId": 114657103,
      "question": "N+1 문제란 무엇이며, 어떻게 해결하나요?",
      "modelAnswer": "N+1 문제는 ...",
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

### 5.4 `scoreAllocation` 정책

`scoreAllocation`은 “퀴즈를 완벽히 맞혔을 때 필드별로 받을 수 있는 최대점”이다.

난이도별 기본값:

| 난이도 | 관련 필드별 최대점 |
| --- | ---: |
| basic | 5 |
| intermediate | 7 |
| advanced | 10 |

복합 주제는 관련 필드에 각각 최대점을 준다.

예시:

| 문제 | 난이도 | scoreAllocation |
| --- | --- | --- |
| REST API란 무엇인가요? | intermediate | `network: 7` |
| JWT와 OAuth의 차이는? | intermediate | `network: 7`, `framework: 7` |
| JPA N+1과 fetch join 한계는? | advanced | `db: 10`, `framework: 10` |

## 6. 퀴즈 채점 구현 계획

### 6.1 역할

퀴즈 채점은 Spring Boot가 넘긴 `question`, `modelAnswer`, `userAnswer`, `scoreAllocation`을 기준으로 사용자의 답변을 평가한다.

RAG는 보조 근거다. `problemId`로 원문 문제와 주변 개념을 찾을 수 있으면 LLM 채점 프롬프트에 추가한다. 검색 실패 시에도 채점은 Spring Boot가 넘긴 `modelAnswer` 스냅샷만으로 진행한다.

### 6.2 채점 출력

```json
{
  "submissionId": "quiz-submission-uuid",
  "status": "GRADED",
  "scoreAllocation": {
    "db": 10,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 10
  },
  "scoreDelta": {
    "db": 7,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 6
  },
  "feedback": "N+1의 원인은 잘 설명했지만 BatchSize와 fetch join의 페이징 한계가 빠졌습니다.",
  "confidence": 0.82
}
```

### 6.3 채점 기준

퀴즈는 필드별 rubric을 사용한다.

| 점수 비율 | 기준 |
| ---: | --- |
| 0% | 무응답, 무관한 답변 |
| 20% | 키워드 일부만 언급 |
| 40% | 핵심 정의 설명 |
| 60% | 원인/동작 방식 설명 |
| 80% | 해결책/비교/주의점 포함 |
| 100% | 모범답안의 핵심 요소와 한계까지 포함 |

예를 들어 `scoreAllocation.db = 10`이고 LLM이 DB 관점 채점 비율을 `0.7`로 판단하면:

```text
scoreDelta.db = round(10 * 0.7) = 7
```

최종 서버 검증:

```python
score_delta[field] = clamp(score_delta[field], 0, score_allocation[field])
```

## 7. 정량화 모듈 구조

권장 파일 구조:

```text
fastapi/app/scoring/
├── __init__.py
├── schemas.py              # ScoreVector, ReportAnalysis, QuizGradingResult
├── policy.py               # clamp, confidence fallback, difficulty max score
├── report_analyzer.py      # 리포트 -> 정형 분석
├── quiz_allocator.py       # 문제 -> scoreAllocation
├── quiz_grader.py          # 답안 -> scoreDelta
└── prompts/
    ├── report_analysis.md
    └── quiz_grading.md
```

RAG 관련 파일은 `fastapi/app/rag`에 두고, 점수 정책은 `fastapi/app/scoring`으로 분리한다. 이렇게 해야 검색 로직을 바꿔도 점수 정책이 흔들리지 않는다.

## 8. 구현 순서

### 1단계: 점수 스키마와 정책

- `ScoreField` enum 정의
- `ScoreVector` 모델 정의
- 필드별 `0..10` clamp 구현
- 퀴즈용 `0..scoreAllocation[field]` clamp 구현
- 난이도별 관련 필드 최대점 정책 구현

완료 기준:

- 총합 cap이 없음
- `10,10,10,10,10`이 유효한 벡터로 통과함
- 음수와 10 초과 값은 clamp됨

### 2단계: 리포트 분석 프로토타입

- LLM 없이 규칙 기반 fallback 유지
- LLM 연동 전에도 `ReportAnalysis` mock 또는 heuristic analyzer 제공
- API 붙이기 전 CLI로 리포트 입력 -> scoreDelta 확인

완료 기준:

- `JPA N+1 + fetch join` 리포트가 `db/framework`로 잡힘
- `OAuth + JWT` 리포트가 `network/framework`로 잡힘
- 무관한 리포트는 낮은 confidence와 0점에 가까운 결과를 냄

### 3단계: LLM 구조화 출력 연결

- 리포트 분석 prompt 작성
- JSON schema 또는 Pydantic validation 연결
- confidence fallback 구현
- LLM 실패 시 heuristic fallback

완료 기준:

- 사용자 표현 변형에도 필드 분류가 안정적임
- LLM이 없는 필드를 만들거나 10 초과 점수를 주면 서버가 교정함

### 4단계: 문제 뱅크 기반 퀴즈 추천 연결

- `fastapi/data/rag/catalog/problems.jsonl` 문제 뱅크 검색 결과를 `QuizRecommendation`으로 변환
- 가까운 문제가 없으면 `recommendedQuizzes: []`
- `scoreAllocation`은 문제 뱅크에 저장된 값을 사용한다. 생성/검증 규칙은 scoring policy가 담당한다.

완료 기준:

- 문제 추천과 점수 배정이 분리됨
- 추천 실패가 리포트 분석 실패로 번지지 않음
- 추천 결과는 `problemId`, `question`, `modelAnswer`, `scoreAllocation` 전체를 포함함

### 5단계: 퀴즈 채점 연결

- Spring Boot 요청 payload 기준 채점
- `problemId` 기반 RAG 근거는 optional
- LLM 채점 결과를 서버에서 clamp

완료 기준:

- `scoreDelta[field] <= scoreAllocation[field]`
- LLM 실패 시 `UNGRADED`, 점수 0
- 재시도/웹훅 멱등성은 아키텍처 4.3 계약을 따른다

## 9. 테스트 계획

단위 테스트:

- `ScoreVector`가 5개 필드를 항상 포함하는지
- 리포트 delta가 필드별 `0..10`으로 clamp되는지
- 퀴즈 delta가 `scoreAllocation`을 넘지 않는지
- 총합 50점 벡터가 유효한지
- 난이도별 `scoreAllocation`이 관련 필드마다 `5/7/10`으로 생성되는지

통합 테스트:

- 리포트 문장 -> report analysis -> quiz recommendation
- 퀴즈 답안 -> grading result -> clamp
- RAG 검색 실패 -> recommendedQuizzes 빈 배열
- LLM 실패 -> fallback result

샘플 테스트 문장:

```text
오늘 JPA N+1 문제와 fetch join 해결 방법을 공부했다.
오늘 OAuth 로그인 흐름과 JWT 인증 방식을 공부했다.
오늘 운영체제의 프로세스와 스레드 차이를 정리했다.
오늘 REST API의 멱등성과 PUT/PATCH 차이를 공부했다.
오늘 바나나 재배와 토양 산도를 공부했다.
```

## 10. 정확성 추적과 Source of Truth

정량화 결과는 golden dataset으로 회귀 추적한다. source of truth는 MD 원문 자체가 아니라, 사람이 MD와 도메인 기준을 보고 만든 평가 라벨 세트다.

권장 파일 구조:

```text
fastapi/evals/
├── cases/
│   ├── report_analysis_cases.jsonl
│   ├── quiz_recommendation_cases.jsonl
│   ├── score_allocation_cases.jsonl
│   └── quiz_grading_cases.jsonl
├── run_eval.py
└── reports/
    └── latest.md
```

리포트 분석 케이스 예시:

```json
{
  "caseId": "report-jpa-nplus-001",
  "type": "report_analysis",
  "input": {
    "report": "오늘 JPA N+1이 Lazy Loading에서 왜 발생하는지 정리했고 fetch join과 BatchSize 차이를 비교했다."
  },
  "expected": {
    "topics": ["JPA N+1", "Lazy Loading", "fetch join", "BatchSize"],
    "scoreDeltaRange": {
      "db": [7, 10],
      "algorithm": [0, 0],
      "cs": [0, 2],
      "network": [0, 0],
      "framework": [6, 10]
    },
    "mustMention": ["N+1", "Lazy Loading", "fetch join"],
    "mustNotMention": ["OAuth", "TCP"]
  }
}
```

평가 지표:

| 지표 | 의미 |
| --- | --- |
| contract pass rate | JSON schema, 필드 누락, 범위 위반 여부 |
| field accuracy | 기대 필드와 실제 필드의 일치 정도 |
| score range violation | 기대 점수 범위를 벗어난 필드 수 |
| recommendation hit rate | 추천 `problemId`가 기대 후보군 안에 있는 비율 |
| feedback keyword pass | `mustMention` 포함, `mustNotMention` 미포함 |
| regression count | 이전 실행보다 나빠진 케이스 수 |

정확히 한 점수만 정답으로 두지 않고 범위로 둔다. AI 채점은 6점과 7점 경계가 애매할 수 있으므로, exact match보다 허용 범위와 금지 오판을 함께 본다.

## 11. 결론

정량화는 RAG의 문제가 아니라 **scoring policy와 structured analysis의 문제**다.

따라서 구현 책임을 다음처럼 나눈다.

| 영역 | 책임 |
| --- | --- |
| LLM structured analyzer | 사용자 리포트와 답안의 의미 분석 |
| scoring policy | 필드별 점수 범위, 난이도, clamp, confidence fallback |
| RAG/retrieval | 문제 뱅크 검색, 주변 개념 검색, 리포트 grounding |
| Spring Boot | FastAPI 결과 저장, 점수 반영 트랜잭션, 멱등성 |

키워드 기반 분류는 계속 보조 수단으로 남긴다. 하지만 사용자 리포트의 표현 다양성을 감당하는 메인 엔진은 LLM 구조화 출력으로 둔다.
