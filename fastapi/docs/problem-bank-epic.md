---
title: FastAPI 문제 은행 생성 Epic
status: draft
created: 2026-06-11
owner: FastAPI AI 서버
output_scope: fastapi 하위 산출물
source_root: /Users/tlsdla1235/study/md파일들
primary_output: fastapi/data/rag/catalog/problems.jsonl
related_docs:
  - fastapi/docs/rag-implementation-plan.md
  - fastapi/docs/report-quiz-scoring-plan.md
---

# FastAPI 문제 은행 생성 Epic

## 1. 목적

이 문서는 FastAPI AI 서버가 `/Users/tlsdla1235/study/md파일들`의 Markdown/CSV 자료를 읽어, RAG/pgvector 검색에 넣기 전 단계의 정적 문제 은행을 생성하기 위한 epic과 하위 story를 정의한다.

이번 epic은 **문제 은행 생성만** 다룬다. 임베딩 생성, pgvector 저장, 검색 API, 추천 API, 채점 API 구현은 포함하지 않는다.

## 2. 핵심 계약

- FastAPI는 Spring Boot에 `problemId`만 반환하지 않는다.
- 추천 퀴즈는 `problemId`, `question`, `modelAnswer`, `scoreAllocation`을 포함한 문제 스냅샷 전체로 반환될 수 있어야 한다.
- `problemId`는 Spring Boot 조회키가 아니라 FastAPI 내부의 추적, 출처, eval, 로그 상관관계용 ID다.
- MVP에서는 런타임 신규 문제 생성을 하지 않는다.
- 문제 은행에 가까운 문제가 없으면 이후 추천 단계에서 `recommendedQuizzes: []`를 반환한다.
- 문제 은행은 우선 FastAPI 내부 파일로 관리한다.
- 권장 저장 위치는 `fastapi/data/rag/catalog/problems.jsonl`이다.

## 3. 문제 은행 레코드 목표 스키마

```json
{
  "problemId": 114657103,
  "sourceKey": "sha256:...",
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
  },
  "sourcePath": "computer-science-interview-KR/02-backend-engineering/database/qna-database.md",
  "headingPath": ["..."],
  "rubric": {
    "mustMention": ["N+1", "Lazy Loading", "fetch join"],
    "optionalMention": ["BatchSize", "EntityGraph", "페이징 한계"],
    "mustNotConfuse": ["SQL Injection"]
  },
  "contentHash": "sha256:..."
}
```

## 4. 공통 정책

### 4.1 입력 범위

포함 대상:

- Markdown 파일
- CSV 파일
- 구조화된 QnA 문서
- Notion export 또는 스터디 문제 형식 문서

기본 제외 대상:

- 경로 또는 파일명에 `(대외비)`가 포함된 파일
- `.DS_Store`
- 빈 파일
- 바이너리 파일
- 이미지와 첨부파일
- `node_modules` 하위 파일

### 4.2 추천 가능 문제 기준

문제 은행의 추천 가능 레코드는 다음 값을 모두 가져야 한다.

- `question`
- `modelAnswer`
- `scoreAllocation`
- `problemId`
- `sourceKey`
- `sourcePath`
- `contentHash`

모범답안이 없는 문제는 `problems.jsonl`의 추천 가능 문제로 저장하지 않는다. 대신 skipped/rejected 리포트에 남길 수 있다.

### 4.3 점수 정책

- 점수는 총합 10점이 아니다.
- 각 필드는 독립적으로 `0..10` 범위를 가진다.
- 전체 이론상 최대는 50점이다.
- `scoreAllocation`은 문제를 완벽히 맞혔을 때 받을 수 있는 필드별 최대점이다.

난이도별 관련 필드 최대점:

| 난이도 | 관련 필드별 최대점 |
| --- | ---: |
| `basic` | 5 |
| `intermediate` | 7 |
| `advanced` | 10 |

`scoreAllocation`은 항상 5개 필드(`db`, `algorithm`, `cs`, `network`, `framework`)를 모두 포함한다. 관련 없는 필드는 0으로 둔다.

## 5. Epic

### Epic PB-1. FastAPI 정적 문제 은행 생성

**목표**

FastAPI 내부에서 사용할 정적 문제 은행 생성 파이프라인을 설계하고 구현 가능한 단위로 나눈다. 이 파이프라인은 로컬 Markdown/CSV 자료에서 추천 가능한 문제를 추출하고, 필드/난이도/배점/rubric/출처 정보를 붙여 `fastapi/data/rag/catalog/problems.jsonl`로 기록한다.

**성공 기준**

- 지정 소스 루트에서 문제 후보를 발견할 수 있다.
- QnA 문서와 Notion/스터디 문제 문서에서 `question`과 `modelAnswer`를 추출할 수 있다.
- 모범답안 없는 항목은 추천 가능한 문제 은행에서 제외된다.
- 각 문제는 안정적인 `problemId`, `sourceKey`, `contentHash`를 가진다.
- 각 문제는 5개 능력치 기준의 `scoreAllocation`을 가진다.
- 생성 결과와 skipped/rejected 항목을 검증하고 리포트로 남긴다.
- 모든 산출물은 `fastapi` 하위에만 생성된다.

**Epic 제외 범위**

- RAG 임베딩 생성
- pgvector 또는 DB 저장
- 벡터/키워드 검색 엔진 구현
- FastAPI 추천 API 엔드포인트 구현
- FastAPI 채점 API 엔드포인트 구현
- Spring Boot 연동 구현
- 런타임 신규 문제 생성

## 6. Stories

### Story PB-1.1. 문제 은행 스키마, 점수 정책, ID 계약 정의

**목표**

문제 은행 생성의 기준이 되는 레코드 스키마, 점수 정책, ID/해시 정책을 먼저 고정한다.

**구현 범위**

- `ProblemRecord` 스키마 정의
- `ScoreField` 5종 고정
- 난이도 enum 정의
- `scoreAllocation` 필드별 독립 최대점 정책 정의
- `problemId`, `sourceKey`, `contentHash` 생성 정책 문서화
- 추천 가능 문제와 skipped/rejected 항목의 최소 필드 정의
- 최소 fixture와 schema validation 테스트 기준 정의

**주요 파일 경로**

- `fastapi/app/rag/schemas.py`
- `fastapi/app/scoring/policy.py`
- `fastapi/docs/problem-bank-epic.md`
- `fastapi/tests/rag/test_problem_schema.py`
- `fastapi/tests/fixtures/rag/schema/`

**Acceptance Criteria**

- `scoreAllocation`은 항상 `db`, `algorithm`, `cs`, `network`, `framework`를 모두 포함한다.
- 각 점수 필드는 `0..10` 범위만 허용한다.
- `10,10,10,10,10` 벡터가 유효하다.
- 난이도별 관련 필드 최대점은 `basic=5`, `intermediate=7`, `advanced=10`이다.
- `problemId`가 Spring Boot 조회키가 아니라 추적용 ID임이 문서와 타입 주석에 드러난다.

**테스트 기준**

- 유효한 문제 레코드는 schema validation을 통과한다.
- 누락 필드, 잘못된 필드명, 음수 점수, 10 초과 점수는 실패한다.
- 총합 10점 cap이 적용되지 않음을 테스트한다.
- 난이도별 배점 생성 케이스를 검증한다.

**제외 범위**

- 실제 파일 discovery
- Markdown/CSV 파싱
- JSONL writer 구현
- 추천 로직 구현

### Story PB-1.2. 소스 파일 discovery, 제외 규칙, 입력 fixture 구성

**목표**

`/Users/tlsdla1235/study/md파일들`에서 문제 은행 생성 후보 파일을 찾고, 제외 규칙과 테스트 fixture를 함께 정리한다.

**구현 범위**

- 소스 루트 설정
- 포함 확장자 정의
- `(대외비)`, `.DS_Store`, 빈 파일, 바이너리, 이미지, 첨부파일, `node_modules` 제외
- 발견 파일 목록과 제외 파일 목록 생성
- 상대 `sourcePath` 계산
- discovery 통계 생성
- QnA, Notion/스터디 문제, CSV, 제외 파일 fixture 구성

**주요 파일 경로**

- `fastapi/app/rag/config.py`
- `fastapi/app/rag/loaders/source_discovery.py`
- `fastapi/data/rag/manifests/problem-bank-discovery.json`
- `fastapi/data/rag/reports/problem-bank-skipped.jsonl`
- `fastapi/tests/rag/test_source_discovery.py`
- `fastapi/tests/fixtures/rag/discovery/`
- `fastapi/tests/fixtures/rag/qna/`
- `fastapi/tests/fixtures/rag/notion_quiz/`

**Acceptance Criteria**

- Markdown/CSV 파일만 후보로 포함된다.
- `(대외비)`가 포함된 경로는 기본 제외된다.
- 빈 파일과 바이너리 파일은 제외되고 이유가 기록된다.
- 발견 파일과 제외 파일 수가 manifest에 기록된다.
- `sourcePath`는 절대 경로가 아니라 소스 루트 기준 상대 경로로 저장된다.
- fixture에는 실제 대외비 원문을 포함하지 않는다.

**테스트 기준**

- fixture 디렉터리에서 포함/제외 파일이 기대대로 분류된다.
- `(대외비)` 파일 제외가 테스트된다.
- `.DS_Store`, 이미지, 빈 Markdown 제외가 테스트된다.
- discovery 결과가 실행 순서와 무관하게 안정적으로 정렬된다.
- QnA, Notion/스터디 문제, CSV fixture가 parser 테스트에서 재사용 가능하다.

**제외 범위**

- 파일 내용 파싱
- 문제 레코드 생성
- field/difficulty 산정
- JSONL 저장

### Story PB-1.3. Markdown/CSV 문제 후보 파서

**목표**

Markdown/CSV 자료에서 추천 가능한 문제 후보를 추출한다. 구조화 QnA와 Notion export/스터디 문제 형식을 이 story 안에서 함께 처리한다.

**구현 범위**

- `## Q1. ...` 형태의 질문 heading 감지
- `<details>` 내부 답변 추출
- `[기본] Q1. ...`, `[심화] Q2. ...` 같은 스터디 문제 패턴 감지
- 한국어 난이도 라벨을 `basic`, `intermediate`, `advanced`로 매핑
- 문제와 답안 블록 연결
- CSV는 문제 추출보다 챕터/자료 링크 메타데이터로 우선 처리
- heading path 계산
- 질문 본문 정규화
- 답변 본문 정규화
- 모범답안 없는 항목 skipped 처리
- source type 메타데이터 부여

**주요 파일 경로**

- `fastapi/app/rag/parsers/qna_parser.py`
- `fastapi/app/rag/parsers/notion_quiz_parser.py`
- `fastapi/app/rag/parsers/csv_metadata_parser.py`
- `fastapi/app/rag/parsers/markdown_blocks.py`
- `fastapi/tests/rag/test_qna_parser.py`
- `fastapi/tests/rag/test_notion_quiz_parser.py`
- `fastapi/tests/rag/test_csv_metadata_parser.py`
- `fastapi/tests/fixtures/rag/qna/`
- `fastapi/tests/fixtures/rag/notion_quiz/`

**Acceptance Criteria**

- QnA 파일에서 질문 1개가 문제 후보 1개로 추출된다.
- `<details>` 내부 내용이 `modelAnswer`로 추출된다.
- `[기본]` 문제는 기본적으로 `basic`으로 매핑된다.
- `[심화]` 문제는 기본적으로 `advanced` 또는 정책상 지정된 난이도로 매핑된다.
- 답안 블록이 여러 문단이어도 하나의 `modelAnswer`로 보존된다.
- 질문별 `headingPath`가 유지된다.
- 모범답안이 없는 질문은 추천 가능 문제 후보에서 제외되고 skipped 이유가 남는다.
- 파서 결과는 원문 파일 순서에 대해 결정적이다.

**테스트 기준**

- REST API QnA 예시에서 질문과 답변이 추출된다.
- 여러 질문이 있는 파일에서 각 질문이 별도 후보로 분리된다.
- `<summary>` 같은 표시용 텍스트가 답변에 섞이지 않는지 검증한다.
- 기본/심화 난이도 fixture에서 난이도와 질문이 추출된다.
- 답변 없는 QnA가 skipped로 기록되는지 검증한다.
- CSV fixture에서 챕터/자료 링크 메타데이터가 읽히고, 답안 없는 행이 문제로 저장되지 않는지 검증한다.

**제외 범위**

- field/difficulty 추론
- rubric 생성
- 최종 `problemId` 부여
- JSONL writer 구현

### Story PB-1.4. Field, difficulty, scoreAllocation, rubric 보강

**목표**

문제 후보에 추천과 채점 기준으로 쓸 필드, 난이도, 배점, rubric 정보를 보강한다.

**구현 범위**

- 자료 주제와 5개 필드 매핑 규칙 구현
- `primaryField` 선택
- `fields` 중복 제거 및 안정 정렬
- 파서가 제공한 난이도 우선 사용
- 난이도 미지정 시 경로/제목/문제 패턴 기반 fallback 적용
- 난이도별 관련 필드 최대점으로 `scoreAllocation` 생성
- 모범답안에서 핵심 키워드 후보 추출
- `mustMention`, `optionalMention`, `mustNotConfuse` 구조 생성
- 원문에 명시된 채점 기준이 있으면 우선 반영
- rubric 생성 실패 시 warning 또는 skipped 처리 기준 정의

**주요 파일 경로**

- `fastapi/app/rag/field_mapper.py`
- `fastapi/app/rag/rubric_builder.py`
- `fastapi/app/scoring/policy.py`
- `fastapi/app/scoring/quiz_allocator.py`
- `fastapi/tests/rag/test_field_mapper.py`
- `fastapi/tests/rag/test_rubric_builder.py`
- `fastapi/tests/scoring/test_quiz_allocator.py`
- `fastapi/tests/fixtures/rag/rubric/`

**Acceptance Criteria**

- Database, SQL, JPA, transaction, ORM 주제는 `db`를 포함한다.
- JPA, Spring, Java, Docker, Cloud, DevOps 등 프레임워크/도구 주제는 `framework`를 포함할 수 있다.
- REST, HTTP, API design, security, network 주제는 `network`를 포함한다.
- Algorithm/Data Structure 주제는 `algorithm`을 포함한다.
- OS, Computer Architecture, Software Engineering, Design Pattern, Version Control 주제는 `cs`를 포함한다.
- 관련 없는 필드는 `scoreAllocation`에서 0이다.
- 복합 주제는 관련 필드마다 독립 최대점을 받는다.
- 모든 추천 가능 문제는 `rubric` 객체를 가진다.
- 최소한 `mustMention` 또는 `optionalMention` 중 하나는 비어 있지 않다.

**테스트 기준**

- `JPA N+1` 문제는 `db`, `framework` 배점을 받는다.
- `OAuth + JWT` 문제는 `network`, `framework` 배점을 받을 수 있다.
- `REST API` 문제는 `network` 배점을 받는다.
- advanced 복합 문제는 관련 필드마다 10점을 받는다.
- 모든 테스트에서 총합 10점 cap이 적용되지 않는다.
- N+1 예시에서 `N+1`, `Lazy Loading`, `fetch join` 같은 핵심어가 추출된다.
- 답변이 너무 짧아 rubric을 만들 수 없는 경우 warning/skipped가 기록된다.

**제외 범위**

- LLM 기반 분류
- LLM을 이용한 rubric 생성
- 검색 유사도 기반 추천
- 사용자 리포트 점수 산정
- 퀴즈 답안 채점

### Story PB-1.5. problems.jsonl writer, validation/reporting, CLI

**목표**

보강된 문제 레코드를 `fastapi/data/rag/catalog/problems.jsonl`로 안정적으로 저장하고, 검증/리포트/CLI 실행까지 묶어 문제 은행 생성 파이프라인을 완성한다.

**구현 범위**

- `sourceKey = sha256(normalized_source_path + heading_path + question_text)` 정책 구현
- `contentHash` 생성
- `sourceKey` 기반 `problemId` 매핑
- JSONL writer 구현
- 결정적 정렬 정책 적용
- 기존 ID manifest가 있으면 동일 `sourceKey`에 동일 `problemId` 재사용
- `problems.jsonl` schema validation
- skipped/rejected/summary 리포트 생성
- CLI entrypoint, `--source`, `--out`, dry-run 옵션 구현
- 생성 산출물이 `fastapi` 하위에만 쓰이도록 검증

**주요 파일 경로**

- `fastapi/app/rag/catalog/problem_bank_writer.py`
- `fastapi/app/rag/catalog/id_registry.py`
- `fastapi/app/rag/catalog/problem_bank_validator.py`
- `fastapi/app/rag/build_problem_bank.py`
- `fastapi/scripts/build_problem_bank.py`
- `fastapi/data/rag/catalog/problems.jsonl`
- `fastapi/data/rag/manifests/problem-bank-id-registry.json`
- `fastapi/data/rag/reports/problem-bank-summary.md`
- `fastapi/data/rag/reports/problem-bank-skipped.jsonl`
- `fastapi/data/rag/reports/problem-bank-rejected.jsonl`
- `fastapi/tests/rag/test_problem_bank_writer.py`
- `fastapi/tests/rag/test_problem_bank_validator.py`
- `fastapi/tests/rag/test_build_problem_bank_cli.py`
- `fastapi/tests/rag/test_problem_bank_pipeline.py`

**Acceptance Criteria**

- 같은 입력을 두 번 실행하면 같은 `problemId`와 같은 출력 순서를 얻는다.
- 질문이 바뀌면 새 `sourceKey`와 새 문제로 처리된다.
- 모범답안만 바뀌면 같은 `sourceKey`를 유지하되 `contentHash`는 바뀐다.
- JSONL 각 줄은 독립적으로 valid JSON이다.
- 최종 산출물은 `fastapi/data/rag/catalog/problems.jsonl`에 생성된다.
- skipped와 rejected는 이유, `sourcePath`, 가능하면 `headingPath`를 포함한다.
- summary에는 발견 파일 수, 제외 파일 수, 추출 후보 수, 저장 문제 수, skipped 수, rejected 수가 포함된다.
- `python -m app.rag.build_problem_bank --source /Users/tlsdla1235/study/md파일들 --out fastapi/data/rag` 형태로 실행할 수 있다.
- `--out`이 `fastapi` 밖을 가리키면 실패한다.

**테스트 기준**

- 동일 fixture 반복 실행의 출력 diff가 없다.
- 질문 변경 fixture에서 새 ID가 생성된다.
- 답변 변경 fixture에서 ID는 유지되고 content hash만 바뀐다.
- 필수 필드 누락, 중복 ID/sourceKey, malformed JSONL을 validation 실패로 잡는다.
- fixture source로 CLI smoke test를 실행한다.
- dry-run이 실제 산출물을 만들지 않는지 검증한다.
- 전체 pipeline fixture test에서 generated `problems.jsonl`, skipped/rejected 리포트가 expected snapshot과 일치한다.

**제외 범위**

- 검색 인덱스 생성
- pgvector upsert
- Spring Boot DB 저장
- API 응답 생성
- cron/job scheduler
- FastAPI admin endpoint
- Docker image 변경
- 배포 파이프라인 연결

## 7. 권장 구현 순서

1. PB-1.1 문제 은행 스키마, 점수 정책, ID 계약 정의
2. PB-1.2 소스 파일 discovery, 제외 규칙, 입력 fixture 구성
3. PB-1.3 Markdown/CSV 문제 후보 파서
4. PB-1.4 Field, difficulty, scoreAllocation, rubric 보강
5. PB-1.5 problems.jsonl writer, validation/reporting, CLI

## 8. Definition of Done

- `fastapi/data/rag/catalog/problems.jsonl` 생성 기준이 story로 모두 정의되어 있다.
- 추천 가능한 문제와 추천 불가/skipped 항목의 기준이 분리되어 있다.
- 점수 정책이 필드별 독립 최대점으로 명시되어 있다.
- `problemId`의 의미가 추적/출처/eval용 ID로 명시되어 있다.
- RAG 임베딩, pgvector 저장, API 엔드포인트 구현이 제외 범위로 명시되어 있다.
- 모든 story가 목표, 구현 범위, 주요 파일 경로, acceptance criteria, 테스트 기준, 제외 범위를 포함한다.
