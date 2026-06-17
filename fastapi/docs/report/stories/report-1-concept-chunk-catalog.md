---
title: Report 1 - Concept Chunk Catalog
status: done
created: 2026-06-13
owner: FastAPI AI 서버
epic: report-generation
source_docs:
  - ../report-generation-epic.md
  - ../report-generation-sprint-status.yaml
  - ../../quiz-bank/problem-bank-implementation.md
---

# Report 1. Concept Chunk Catalog

## 목표

`/Users/tlsdla1235/study/md파일들/computer-science-interview-KR` 하위 Markdown 문서를 읽어서 FastAPI 내부 리포트 RAG가 사용할 개념 chunk catalog를 만든다.

최종 산출물은 `fastapi/data/rag/catalog/chunks.jsonl`이다. 각 chunk는 원문 파일, heading 계층, 문서 내 순서, 내용 hash, 필드 힌트, 이전/다음/부모 관계를 추적할 수 있어야 한다.

## 배경

문제 은행 쪽은 이미 `fastapi/data/rag/catalog/problems.jsonl` 생성, loader, 검색, 리포트 기반 퀴즈 추천까지 구현되어 있다. 이번 story는 그 흐름을 갈아엎는 작업이 아니다.

이번 작업은 리포트 분석 epic의 첫 단계로, 리포트 텍스트를 지식 문서와 연결하기 위한 별도 개념 catalog를 준비한다. 기존 문제 은행 구현에서 참고할 수 있는 부분은 source discovery, JSONL writer, skipped/rejected/summary report, 파일 없음/빈 파일을 안전하게 처리하는 loader 패턴 정도다.

중요한 경계:

- source root는 반드시 `/Users/tlsdla1235/study/md파일들/computer-science-interview-KR`로 제한한다.
- 기존 `problems.jsonl` 생성 로직과 추천 로직은 수정 대상이 아니다.
- pgvector, embedding, Gemini 호출, 리포트 분석은 이번 story 범위가 아니다.

## 구현 범위

이번 story에서 구현할 것은 개념 catalog 생성과 최소 loader다.

1. Markdown source discovery
   - 지정 source root 아래만 탐색한다.
   - `.md`/`.markdown` 같은 Markdown 파일만 catalog 후보로 삼는다.
   - 비 Markdown 파일, 빈 파일, 읽을 수 없는 파일, 너무 짧은 문서는 저장하지 않고 skip/report에 남긴다.
   - discovery 순서는 OS/filesystem 순서에 의존하지 않고 normalized relative path 기준으로 정렬한다.

2. Heading 기반 chunking
   - Markdown ATX heading(`#`, `##`, ... `######`)을 기준으로 heading tree를 만든다.
   - 각 chunk는 하나의 heading section을 기본 단위로 한다.
   - 코드블록 안의 `#` 문자는 heading으로 오인하지 않는다.
   - heading이 없는 문서는 파일명을 fallback heading으로 사용할 수 있다.
   - 너무 긴 section은 문단 경계 기준으로 쪼갤 수 있지만, 쪼갠 chunk도 같은 `headingPath`를 유지한다.
   - 너무 짧은 chunk는 저장하지 않고 skip/report에 남긴다. 단, parent heading 관계를 깨뜨리지 않도록 skip 이유와 위치를 기록한다.

3. Stable metadata 생성
   - 같은 입력을 여러 번 빌드해도 `chunkId`, `contentHash`, catalog line order가 동일해야 한다.
   - catalog line order는 `sourcePath`, `chunkIndex` 기준으로 안정적으로 정렬한다.
   - `sourcePath`는 source root 기준 상대 경로로 저장한다.
   - `chunkIndex`는 같은 `sourcePath` 안에서 0부터 시작하는 문서 내 chunk 순서다.
   - `contentHash`는 정규화된 chunk text 기준 hash다.
   - `chunkId`는 `sourcePath`, `headingPath`, `chunkIndex`를 기반으로 stable하게 만든다.

4. Neighbor metadata 저장
   - 같은 문서 내 이전 chunk와 다음 chunk를 따라갈 수 있어야 한다.
   - 현재 chunk의 부모 heading을 따라갈 수 있어야 한다.
   - 후속 story의 source-neighborhood 확장을 위해 sibling/child 후보를 계산하기 쉬운 형태로 둔다.

5. Field hints
   - chunk text와 heading/source path에서 `db`, `algorithm`, `cs`, `network`, `framework` 후보를 추론한다.
   - `fieldHints`는 retrieval/filter 보조 정보일 뿐 점수 산정 근거가 아니다.
   - 기존 `fastapi/app/rag/text.py`의 field keyword 정책을 재사용하거나, 같은 필드 vocabulary를 유지한다.

6. JSONL writer와 report
   - `chunks.jsonl`을 line-delimited JSON으로 생성한다.
   - discovery manifest와 summary/skipped/rejected report를 함께 남긴다.
   - dry-run 또는 테스트용 출력 디렉터리를 지원하면 fixture 테스트가 쉬워진다.

7. Loader 기초
   - `chunks.jsonl`을 읽어 `ConceptCatalogStore` 같은 in-memory store로 로드한다.
   - 파일 없음, 빈 파일, invalid JSON line을 안전하게 처리한다.
   - strict 모드에서는 schema/JSON 오류를 실패로 올릴 수 있게 한다.
   - 이번 story의 loader는 검색 알고리즘까지 책임지지 않는다.

## 주요 파일 경로

원천:

- `/Users/tlsdla1235/study/md파일들/computer-science-interview-KR`

생성 산출물:

- `fastapi/data/rag/catalog/chunks.jsonl`
- `fastapi/data/rag/manifests/concept-catalog-discovery.json`
- `fastapi/data/rag/reports/concept-catalog-summary.json`
- `fastapi/data/rag/reports/concept-catalog-summary.md`
- `fastapi/data/rag/reports/concept-catalog-skipped.jsonl`
- `fastapi/data/rag/reports/concept-catalog-rejected.jsonl`

구현 후보:

- `fastapi/app/rag/concept_catalog.py`
- `fastapi/app/rag/build_concept_catalog.py`
- `fastapi/app/rag/concept_store.py`
- `fastapi/app/rag/schemas.py`
- `fastapi/app/rag/text.py`

테스트 후보:

- `fastapi/tests/rag/test_concept_catalog.py`
- `fastapi/tests/rag/test_concept_store.py`
- `fastapi/tests/fixtures/rag/concept_catalog/`
- `fastapi/tests/fixtures/rag/concept_store/`

참고만 할 기존 파일:

- `fastapi/app/rag/problem_bank.py`
- `fastapi/app/rag/build_problem_bank.py`
- `fastapi/app/rag/problem_bank_store.py`
- `fastapi/tests/rag/test_problem_bank.py`
- `fastapi/tests/rag/test_problem_bank_store.py`

## 권장 데이터 Shape

`chunks.jsonl`의 각 line은 하나의 JSON object다.

```json
{
  "chunkId": "concept:sha256:...",
  "sourcePath": "02-backend-engineering/README.md",
  "sourceRoot": "/Users/tlsdla1235/study/md파일들/computer-science-interview-KR",
  "headingPath": ["Backend Engineering", "JPA", "N+1 문제"],
  "headingLevel": 3,
  "chunkIndex": 7,
  "text": "N+1 문제는 연관 관계를 지연 로딩할 때...",
  "contentHash": "sha256:...",
  "fieldHints": ["db", "framework"],
  "neighbors": {
    "previousChunkId": "concept:sha256:...",
    "nextChunkId": "concept:sha256:...",
    "parentHeadingPath": ["Backend Engineering", "JPA"],
    "siblingHeadingPaths": [
      ["Backend Engineering", "JPA", "Fetch Join"],
      ["Backend Engineering", "JPA", "EntityGraph"]
    ]
  }
}
```

필수 필드:

- `chunkId`: stable id. 같은 입력에서는 재생성해도 동일해야 한다.
- `sourcePath`: source root 기준 상대 경로. 절대 경로를 넣지 않는다.
- `headingPath`: 문서 내 heading 계층. 빈 배열보다 fallback heading을 두는 편이 좋다.
- `chunkIndex`: 같은 문서 안에서의 0-based 순서.
- `text`: 검색과 Gemini grounding에 넘길 정규화된 chunk 본문.
- `contentHash`: 정규화된 `text` 기반 hash.
- `fieldHints`: `db`, `algorithm`, `cs`, `network`, `framework` 중 후보. 없으면 `[]`.
- `neighbors`: 같은 문서 안의 이전/다음 chunk와 부모 heading을 추적할 수 있는 객체.

권장 추가 필드:

- `sourceRoot`: manifest/debugging용. MVP에서는 절대 source root를 남겨도 된다.
- `headingLevel`: chunk의 대표 heading level.
- `charStart`, `charEnd`: 원문 파일 안의 문자 범위. 가능하면 저장한다.
- `tokenHints` 또는 `terms`: MVP keyword search를 돕는 정규화 term 목록. 후속 검색 story에서 추가해도 된다.

## Acceptance Criteria

- `build_concept_catalog`는 source root를 `/Users/tlsdla1235/study/md파일들/computer-science-interview-KR`로 제한하거나, 명시 입력을 받더라도 이 root 밖 입력을 거부한다.
- Markdown 후보 파일은 deterministic order로 discovery된다.
- 비 Markdown 파일은 catalog에 들어가지 않고 skip report에 기록된다.
- 빈 파일, 공백뿐인 파일, 읽기 실패 파일은 catalog에 들어가지 않고 skip/rejected report에 기록된다.
- 너무 짧은 chunk는 catalog에 들어가지 않고 skip report에 `sourcePath`, `headingPath`, reason과 함께 기록된다.
- heading 구조가 `headingPath`로 보존된다.
- heading이 없는 Markdown도 fallback heading으로 안전하게 처리된다.
- `chunks.jsonl`의 모든 line은 schema validation을 통과한다.
- 각 chunk는 `chunkId`, `sourcePath`, `headingPath`, `chunkIndex`, `text`, `contentHash`, `fieldHints`, `neighbors`를 가진다.
- 같은 문서 내 이전 chunk와 다음 chunk를 `neighbors.previousChunkId`, `neighbors.nextChunkId`로 추적할 수 있다.
- 부모 heading은 `neighbors.parentHeadingPath`로 추적할 수 있다.
- 동일 입력으로 여러 번 생성해도 `chunkId`, `contentHash`, `chunkIndex`, JSONL line order가 동일하다.
- loader는 `chunks.jsonl`이 없거나 비어 있어도 빈 catalog를 안전하게 반환한다.
- loader는 invalid JSON/schema line을 issue로 수집하고, strict 모드에서는 실패시킬 수 있다.
- 기존 `fastapi/data/rag/catalog/problems.jsonl` 파일과 문제 은행 생성/추천 로직은 변경하지 않는다.

## 테스트 기준

fixture Markdown 기준으로 테스트할 수 있게 만든다. 실제 `/Users/tlsdla1235/study/md파일들/computer-science-interview-KR` 전체를 매번 테스트에 사용하지 않는다.

권장 fixture 구성:

```text
fastapi/tests/fixtures/rag/concept_catalog/
  01-db/transaction.md
  02-network/http.md
  03-framework/jpa.md
  no-heading.md
  empty.md
  short.md
  assets/logo.png
```

필수 테스트:

- heading tree가 기대한 `headingPath`로 변환된다.
- nested heading에서 parent heading이 `neighbors.parentHeadingPath`에 저장된다.
- 같은 문서 안에서 이전/다음 chunk가 올바른 `chunkId`로 연결된다.
- 동일 fixture를 두 번 빌드했을 때 `chunks.jsonl` line 목록이 byte 수준 또는 JSON object 순서 수준에서 동일하다.
- chunk 본문이 바뀌면 `contentHash`가 바뀐다.
- 같은 입력에서는 `chunkId`가 안정적으로 유지된다.
- 코드블록 안의 `# heading처럼 보이는 문자열`은 heading으로 처리하지 않는다.
- 목록, 표, 링크, inline code가 포함된 문서도 chunk text가 비거나 깨지지 않는다.
- 비 Markdown 파일은 skip report에 `unsupported_file_type` 같은 reason으로 기록된다.
- 빈 파일은 skip report에 `empty_file` 같은 reason으로 기록된다.
- 너무 짧은 chunk는 skip report에 `chunk_too_short` 같은 reason으로 기록된다.
- catalog 파일 없음/빈 파일/invalid JSON line에 대한 loader 동작을 검증한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.rag.test_concept_catalog tests.rag.test_concept_store
```

전체 회귀 확인:

```bash
cd fastapi
python3 -m unittest discover -s tests
```

## 제외 범위

이번 story에서 하지 않는다.

- `problems.jsonl` 재생성 방식 변경
- `daily_report_recommender.py` 추천 정책 변경
- 기존 문제 은행 검색 로직 변경
- pgvector 저장
- embedding 생성 또는 운영 vector index 구축
- Gemini 또는 외부 LLM 호출
- 리포트 chunking
- 리포트 점수화, feedback 생성, `scoreDelta` 산정
- source-neighborhood 검색 확장 알고리즘
- API endpoint
- SQS consumer
- Spring Boot callback 전송

## 개발 메모

- `problem_bank.py`의 `ReportEntry`, discovery result, summary/report writer 흐름은 참고하기 좋다. 단, 문제 추출 로직은 가져오지 않는다.
- `problem_bank_store.py`의 `read_problem_jsonl(..., strict=False)` 패턴은 concept loader에도 그대로 어울린다.
- `text.py`의 `normalize_text`, `stable_hex`, `relative_source_path`, `infer_fields`는 재사용 후보가 좋다.
- Markdown parsing은 처음부터 완전한 Markdown AST가 아니어도 된다. 다만 fenced code block 안의 heading 오인은 반드시 피한다.
- source root 문자열은 한글 경로가 포함되므로 모든 파일 IO는 UTF-8 계열을 우선하고, 기존 loader처럼 필요하면 fallback encoding을 둔다.
- `sourcePath`는 절대 경로가 아니라 source root 기준 상대 경로로 저장한다. 절대 경로는 `sourceRoot`나 manifest/debugging 영역에만 둔다.
- `chunkId`는 사람이 읽기 좋은 slug보다 deterministic hash 기반이 안전하다. 예: `concept:sha256:<hash>`.
- `contentHash`와 `chunkId`의 목적은 다르다. `contentHash`는 본문 변경 감지용이고, `chunkId`는 문서 내 위치 추적용이다.
- 최소 chunk 길이는 테스트에서 조정 가능하게 상수화한다. Korean/English 혼합 문서라서 단어 수보다 정규화 text 길이나 term count를 함께 보는 편이 안전하다.
- `fieldHints`는 점수 필드가 아니다. 리포트 점수는 후속 Gemini 분석 story에서 서버 validation/clamp와 함께 다룬다.
- 실제 source root에는 `01-computer-science-fundamentals`부터 `11-cloud-engineering`, `99-practical-interview`까지의 폴더가 있다. catalog builder는 특정 폴더명에 의존하지 않아야 한다.
- 생성 산출물은 `fastapi/data/rag` 아래로 제한한다. 테스트에서는 임시 `out_dir`을 사용해 실제 catalog를 덮어쓰지 않게 한다.

## Tasks/Subtasks

- [x] Concept chunk catalog builder 구현
- [x] Markdown discovery, heading 기반 chunking, stable metadata, neighbor metadata 구현
- [x] `chunks.jsonl` loader/store 기초 구현
- [x] fixture Markdown 기반 unit test 작성
- [x] 실제 source root로 concept catalog 생성
- [x] 지정 concept 테스트와 전체 unittest 회귀 테스트 실행

## Dev Agent Record

### Debug Log

- 2026-06-13: `git status --short --branch`로 시작 상태 확인.
- 2026-06-13: story 문서와 기존 problem bank/store 패턴 확인.
- 2026-06-13: concept catalog builder/store/schema/CLI와 fixture 테스트 구현.
- 2026-06-13: concept chunk 정규화에서 기술 용어 하이픈 보존하도록 조정.
- 2026-06-13: 실제 source root로 catalog 생성 완료.
- 2026-06-13: catalog summary, chunk samples, skip samples, ID/neighbor/index integrity sanity check 완료.

### Completion Notes

- `/Users/tlsdla1235/study/md파일들/computer-science-interview-KR` 하위 Markdown만 허용하는 concept catalog builder를 추가했다.
- heading tree 기반 chunking, fallback heading, code fence heading 무시, stable `chunkId`/`contentHash`/line order, previous/next/parent neighbor metadata, `fieldHints` 추론을 구현했다.
- `chunks.jsonl` loader/store는 파일 없음, 빈 파일, invalid JSON/schema line을 안전하게 처리하고 strict 모드 실패를 지원한다.
- 기존 `problems.jsonl`, `problem_bank.py`, `daily_report_recommender.py`, `quiz_recommender.py`는 수정하지 않았다.
- 생성 catalog sanity check 결과 `sourcePath` 상대 경로, chunk text, headingPath, fieldHints, neighbor 연결이 정상 범위로 확인되어 story를 `done` 처리했다.

## File List

- `fastapi/app/rag/schemas.py`
- `fastapi/app/rag/concept_catalog.py`
- `fastapi/app/rag/build_concept_catalog.py`
- `fastapi/app/rag/concept_store.py`
- `fastapi/tests/rag/test_concept_catalog.py`
- `fastapi/tests/rag/test_concept_store.py`
- `fastapi/tests/fixtures/rag/concept_catalog/01-db/transaction.md`
- `fastapi/tests/fixtures/rag/concept_catalog/02-network/http.md`
- `fastapi/tests/fixtures/rag/concept_catalog/03-framework/jpa.md`
- `fastapi/tests/fixtures/rag/concept_catalog/no-heading.md`
- `fastapi/tests/fixtures/rag/concept_catalog/empty.md`
- `fastapi/tests/fixtures/rag/concept_catalog/short.md`
- `fastapi/tests/fixtures/rag/concept_catalog/assets/logo.png`
- `fastapi/data/rag/catalog/chunks.jsonl`
- `fastapi/data/rag/manifests/concept-catalog-discovery.json`
- `fastapi/data/rag/reports/concept-catalog-summary.json`
- `fastapi/data/rag/reports/concept-catalog-summary.md`
- `fastapi/data/rag/reports/concept-catalog-skipped.jsonl`
- `fastapi/data/rag/reports/concept-catalog-rejected.jsonl`
- `fastapi/docs/report/stories/report-1-concept-chunk-catalog.md`
- `fastapi/docs/report/report-generation-sprint-status.yaml`

## Change Log

- 2026-06-13: Concept chunk catalog generation, loader/store, fixture tests, generated catalog artifacts, and sanity check completed.
