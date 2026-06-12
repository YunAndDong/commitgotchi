---
title: Commitgotchi FastAPI RAG 구축 계획
status: draft
created: 2026-06-11
owner: FastAPI AI 서버
output_scope: fastapi 하위 산출물
architecture_reference: _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4
source_root: /Users/tlsdla1235/study/md파일들
---

# Commitgotchi FastAPI RAG 구축 계획

## 1. 목적

내 역할은 FastAPI AI 서버 인스턴스다. 이 계획은 로컬 학습 자료인 `/Users/tlsdla1235/study/md파일들`을 기반으로 **문제 뱅크와 검색 계층**을 구축해, 파트너가 작성한 아키텍처 4번의 핵심 계약을 깨지 않고 다음 기능을 지원하는 것을 목표로 한다.

- 흐름 A: 일일 리포트 생성 시 다음 학습 추천과 추천 퀴즈 생성
- 흐름 B: 추천 퀴즈 답안 채점 시 문제 근거 보강
- Spring Boot DB 직접 접근 없이 FastAPI 내부에서 지식 검색과 문제 추천 수행
- 결과는 아키텍처 4번 계약의 `recommendedQuizzes`, `problemId`, `question`, `modelAnswer`, `scoreAllocation` 형태로 반환
- `problemId`만 반환하지 않고, Spring Boot가 저장할 수 있는 **문제 스냅샷 전체**를 반환

## 2. BMAD 컨텍스트 정리

### 2.1 입력 아키텍처

아키텍처 4번은 두 서버 간 계약을 3개 흐름, 4개 계약으로 고정한다.

| 흐름 | FastAPI에서 RAG가 관여하는 지점 | 계약상 출력 |
| --- | --- | --- |
| A. 일일 리포트 | 학습 리포트 분석 후 다음 학습 주제와 추천 퀴즈 선택 | `nextRecommendation`, `recommendedQuizzes[]` |
| B. 퀴즈 채점 | Spring Boot가 넘긴 `question`, `modelAnswer`, `scoreAllocation` 기반 채점. 필요 시 `problemId`로 근거 검색 | `scoreDelta`, `feedback`, `emotion`, `statusMessage` |
| C. 캐릭터 이미지 | RAG 범위 밖 | 없음 |

핵심 제약:

- FastAPI는 Spring Boot의 System of Record DB에 직접 접근하지 않는다.
- FastAPI가 생성한 추천 퀴즈는 Spring Boot 콜백에 포함된다.
- `problemId`는 FastAPI 내부 문제 뱅크의 추적용 ID다. Spring Boot가 이 ID로 다시 조회해야 한다는 뜻이 아니다.
- MVP에서는 신규 문제를 런타임에 생성하지 않는다. 가까운 문제가 없으면 `recommendedQuizzes: []`를 반환한다.
- 점수는 `scoreAllocation[field]` 범위 안에서만 채점된다.

### 2.2 로컬 자료 현황

확인한 입력 루트:

```text
/Users/tlsdla1235/study/md파일들
```

자료 규모:

| 구분 | 수량 | 성격 |
| --- | ---: | --- |
| Markdown 전체 | 163개 | 개념 정리, QnA, 주차별 스터디 자료, 문제 파일 |
| CSV 전체 | 8개 | 스터디 커리큘럼과 학습자료 링크 메타데이터 |
| `computer-science-interview-KR` Markdown | 92개 | 구조화된 CS 면접 QnA와 개념 자료 |
| `quiz` Markdown | 71개 | Notion export 형태의 주차별 학습 자료와 서술형 문제 |

주의할 자료:

- 파일명에 `(대외비)`가 포함된 자료는 기본 인덱싱 대상에서 제외한다.
- `.DS_Store`, 빈 Markdown, 참석자 명단만 있는 CSV는 지식 청크에서 제외하거나 메타데이터로만 사용한다.

## 3. 권장 아키텍처 결정

### RAG-AD-1. RAG는 FastAPI 내부 책임으로 둔다

RAG는 Spring Boot의 도메인 DB와 분리한다. Spring Boot는 추천 퀴즈 결과만 저장하고, FastAPI는 학습자료 인덱싱, 문제 뱅크 관리, 검색, 추천, 채점 근거 보강을 담당한다.

실행 위치:

```text
fastapi/app/rag/
fastapi/data/rag/
fastapi/docs/
```

### RAG-AD-2. MVP 저장소는 FastAPI 전용 파일 기반 문제 뱅크로 시작한다

MVP에서는 모든 산출물을 `fastapi/data/rag` 아래에 둔다. 비효율적이어도 우선 파일 기반으로 두고, FastAPI가 런타임에 파일을 읽어 추천 후보를 찾는다.

권장 초기 구성:

```text
fastapi/data/rag/
├── catalog/
│   ├── documents.jsonl
│   ├── chunks.jsonl
│   └── problems.jsonl        # 문제 뱅크: question/modelAnswer/rubric/scoreAllocation 포함
├── vector/
│   └── local_index/
└── manifests/
    └── ingest-manifest.json
```

이 선택의 장점:

- Docker PostgreSQL 이미지나 Spring Boot DB를 건드리지 않고 시작 가능
- 산출물이 전부 FastAPI 폴더 하위에 남음
- 인덱스 재생성이 쉬움
- 포트폴리오 MVP에서 운영 부담이 작음

확장 선택지:

- 검색 품질과 운영성이 더 필요해지면 FastAPI 전용 DB `commitgotchi_ai`에 `pgvector` 기반 테이블을 둘 수 있다.
- 이 경우에도 Spring Boot DB에는 직접 접근하지 않고, 계약상 콜백 payload만 주고받는다.

### RAG-AD-3. 문제 뱅크와 개념 청크를 분리한다

자료는 두 종류로 나눠 저장한다.

| 종류 | 저장 대상 | 사용처 |
| --- | --- | --- |
| 개념 청크 | 문단, QnA 답변, 강의/정리 본문 | 리포트 분석 grounding, 현재 공부 보강, 다음 공부 추천, 채점 근거 보강 |
| 문제 뱅크 | 질문, 모범답안, rubric, 난이도, 분야, 출처, `scoreAllocation` | `recommendedQuizzes[]` 스냅샷 반환과 채점 기준 고정 |

문제 뱅크에는 추천 가능한 완성 문제만 넣는다. 즉 `question`, `modelAnswer`, `scoreAllocation`이 없는 항목은 `recommendedQuizzes` 후보에서 제외한다. 런타임 신규 생성 문제는 MVP 범위에서 제외한다.

### RAG-AD-4. `problemId`는 조회 키가 아니라 추적 키다

아키텍처 4번의 `problemId`는 숫자이므로 FastAPI 문제 뱅크에서 정수 ID를 생성한다. 다만 Spring Boot에는 `problemId`만 주지 않고 문제 전체 스냅샷을 함께 보낸다. `problemId`는 provenance, 중복 방지, 평가 추적, 로그 상관관계를 위한 보조키다.

```text
sourceKey = sha256(normalized_source_path + heading_path + question_text)
problemId = sourceKey에 매핑된 내부 정수 ID
```

재인덱싱 정책:

- 기존 `sourceKey`가 있으면 같은 `problemId`를 재사용한다.
- 질문이 바뀌면 새 문제로 본다.
- 본문이나 모범답안만 바뀌면 같은 `problemId`로 갱신한다.

### RAG-AD-5. 점수 분야는 5개 능력치에 직접 매핑한다

RAG 메타데이터의 `field`는 아키텍처의 능력치 키와 동일하게 둔다.

| 자료 주제 | 기본 `field` |
| --- | --- |
| Database, SQL, JPA, transaction, ORM | `db` |
| Algorithm, Data Structure, complexity | `algorithm` |
| OS, Computer Architecture, Software Engineering, Design Pattern, Version Control | `cs` |
| Network, HTTP, API design, Security, Linux networking | `network` |
| Spring, Java, Frontend, React, Next.js, Docker, Cloud, DevOps, AI Engineering | `framework` |

점수는 총합 10점이 아니라 **필드별 독립 최대점**이다. 각 필드는 `0..10` 범위에서 별도로 배점되고, 다섯 필드가 모두 충분히 다뤄진 리포트나 문제라면 이론상 `10, 10, 10, 10, 10`으로 총 50점까지 획득 가능하다.

프로토타입의 문제 뱅크 배점은 난이도에 따라 관련 필드마다 같은 최대점을 부여한다.

| 난이도 | 관련 필드별 최대점 |
| --- | ---: |
| `basic` | 5 |
| `intermediate` | 7 |
| `advanced` | 10 |

예를 들어 advanced 난이도의 "JPA N+1 문제와 해결"이 `db`, `framework`에 모두 걸치면 `db: 10`, `framework: 10`이 될 수 있다. 이는 두 필드 합계가 10이라는 뜻이 아니라, 각 필드가 독립적으로 최대 10이라는 뜻이다.

## 4. 목표 모듈 구조

```text
fastapi/
├── app/
│   ├── api/
│   │   ├── grade.py
│   │   └── rag_admin.py
│   ├── pipeline/
│   │   ├── report.py
│   │   ├── recommendation.py
│   │   └── grading.py
│   ├── rag/
│   │   ├── __init__.py
│   │   ├── config.py
│   │   ├── schemas.py
│   │   ├── loaders/
│   │   │   ├── markdown_loader.py
│   │   │   └── csv_loader.py
│   │   ├── parsers/
│   │   │   ├── qna_parser.py
│   │   │   ├── notion_quiz_parser.py
│   │   │   └── generic_markdown_parser.py
│   │   ├── chunking.py
│   │   ├── embeddings.py
│   │   ├── vector_store.py
│   │   ├── catalog.py
│   │   ├── retriever.py
│   │   ├── quiz_recommender.py
│   │   └── ingest.py
│   ├── llm/
│   │   ├── client.py
│   │   └── prompts/
│   │       ├── report_recommendation.md
│   │       ├── quiz_generation.md
│   │       └── grading.md
│   └── fallback/
│       └── rag_fallback.py
├── data/
│   └── rag/
├── docs/
│   └── rag-implementation-plan.md
└── tests/
    ├── rag/
    └── fixtures/
        └── rag/
```

`rag_admin.py`는 개발 중에만 사용하는 내부 API로 둔다. 예를 들어 `/api/internal/rag/reindex`, `/api/internal/rag/search` 같은 엔드포인트를 만들 수 있지만, 배포 시에는 내부 시크릿이나 로컬 전용으로 묶는다.

## 5. 인덱싱 계획

### 5.1 소스 발견

환경변수:

```text
RAG_SOURCE_ROOT=/Users/tlsdla1235/study/md파일들
RAG_DATA_DIR=fastapi/data/rag
```

포함:

- `*.md`
- `*.csv`

제외:

- `.DS_Store`
- 빈 Markdown
- `(대외비)` 포함 경로
- `node_modules`
- 이미지, 첨부파일, 바이너리

### 5.2 파싱 전략

| 자료 패턴 | 예시 | 파싱 방식 |
| --- | --- | --- |
| 구조화 QnA | `## Q1. RESTful API의 설계 원칙은?` + `<details>` | 질문 1개를 문제 1개로 추출, details 내부를 모범답안과 개념 청크로 저장 |
| Notion 문제 파일 | `[기본] Q1. 관계형 데이터베이스의 주요 구성요소...` | 질문과 난이도 추출, 모범답안이 있는 항목만 문제 뱅크에 저장. 답안 없는 항목은 청크/비추천 자료로만 사용 |
| 주차별 학습 자료 | `1회차 Java 기초`, `Database 심화` | 제목/소제목 기준 개념 청크 생성 |
| 커리큘럼 CSV | `Chapter`, `Status`, `학습자료` | 챕터 메타데이터로만 활용 |
| 일반 README | 체크리스트, 목차 | 주제 태그와 상위 카테고리 추출 |

### 5.3 청킹 전략

기본 원칙:

- QnA는 질문과 답변 한 쌍을 하나의 의미 단위로 유지한다.
- 긴 답변은 `핵심 답변`, `비교표`, `예시`, `꼬리 질문` 단위로 나눈다.
- 주차별 본문은 Markdown heading 경계를 우선한다.
- 청크에는 항상 출처 경로와 heading path를 넣는다.

청크 메타데이터 예시:

```json
{
  "chunkId": "chunk_000123",
  "sourcePath": "computer-science-interview-KR/08-api-design/qna-api-design.md",
  "headingPath": ["API 설계 면접 질문 & 답변", "Q1. RESTful API의 설계 원칙은?"],
  "text": "...",
  "field": "network",
  "topics": ["REST", "HTTP", "API design"],
  "difficulty": "basic",
  "sourceType": "qna"
}
```

### 5.4 문제 뱅크 스키마

```json
{
  "problemId": 1,
  "sourceKey": "sha256:...",
  "question": "JPA N+1 문제란 무엇인가?",
  "modelAnswer": "연관 엔티티를 지연 로딩할 때 ...",
  "primaryField": "db",
  "fields": ["db", "framework"],
  "scoreAllocation": {
    "db": 10,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 10
  },
  "rubric": {
    "mustMention": ["N+1", "Lazy Loading", "fetch join"],
    "optionalMention": ["BatchSize", "EntityGraph", "페이징 한계"],
    "mustNotConfuse": ["SQL Injection", "트랜잭션 격리 수준"]
  },
  "difficulty": "advanced",
  "topics": ["JPA", "N+1", "fetch join"],
  "sourcePath": "...",
  "headingPath": ["..."],
  "contentHash": "sha256:..."
}
```

### 5.5 임베딩 대상

문제 검색용 텍스트:

```text
질문: {question}
모범답안: {modelAnswer}
주제: {topics}
주 분야: {primaryField}
관련 분야: {fields}
난이도: {difficulty}
```

개념 검색용 텍스트:

```text
제목 경로: {headingPath}
본문: {chunkText}
태그: {topics}
```

## 6. 흐름 A: 일일 리포트 추천 파이프라인

입력은 아키텍처 4.1의 SQS 메시지다.

```text
dailyReport.content
userMetadata.weeklyStudyStreak
userMetadata.reportDirection.scoreDeltaHint
characterMetadata.currentStats
```

처리 단계:

1. 학습 리포트에서 핵심 주제, 부족한 개념, 다음 학습 후보를 추출한다.
2. `scoreDeltaHint`와 리포트 주제를 5개 능력치 필드로 매핑한다.
3. RAG 검색 쿼리를 만든다.
4. 문제 뱅크(`problems.jsonl`)에서 기존 완성 문제를 검색한다.
5. 가까운 문제가 없으면 신규 문제를 만들지 않고 `recommendedQuizzes: []`를 반환한다.
6. 추천 퀴즈는 중복 주제를 피하고 1-3개로 제한한다.
7. 문제 뱅크에 저장된 `question`, `modelAnswer`, `scoreAllocation` 스냅샷을 그대로 사용한다.
8. 아키텍처 4.2의 `recommendedQuizzes[]`에 맞춰 Spring Boot 콜백에 포함한다.

추천 선택 기준:

- 전날 학습 리포트와 의미적으로 가까운 문제
- `scoreDeltaHint`가 높은 분야를 우선
- 사용자의 현재 능력치가 낮은 분야를 보정
- 같은 주제의 중복 문제는 제외
- 난이도는 `basic -> intermediate -> advanced` 순으로 완만하게 상승

콜백 예시:

```json
{
  "recommendedQuizzes": [
    {
      "problemId": 12,
      "question": "JPA N+1 문제란 무엇이며 어떻게 해결할 수 있나요?",
      "modelAnswer": "N+1 문제는 ... fetch join, batch size 등으로 완화할 수 있다.",
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

## 7. 흐름 B: 퀴즈 채점 파이프라인

아키텍처 4.3에 따르면 Spring Boot가 채점 요청에 이미 `question`, `modelAnswer`, `scoreAllocation`을 포함한다. 따라서 채점의 1차 근거는 요청 payload다.

RAG는 보조 근거로만 쓴다.

처리 단계:

1. `submissionId` 기준으로 요청을 수락한다.
2. `problemId`가 있으면 문제 뱅크와 관련 청크를 조회한다.
3. `problemId`가 없거나 조회 실패하면 `question + modelAnswer`로 유사 청크를 검색한다.
4. LLM 채점 프롬프트에 `question`, `modelAnswer`, `userAnswer`, `scoreAllocation`, 검색 근거를 넣는다.
5. LLM 출력은 JSON으로 제한한다.
6. `scoreDelta[field]`는 반드시 `0..scoreAllocation[field]` 범위로 clamp한다.
7. 성공 시 `GRADED`, 실패 시 `UNGRADED` 웹훅을 보낸다.

채점 출력 스키마:

```json
{
  "scoreDelta": {
    "db": 5,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 2
  },
  "feedback": "핵심 원인은 잘 설명했지만 fetch join과 batch size의 차이가 빠졌습니다.",
  "emotion": "JOY",
  "statusMessage": "좋아요, 핵심은 잡았어요. 해결 전략만 조금 더 붙이면 완성입니다."
}
```

## 8. Fallback 정책

| 실패 지점 | 대체 처리 | 아키텍처 계약 영향 |
| --- | --- | --- |
| 문제 뱅크 파일 없음 | `recommendedQuizzes: []`, 리포트 분석은 계속 진행 | 흐름 A 유지 |
| 기존 문제 검색 실패 | `recommendedQuizzes: []` | 흐름 A 유지 |
| 개념 청크도 없음 | 다음 공부/보강 근거 생략, `recommendedQuizzes: []` | 흐름 A 유지 |
| 임베딩/벡터 검색 실패 | 키워드 검색으로 대체 | 문제 추천 품질 저하, 흐름 유지 |
| 채점 중 RAG 조회 실패 | 요청 payload의 `modelAnswer`만으로 채점 | 흐름 B 유지 |
| LLM 채점 실패 | `UNGRADED`, `scoreDelta` 전부 0 | 아키텍처 4.3 실패 웹훅 |

원칙:

- RAG 실패가 리포트/채점 전체 실패로 번지지 않게 한다.
- 추천 품질이 낮아질 수는 있지만 사용자 흐름은 유지한다.
- 점수는 실패 시 절대 임의로 올리지 않는다.
- 문제 생성 실패라는 개념을 MVP에 두지 않는다. 문제는 파일 기반 문제 뱅크에 있는 것만 추천한다.

## 9. 구현 단계

### 1단계: 문서 계획과 입력 샘플 고정

- 이 문서를 기준 계획으로 둔다.
- 대표 샘플 파일 5-10개를 `fastapi/tests/fixtures/rag`에 복사하거나 최소 샘플로 재작성한다.
- `(대외비)` 자료 제외 규칙을 테스트에 포함한다.

완료 기준:

- RAG 소스 루트와 제외 규칙이 설정으로 분리됨
- QnA 파일, Notion 문제 파일, 일반 Markdown 샘플이 fixture로 존재

### 2단계: 파서와 카탈로그 생성

- Markdown loader 구현
- QnA parser 구현
- Notion quiz parser 구현
- 일반 Markdown heading chunker 구현
- `documents.jsonl`, `chunks.jsonl`, `problems.jsonl` 생성
- 추천 가능한 문제는 `question`, `modelAnswer`, `rubric`, `scoreAllocation`을 모두 갖추도록 필터링

완료 기준:

- `computer-science-interview-KR/08-api-design/qna-api-design.md`에서 Q1 REST 문제가 추출됨
- `quiz/.../Database 기초/...문제.md`에서 `[기본]`, `[심화]` 난이도가 추출됨
- `problemId`, `sourceKey`, `field`, `rubric`, `scoreAllocation`이 생성됨

### 3단계: 검색 인덱스 구축

- 임베딩 클라이언트 추상화
- 로컬 벡터 저장소 구현
- 키워드 fallback 검색 구현
- `rag ingest` 실행 스크립트 추가

완료 기준:

- "JPA N+1", "RESTful API", "트랜잭션 격리" 같은 쿼리로 관련 문제/청크가 검색됨
- 인덱스가 없어도 키워드 fallback이 동작함

### 4단계: 문제 뱅크 기반 추천 퀴즈 선택

- `quiz_recommender.py` 구현
- 리포트 내용에서 주제 추출
- 문제 뱅크에서 가까운 기존 문제만 추천
- 아키텍처 4.2의 `recommendedQuizzes[]` payload 생성

완료 기준:

- "Spring JPA N+1 문제와 해결 방법을 공부했다" 입력 시 DB/Framework 배점의 JPA 관련 퀴즈가 추천됨
- 문제는 1-3개로 제한됨
- 가까운 문제가 없으면 `recommendedQuizzes: []`
- 추천 문제는 `problemId`, `question`, `modelAnswer`, `scoreAllocation`을 모두 포함함

### 5단계: 채점 근거 보강

- `problemId` 기반 근거 조회
- `question + modelAnswer` 기반 근거 검색
- 채점 프롬프트에 근거 삽입
- 점수 clamp 검증

완료 기준:

- `scoreDelta[field] <= scoreAllocation[field]`가 항상 보장됨
- RAG 조회 실패 시에도 payload 기반 채점이 가능함

### 6단계: 흐름 A/B에 연결

- 리포트 파이프라인에서 추천 모듈 호출
- 채점 API에서 RAG 보강 모듈 호출
- Spring Boot 콜백 payload를 아키텍처 4번 계약과 맞춤

완료 기준:

- 흐름 A 결과 콜백에 `recommendedQuizzes` 포함
- 흐름 B 웹훅에 `GRADED` 또는 `UNGRADED` 포함
- 실패 시 fallback payload가 계약을 만족함

## 10. 테스트 계획

단위 테스트:

- QnA parser: `<details>` 내부 답변 추출
- Notion quiz parser: `[기본] Q1`, `[심화] Q2` 추출
- field mapper: 경로와 제목 기반 5개 능력치 매핑
- score allocation: 필드별 `0..10` 범위 검증. 합계 cap은 적용하지 않음
- sourceKey: 같은 입력은 같은 키, 질문 변경 시 새 키

통합 테스트:

- fixture 인덱싱 후 검색 smoke test
- dailyReport 입력에서 추천 퀴즈 생성
- 채점 요청에서 `problemId` 근거 검색
- 임베딩 실패 시 키워드 fallback

계약 테스트:

- 흐름 A 콜백 JSON이 아키텍처 4.2 스키마를 만족
- 흐름 B 수락 응답과 웹훅 JSON이 아키텍처 4.3 스키마를 만족
- 실패 케이스에서 `scoreDelta`가 모두 0이거나 범위 안에 있음

## 11. 운영 메모

인덱스 재생성 명령은 개발 단계에서 CLI로 시작하고, 필요하면 내부 API로 확장한다.

권장 명령 형태:

```text
python -m app.rag.ingest --source "$RAG_SOURCE_ROOT" --out "$RAG_DATA_DIR"
```

로그에 남길 항목:

- ingestion 시작/종료 시간
- 발견 파일 수, 제외 파일 수
- 생성 문서 수, 청크 수, 문제 수
- embedding 실패 수
- 문제 뱅크 변경 수

추적할 품질 지표:

- 추천 퀴즈가 리포트 주제와 맞는 비율
- 기존 문제 매칭률과 미추천 비율
- 채점 feedback이 모범답안의 핵심 키워드를 반영하는 비율
- fallback 발생률

## 12. 열린 질문

1. FastAPI 전용 로컬 인덱스로 MVP를 시작할지, 처음부터 `commitgotchi_ai` DB와 pgvector로 갈지 결정이 필요하다. 현재 계획은 로컬 인덱스 우선이다.
2. 추천 퀴즈 개수는 1-3개로 가정했다. Spring Boot와 화면 설계에서 하루 노출 개수를 확정해야 한다.
3. `field` 매핑에서 Frontend/Cloud/AI Engineering을 `framework`로 볼지, 일부를 `cs`나 `network`로 나눌지 합의가 필요하다.
4. 문제 뱅크에 없는 주제는 문제를 내지 않는 정책을 UX에서 어떻게 보여줄지 확인해야 한다.
5. `(대외비)` 자료는 기본 제외로 잡았다. 의도적으로 포함해야 한다면 별도 allowlist가 필요하다.

## 13. 추천 다음 작업

우선순위는 다음과 같다.

1. `fastapi/app/rag/schemas.py`에 Document, Chunk, Problem 모델 정의
2. `fastapi/app/rag/parsers/qna_parser.py`와 테스트 작성
3. `fastapi/app/rag/parsers/notion_quiz_parser.py`와 테스트 작성
4. `fastapi/app/rag/ingest.py`로 JSONL 카탈로그 생성
5. 키워드 검색 fallback 먼저 구현
6. 임베딩/벡터 검색은 그 다음 단계로 추가

이 순서가 좋은 이유는, RAG에서 가장 위험한 부분이 모델 호출보다 "자료를 문제와 근거로 안정적으로 구조화하는 것"이기 때문이다. 카탈로그가 먼저 서면 벡터 저장소는 교체 가능해진다.
