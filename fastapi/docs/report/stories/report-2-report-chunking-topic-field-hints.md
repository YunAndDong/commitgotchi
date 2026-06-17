---
title: Report 2 - Report Chunking and Topic/Field Hints
status: done
created: 2026-06-13
owner: FastAPI AI 서버
epic: report-generation
source_docs:
  - ../report-generation-epic.md
  - ../report-generation-sprint-status.yaml
  - ./report-1-concept-chunk-catalog.md
---

# Report 2. Report Chunking and Topic/Field Hints

## Status

done

## 목표

사용자의 일일 리포트 `title`/`content` 입력을 정규화하고, 문단, bullet, Markdown heading, 문장 경계를 고려해 여러 report chunk로 나눈다.

각 report chunk는 원문 `title + content` 기준 위치와 검색 보조용 `topicHints`, `fieldHints` 후보를 가져야 한다. 이 story의 결과는 후속 RAG 검색과 리포트 분석의 입력 준비 단계이며, 점수 산정이나 추천 실행을 하지 않는다.

## 배경

리포트 분석 epic은 사용자의 일일 학습 리포트가 하나의 주제만 담는다고 가정하지 않는다. 예를 들어 하루 리포트 하나에 `JPA N+1`, `OAuth/JWT`, `프로세스/스레드`가 함께 들어올 수 있다. 후속 story에서 각 주제별로 적절한 knowledge chunk를 검색하려면, 먼저 리포트 자체를 안정적인 단위로 나누고 각 단위에 후보 topic/field 정보를 붙여야 한다.

Story 1은 완료된 상태이며, `computer-science-interview-KR` 기반 concept chunk catalog와 loader/store 기초를 만들었다. Story 2는 그 catalog를 읽거나 검색하지 않는다. Story 2는 사용자의 리포트 텍스트만 다루는 deterministic preprocessing 단계다.

중요한 경계:

- 기존 문제 은행 생성/추천 흐름은 수정 대상이 아니다.
- Story 1에서 만든 `fastapi/data/rag/catalog/chunks.jsonl`을 읽거나 검색하지 않는다.
- Gemini/LLM 호출, embedding, pgvector, RAG 검색, `scoreDelta` 산정, quiz recommender 연결은 이번 story 범위가 아니다.

## 구현 범위

이번 story에서 구현할 것은 일일 리포트 입력을 report chunk 목록으로 변환하는 순수 내부 로직이다.

1. 입력 정규화
   - `title`과 `content`를 받아 하나의 원문 문자열로 결합한다.
   - 원문 위치 추적을 위해 결합 규칙을 deterministic하게 고정한다.
   - 권장 결합 규칙은 `title.strip()`이 있으면 `title + "\n\n" + content`, 없으면 `content`다.
   - Unicode normalization은 기존 `fastapi/app/rag/text.py`의 `normalize_text`를 재사용한다.
   - Markdown 표시 문자는 topic boundary 판단에는 사용하되, `charStart`/`charEnd`는 정규화 후 원문 결합 문자열 기준으로 추적한다.

2. Boundary detection
   - Markdown heading(`#`, `##`, ... `######`)은 강한 topic boundary로 처리한다.
   - bullet list(`-`, `*`, `+`, ordered list)는 순서를 보존하며 독립 segment 후보로 처리한다.
   - 빈 줄로 구분되는 일반 문단은 segment 후보로 처리한다.
   - 긴 문단은 문장 경계(`.`, `!`, `?`, `。`, `다.`, `요.` 등)나 쉼표/세미콜론 이후 의미 단위 기준으로 분할한다.
   - 코드블록 안의 `#` 또는 bullet-like 문자는 boundary로 오인하지 않도록 한다.

3. Chunk assembly
   - 여러 segment를 의미가 크게 깨지지 않는 범위에서 chunk로 묶는다.
   - 너무 짧은 segment는 인접 segment와 병합하되 원래 순서를 보존한다.
   - 공백/빈 리포트는 예외를 던지는 대신 빈 chunk 목록을 반환한다.
   - 아주 짧은 리포트는 단일 chunk로 안전하게 처리한다.
   - 긴 단일 문단은 너무 큰 chunk 하나로 남기지 말고 deterministic하게 분할한다.

4. Stable metadata 생성
   - chunk 순서는 입력에 나타난 순서와 같아야 한다.
   - `reportChunkId`는 같은 입력에서 여러 번 실행해도 같은 순서와 값을 유지해야 한다.
   - 권장 ID는 순서 기반 `report:0`, `report:1`, ... 이다.
   - 각 chunk는 `charStart`, `charEnd`로 원문 결합 문자열의 substring 추적이 가능해야 한다.
   - `text`는 `combined_text[charStart:charEnd]`에서 Markdown heading/list marker 같은 불필요한 wrapper만 최소 정리한 내용이어야 한다. 단, substring 추적 검증이 가능하도록 원문 의미를 바꾸지 않는다.

5. Topic hints 추출
   - `topicHints`는 retrieval query 보조 후보일 뿐 정답 label이 아니다.
   - 명시적인 기술 키워드, heading text, backtick/영문 대문자 토큰, slash 조합(`OAuth/JWT`), plus 조합(`JPA N+1`)을 우선 후보로 둔다.
   - 기존 `fastapi/app/rag/daily_report_recommender.py`의 `TOPIC_RULES`는 참고할 수 있지만, 기존 recommender 흐름을 호출하거나 변경하지 않는다.
   - 후보는 중복 제거하고 입력 순서에 가까운 deterministic order를 유지한다.
   - 후보가 없으면 `[]`를 허용한다.

6. Field hints 추출
   - `fieldHints`는 `fastapi/app/rag/text.py`의 `FIELD_KEYWORDS`, `infer_fields`, `SCORE_FIELDS` vocabulary와 최대한 맞춘다.
   - 허용 필드는 `db`, `algorithm`, `cs`, `network`, `framework`다.
   - `fieldHints`는 retrieval/filter 보조 정보일 뿐 최종 점수로 직접 반영하지 않는다.
   - 후보가 없거나 근거가 약한 경우 `[]` 또는 보수적 fallback을 사용할 수 있으나, scoring 객체를 만들면 안 된다.

7. Schema/serialization
   - `fastapi/app/scoring/schemas.py`에 dataclass 기반 `ReportChunk`와 validation helper를 추가하는 방식을 우선 고려한다.
   - 기존 프로젝트는 dataclass와 `to_dict()`/`to_json_dict()` 패턴을 사용한다.
   - Spring Boot callback 계약이나 API payload는 이번 story에서 정의하지 않는다.

## 주요 파일 경로

구현 후보:

- `fastapi/app/scoring/report_chunker.py`
- `fastapi/app/scoring/schemas.py`
- 기존 구조상 더 적절하면 `fastapi/app/rag/text.py`의 normalization/field helper를 import해서 사용한다.

테스트 후보:

- `fastapi/tests/scoring/test_report_chunker.py`

참고만 할 파일:

- `fastapi/app/rag/text.py`
- `fastapi/app/rag/daily_report_recommender.py`
- `fastapi/app/rag/schemas.py`
- `fastapi/app/rag/concept_catalog.py`
- `fastapi/tests/rag/test_concept_catalog.py`

수정하지 않아야 할 흐름:

- `fastapi/app/rag/problem_bank.py`
- `fastapi/app/rag/build_problem_bank.py`
- `fastapi/app/rag/problem_bank_store.py`
- `fastapi/app/rag/daily_report_recommender.py`의 기존 추천 contract
- `fastapi/app/rag/quiz_recommender.py`

## 권장 데이터 Shape

하나의 report chunk는 다음 shape를 갖는다.

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

권장 dataclass:

```python
@dataclass(frozen=True)
class ReportChunk:
    report_chunk_id: str
    text: str
    char_start: int
    char_end: int
    topic_hints: tuple[str, ...]
    field_hints: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "reportChunkId": self.report_chunk_id,
            "text": self.text,
            "charStart": self.char_start,
            "charEnd": self.char_end,
            "topicHints": list(self.topic_hints),
            "fieldHints": list(self.field_hints),
        }
```

권장 public 함수:

```python
def chunk_daily_report(title: str | None, content: str | None) -> list[ReportChunk]:
    ...
```

선택적으로 디버깅과 테스트를 쉽게 하기 위해 다음 helper를 둘 수 있다.

- `combine_report_text(title, content) -> str`
- `split_report_segments(combined_text) -> list[ReportSegment]`
- `extract_topic_hints(text, heading_context=None) -> list[str]`
- `extract_field_hints(text, topic_hints=None) -> list[str]`

## Acceptance Criteria

- 여러 주제를 포함한 리포트는 순서를 보존한 여러 report chunk로 분리된다.
- 각 report chunk는 `reportChunkId`, `text`, `charStart`, `charEnd`, `topicHints`, `fieldHints`를 가진다.
- `charStart`/`charEnd`는 원문 `title + content` 기준 위치 추적이 가능해야 한다.
- 각 chunk의 `text`는 원문 substring에서 유래한 의미 단위여야 하며 주요 내용이 손실되지 않는다.
- chunk 순서는 deterministic해야 한다.
- 동일 입력으로 여러 번 chunking해도 stable id/order가 유지된다.
- 빈 리포트, 공백 리포트, 아주 짧은 리포트도 예외 없이 안전하게 처리한다.
- 긴 단일 문단은 의미가 크게 깨지지 않는 선에서 여러 chunk로 분할한다.
- bullet list, Markdown heading, 일반 문단이 섞인 입력에서도 chunk 순서가 보존된다.
- Markdown heading은 topic boundary로 작동한다.
- `topicHints`와 `fieldHints`는 retrieval/filter 보조 정보일 뿐 최종 점수로 직접 반영하지 않는다.
- `fieldHints`는 기존 `fastapi/app/rag/text.py`의 field vocabulary와 최대한 맞춘다.
- Story 1에서 만든 `chunks.jsonl` catalog를 읽거나 검색하지 않는다.
- Gemini/LLM 호출을 하지 않는다.
- embedding, vector store, pgvector를 구현하지 않는다.
- 최종 `scoreDelta`를 만들지 않는다.
- 기존 quiz recommender를 수정하지 않는다.
- 기존 문제 은행 생성/추천 테스트가 깨지지 않는다.

## 테스트 기준

필수 테스트:

- `JPA N+1`, `OAuth/JWT`, `프로세스/스레드`가 섞인 리포트가 2개 이상의 chunk로 나뉘는지 검증한다.
- bullet list와 일반 문단이 섞인 입력에서 chunk 순서 보존을 검증한다.
- Markdown heading이 topic boundary로 작동하는지 검증한다.
- 아주 짧은 입력, 공백 입력, 긴 단일 문단 입력을 안전하게 처리하는지 검증한다.
- `charStart`/`charEnd`로 원문 substring을 추적할 수 있는지 검증한다.
- 동일 입력 재실행 시 stable id/order를 검증한다.
- field 후보가 있어도 최종 점수 객체를 만들지 않는지 검증한다.

권장 테스트 예시:

- `title="오늘의 학습"`와 여러 주제 `content`를 넣었을 때 `combined_text[chunk.char_start:chunk.char_end]`가 chunk text의 원천임을 검증한다.
- `# JPA`, `## OAuth`, `## OS` 같은 heading이 각각 chunk boundary를 만드는지 검증한다.
- `- JPA N+1 정리`, `- JWT 인증 흐름 정리` 같은 bullet이 입력 순서대로 chunk에 반영되는지 검증한다.
- 공백 title/content 조합은 `[]`를 반환하는지 검증한다.
- 짧은 입력 `"JWT 복습"`은 단일 chunk 또는 안전한 fallback chunk로 반환되는지 검증한다.
- 긴 단일 문단은 최대 chunk 길이 정책에 따라 2개 이상으로 나뉘고, 중간 문장이 과도하게 잘리지 않는지 검증한다.
- `fieldHints`에는 `SCORE_FIELDS` 밖의 값이 들어가지 않는지 검증한다.
- `chunk_daily_report` 결과 dict에 `scoreDelta`, `recommendedQuizzes`, `matches`, `embedding`, `evidence` 같은 후속 story 필드가 없는지 검증한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_report_chunker
```

전체 회귀 확인:

```bash
cd fastapi
python3 -m unittest discover -s tests
```

## 제외 범위

이번 story에서 하지 않는다.

- Story 1의 `chunks.jsonl` catalog 읽기 또는 검색
- knowledge chunk catalog 생성 또는 재생성
- report chunk별 RAG 검색
- source-neighborhood 확장
- Gemini/LLM 호출
- prompt 작성 또는 structured model output parsing
- embedding 생성
- vector store 또는 pgvector 구현
- 최종 `scoreDelta` 산정
- emotion/statusMessage/dailyReport feedback/nextRecommendation 생성
- 기존 quiz recommender 연결 또는 수정
- 문제 은행 생성/검색/추천 흐름 수정
- API endpoint
- SQS consumer
- Spring Boot callback 전송

## 개발 메모

- 이 story는 deterministic preprocessing이다. 같은 입력이면 같은 output이어야 하므로 random, time, hash randomization에 의존하지 않는다.
- `reportChunkId`는 순서 기반 `report:0` 형식이 가장 단순하고 안정적이다. 후속 story에서 chunk별 RAG 결과를 이 ID에 매핑할 수 있다.
- `charStart`/`charEnd`는 Python string index 기준으로 명시한다. UTF-8 byte offset이 아니다.
- `title`과 `content`를 결합한 문자열 자체를 테스트에서 재사용할 수 있게 helper로 분리하면 위치 검증이 쉬워진다.
- Markdown heading과 bullet marker를 제거한 `text`를 만들더라도, 원문 위치는 marker 포함 segment 범위를 가리켜도 된다. 단 테스트에서 `chunk.text`가 해당 substring에서 유래했음을 설명 가능해야 한다.
- 코드블록이 들어온 경우 코드 내용을 완벽히 파싱할 필요는 없지만, code fence 내부의 heading/bullet-like 문자가 topic boundary로 오인되면 안 된다.
- `fastapi/app/rag/text.py`의 `normalize_text`, `clean_markdown`, `extract_terms`, `infer_fields`, `SCORE_FIELDS` 또는 `FIELD_KEYWORDS` 재사용을 우선한다.
- `infer_fields`는 현재 신호가 없으면 `["cs"]` fallback을 반환한다. report chunker에서 이 fallback을 그대로 쓸지, 근거가 있는 경우만 field hint로 둘지는 테스트와 의도를 맞춰 보수적으로 결정한다.
- `daily_report_recommender.py`에는 `TOPIC_RULES`와 기존 recommendation contract가 있다. topic rule만 참고할 수 있지만, 기존 recommender 호출이나 contract 변경은 하지 않는다.
- `fieldHints`는 점수 필드와 이름이 같아도 점수가 아니다. `scoreDeltaHint`, `scoreAllocation`, `scoreDelta` 같은 객체를 만들지 않는다.
- Story 2 테스트는 실제 catalog 파일이나 외부 source root에 의존하지 않는 순수 unit test로 작성한다.
- 한글/영문 혼합 기술 용어가 많으므로 topic 추출은 단순 whitespace token만 믿지 말고 `OAuth/JWT`, `JPA N+1`, `Lazy Loading`, `프로세스/스레드` 같은 복합 표현을 보존한다.

## Tasks/Subtasks

- [x] `ReportChunk` schema와 serialization/validation helper 설계
- [x] `title`/`content` 결합 및 normalization helper 구현
- [x] Markdown heading, bullet, 일반 문단, 문장 경계 기반 segment splitter 구현
- [x] 짧은 segment 병합 및 긴 segment 분할 정책 구현
- [x] stable `reportChunkId`, `charStart`, `charEnd` metadata 생성
- [x] topic hint 추출 규칙 구현
- [x] `fastapi/app/rag/text.py` vocabulary 기반 field hint 추출 구현
- [x] 빈/공백/아주 짧은/긴 단일 문단 안전 처리 구현
- [x] `fastapi/tests/scoring/test_report_chunker.py` unit test 작성
- [x] 기존 scoring/rag unittest 회귀 확인
- [x] Story 2 범위 밖 필드(`scoreDelta`, embedding, RAG evidence, recommended quizzes)가 생성되지 않는지 확인

## Dev Agent Record

### Debug Log

- 2026-06-13: RED 확인 - `python3 -m unittest tests.scoring.test_report_chunker` 실행 시 `app.scoring.report_chunker` import 실패 확인.
- 2026-06-13: GREEN 확인 - report chunker 구현 후 `python3 -m unittest tests.scoring.test_report_chunker` 7개 테스트 통과.
- 2026-06-13: 회귀 확인 - `python3 -m unittest discover -s tests` 63개 테스트 통과.

### Completion Notes

- `ReportChunk` dataclass와 `to_dict()`/`validate_report_chunk()` helper를 추가했다.
- `combine_report_text()`에서 title/content를 deterministic하게 정규화 결합하고, `chunk_daily_report()`가 안정적인 `report:0`, `report:1`, ... ID와 원문 substring 기반 `charStart`/`charEnd`를 만든다.
- Markdown heading, bullet, 빈 줄 문단, code fence, 긴 단일 문단 분할을 처리하는 순수 deterministic chunker를 추가했다.
- topic hints는 heading/backtick/slash/plus/대문자 토큰 및 로컬 topic rule로 추출하고, field hints는 `rag/text.py`의 `FIELD_KEYWORDS`, `infer_fields`, `SCORE_FIELDS` vocabulary에 맞춰 보수적으로 추출한다.
- catalog 읽기, Gemini/LLM 호출, embedding/vector store/RAG 검색, scoreDelta 생성, quiz recommender 연결은 구현하지 않았다.

## File List

- `fastapi/app/scoring/report_chunker.py`
- `fastapi/app/scoring/schemas.py`
- `fastapi/tests/scoring/test_report_chunker.py`
- `fastapi/docs/report/stories/report-2-report-chunking-topic-field-hints.md`
- `fastapi/docs/report/report-generation-sprint-status.yaml`

## Change Log

- 2026-06-13: Story 2 context document created and marked ready-for-dev.
- 2026-06-13: Implemented deterministic report chunking with topic/field hints and unit coverage; story marked review.
