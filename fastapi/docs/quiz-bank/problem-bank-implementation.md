---
title: FastAPI 문제 은행 구현 현황
status: implemented
created: 2026-06-11
owner: FastAPI AI 서버
scope: 정적 문제 은행 생성, 런타임 로드/검색, 리포트 기반 내부 추천
---

# FastAPI 문제 은행 구현 현황

## 1. 요약

FastAPI AI 서버 내부에 정적 문제 은행 파이프라인을 구현했다.

현재 완료된 흐름은 다음과 같다.

```text
/Users/tlsdla1235/study/md파일들
  Markdown/CSV 원본 자료
        ↓
문제 은행 생성 CLI
        ↓
fastapi/data/rag/catalog/problems.jsonl
        ↓
FastAPI 런타임 loader/search
        ↓
일일 리포트 기반 recommendedQuizzes[] 내부 생성
```

이번 구현은 API 엔드포인트, Spring Boot 콜백, LLM 연동, 임베딩, pgvector 저장을 포함하지 않는다. 서비스 경계는 FastAPI 내부 Python 함수까지다.

## 2. 구현된 기능

### 2.1 정적 문제 은행 생성

`fastapi/app/rag/problem_bank.py`에서 소스 discovery, Markdown/CSV 파싱, 문제 레코드 생성, JSONL writer, skipped/rejected/summary 리포트 생성을 처리한다.

주요 동작:

- `/Users/tlsdla1235/study/md파일들` 하위 Markdown/CSV 자료를 읽는다.
- `(대외비)`, `.DS_Store`, 빈 파일, 바이너리, 이미지, `node_modules`는 제외한다.
- QnA Markdown과 Notion/스터디 문제 형식에서 질문과 모범답안을 추출한다.
- 모범답안이 없는 문제는 추천 가능 문제 은행에 저장하지 않고 skipped 리포트에 남긴다.
- 각 문제에 `problemId`, `sourceKey`, `contentHash`, `difficulty`, `primaryField`, `fields`, `scoreAllocation`, `rubric`을 부여한다.
- 최종 문제 은행은 `fastapi/data/rag/catalog/problems.jsonl`에 저장한다.

CLI:

```bash
cd fastapi
python3 -m app.rag.build_problem_bank --source /Users/tlsdla1235/study/md파일들 --out data/rag
```

dry-run:

```bash
cd fastapi
python3 -m app.rag.build_problem_bank --source /Users/tlsdla1235/study/md파일들 --out data/rag --dry-run
```

현재 생성 결과:

| 항목 | 수량 |
| --- | ---: |
| discovered files | 205 |
| candidate files | 170 |
| parsed candidates | 1148 |
| stored problems | 798 |
| skipped entries | 393 |
| rejected entries | 0 |

생성 산출물:

- `fastapi/data/rag/catalog/problems.jsonl`
- `fastapi/data/rag/manifests/problem-bank-discovery.json`
- `fastapi/data/rag/reports/problem-bank-summary.json`
- `fastapi/data/rag/reports/problem-bank-summary.md`
- `fastapi/data/rag/reports/problem-bank-skipped.jsonl`
- `fastapi/data/rag/reports/problem-bank-rejected.jsonl`

## 3. 문제 레코드 계약

문제 은행의 추천 가능 문제는 `ProblemRecord`로 표현된다.

필수 필드:

- `problemId`: FastAPI 내부 추적용 정수 ID. Spring Boot 조회키가 아니다.
- `sourceKey`: 출처 경로, heading path, 질문을 기반으로 만든 stable key.
- `question`: 추천 퀴즈 질문.
- `modelAnswer`: 채점 기준으로 사용할 모범답안.
- `difficulty`: `basic`, `intermediate`, `advanced`.
- `primaryField`: 대표 점수 필드.
- `fields`: 관련 점수 필드 목록.
- `scoreAllocation`: 필드별 최대 획득 점수.
- `sourcePath`: 원천 자료의 상대 경로.
- `headingPath`: 원천 문서 내 heading 경로.
- `rubric`: `mustMention`, `optionalMention`, `mustNotConfuse`.
- `contentHash`: 문제 내용 변경 추적용 해시.

`scoreAllocation` 정책:

- 총합 10점 cap은 없다.
- 각 필드는 독립적으로 `0..10` 범위를 가진다.
- 필드는 `db`, `algorithm`, `cs`, `network`, `framework` 5개로 고정한다.
- 난이도별 관련 필드 최대점은 `basic=5`, `intermediate=7`, `advanced=10`이다.

예시:

```json
{
  "problemId": 114657103,
  "question": "N+1 문제란 무엇이며, 어떻게 해결하나요?",
  "modelAnswer": "N+1 문제는 ...",
  "difficulty": "advanced",
  "primaryField": "db",
  "fields": ["db", "framework"],
  "scoreAllocation": {
    "db": 10,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 10
  }
}
```

## 4. 런타임 로드와 검색

`fastapi/app/rag/problem_bank_store.py`와 `fastapi/app/rag/problem_bank_search.py`는 생성된 `problems.jsonl`을 FastAPI 런타임에서 읽고 내부 검색에 사용한다.

구현된 기능:

- `problems.jsonl` reader
- `ProblemRecord` 변환과 validation
- 파일 없음/빈 파일 안전 처리
- `ProblemBankStore` 메모리 보관
- `problemId` 단건 조회
- `field`, `difficulty` 필터
- 간단한 keyword 기반 후보 검색
- 추천 응답용 문제 스냅샷 변환

파일 크기:

- `problems.jsonl`: 약 1.5 MB
- 798개 문제를 Python 객체로 로드했을 때 대략 4 MiB 수준

따라서 현재 MVP 규모에서는 앱 시작 시 또는 필요 시 전체 문제 은행을 메모리에 로드하는 방식이 충분하다.

## 5. 리포트 기반 추천 내부 서비스

`fastapi/app/rag/quiz_recommender.py`는 문제 은행 검색 결과를 추천 퀴즈 스냅샷으로 변환한다.

`fastapi/app/rag/daily_report_recommender.py`는 일일 리포트 텍스트를 받아 다음 값을 구성한다.

- `topics`
- `detectedFields`
- `scoreDeltaHint`
- `nextRecommendation`
- `recommendedQuizzes`

반환 shape:

```json
{
  "topics": ["JPA N+1", "Fetch Join"],
  "detectedFields": ["db", "framework"],
  "scoreDeltaHint": "field_focus:db,framework",
  "nextRecommendation": "가까운 기존 문제를 다음 추천 퀴즈 후보로 사용합니다.",
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

추천 정책:

- 기존 문제 은행에 있는 문제만 추천한다.
- 런타임 신규 문제를 생성하지 않는다.
- 가까운 문제가 없으면 `recommendedQuizzes: []`를 반환한다.
- 추천 개수는 최대 3개다.
- 같은 문제, 같은 source key, 같은 content hash, 같은 주제 중복을 피한다.
- 추천 결과는 Spring Boot가 저장할 수 있는 문제 스냅샷을 포함한다.

## 6. 주요 파일

구현 파일:

- `fastapi/app/rag/schemas.py`
- `fastapi/app/rag/text.py`
- `fastapi/app/rag/problem_bank.py`
- `fastapi/app/rag/build_problem_bank.py`
- `fastapi/app/rag/problem_bank_store.py`
- `fastapi/app/rag/problem_bank_search.py`
- `fastapi/app/rag/quiz_recommender.py`
- `fastapi/app/rag/daily_report_recommender.py`

테스트 파일:

- `fastapi/tests/rag/test_problem_schema.py`
- `fastapi/tests/rag/test_problem_bank.py`
- `fastapi/tests/rag/test_problem_bank_store.py`
- `fastapi/tests/rag/test_problem_bank_search.py`
- `fastapi/tests/rag/test_quiz_recommender.py`
- `fastapi/tests/rag/test_daily_report_recommender.py`

설계 문서:

- `fastapi/docs/quiz-bank/problem-bank-epic.md`
- `fastapi/docs/quiz-bank/problem-bank-implementation.md`

## 7. 검증

테스트 명령:

```bash
cd fastapi
python3 -m unittest discover -s tests
```

현재 검증 결과:

```text
Ran 32 tests ... OK
```

실제 catalog sanity check:

- `JPA N+1과 fetch join을 공부했다` 입력 시 `db/framework` 관련 문제가 추천된다.
- `OAuth와 JWT 인증 흐름을 공부했다` 입력 시 `network/framework` 관련 문제가 추천된다.
- 무관한 문장은 `recommendedQuizzes: []`를 반환한다.

## 8. 제외 범위

아직 구현하지 않은 항목:

- FastAPI API 엔드포인트
- Spring Boot 콜백 연동
- SQS 메시지 처리 연결
- LLM 기반 리포트 분석
- LLM 기반 퀴즈 채점
- RAG 임베딩 생성
- pgvector 저장
- 벡터 검색
- 운영용 admin endpoint

## 9. 다음 단계 후보

다음 story로는 API/계약 연결 전에 퀴즈 채점 내부 서비스 함수를 먼저 구현하는 것이 좋다. 추천 퀴즈 생성과 퀴즈 답안 채점을 모두 서비스 함수로 고립한 뒤, 이후 리포트 오케스트레이터나 API 엔드포인트에서 조합한다.

권장 순서:

1. `fastapi/docs/quiz-bank/quiz-grading-service-plan.md`를 기준으로 `grade_quiz_answer()`를 구현한다.
2. Gemini 기반 채점 결과가 `scoreAllocation` 범위를 넘지 않도록 서버 clamp를 고정한다.
3. 추천 함수와 채점 함수를 퀴즈 서비스 함수 단위로 유지한다.
4. 이후 필요할 때 일일 리포트 오케스트레이터, API 엔드포인트, SQS/Spring Boot 콜백에 연결한다.
