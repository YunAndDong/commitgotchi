---
title: Character Image 2 - Commitgotchi Image Endpoint With Local Result
status: review
created: 2026-06-14
owner: FastAPI AI 서버
epic: character-image-generation-epic
story_key: character-image-2-commitgotchi-image-endpoint-local-result
source_docs:
  - ../character-image-generation-epic.md
  - ../character-image-generation-sprint-status.yaml
  - ./character-image-1-gemini-sprite-generation-local-storage.md
  - ../../integration/integration-contracts-epic.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.4
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#8.3
---

# Character Image 2. Commitgotchi Image Endpoint With Local Result

## Status

review

## Story

As a FastAPI AI 서버 개발자,
I want `POST /api/ai/commitgotchi` to accept Spring Boot internal character-image requests and return a local image generation result,
so that Spring Boot can complete character creation with `READY` or `FALLBACK` image status even before S3 is available.

## Goal

Spring Boot가 호출할 FastAPI endpoint를 만든다. S3 bucket이 아직 없으므로 endpoint는 local storage result를 반환하는 MVP 계약으로 시작한다.

이번 story는 Story 1 core service를 HTTP endpoint에 연결한다. S3 upload와 storage adapter hardening은 Story 3에서 다룬다.

## Contract Decision (2026-06-23): Option A — architecture §4.4 호환 응답

> **확정:** 응답 계약은 architecture §4.4의 `status: "OK"|"FAIL"` + `spriteSheetUrl` + `spriteMeta` shape를 따른다.
> Spring Boot `FastApiCharacterImageClient`가 이미 §4.4 계약(`status == "OK"`, non-empty `spriteSheetUrl`,
> `spriteMeta.frameMap`의 `joy/sad/angry` 좌표)을 기대하므로, **Spring 클라이언트는 수정하지 않고 FastAPI가 호환 응답을 낸다.**
>
> - 요청 본문은 Spring이 보내는 `{ "userId", "s3ObjectUrl", "prompt" }`를 그대로 받는다(§4.4).
> - S3가 아직 없으므로 MVP에서는 **로컬에 저장**하고, `spriteSheetUrl`은 로컬 URL 호환 경로로 채운다.
>   `s3ObjectUrl`은 요청 값을 그대로 echo하거나 null로 둔다. 실제 S3 업로드는 Story 3.
> - 실패 시 `status: "FAIL"` + `spriteSheetUrl: null` + `spriteMeta: null` + safe `errorMessage`.
> - 아래 문서의 `imageStatus="READY"|"FALLBACK"` 표기는 이 결정으로 폐기되었다. `status="OK"|"FAIL"`로 읽는다.

## Background

Architecture §4.4의 원래 계약은 Spring Boot가 `POST /api/ai/commitgotchi`를 호출하고 FastAPI가 S3에 저장한 sprite sheet URL을 반환하는 흐름을 가정했다. 현재는 S3 bucket이 준비되지 않았으므로, 이번 story는 Spring Boot가 통합을 시작할 수 있는 local-result contract를 먼저 제공한다.

중요한 계약:

- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- FastAPI는 character record를 생성하거나 저장하지 않는다.
- FastAPI는 이미지 생성, 검증, local 저장, 결과 응답만 책임진다.
- image generation 실패는 캐릭터 생성 자체를 막지 않는다.
- report SQS consumer와 quiz grading endpoint/callback은 건드리지 않는다.

## Scope

1. Route
   - FastAPI exposes `POST /api/ai/commitgotchi`.
   - router 파일명 후보: `fastapi/app/api/commitgotchi.py`
   - app registration 후보: `fastapi/app/main.py`

2. Internal auth guard
   - 기존 Spring internal auth helper/config 패턴을 재사용한다.
   - 권장 header 계약은 integration epic의 `Authorization: Internal <SPRING_INTERNAL_API_SECRET>` 계열과 맞춘다.
   - local/dev에서 secret이 비어 있을 때의 동작은 기존 integration config 정책을 따른다.
   - auth failure는 image generation core service를 호출하지 않는다.

3. Request schema (§4.4 호환, Option A)
   - 필수 필드 (Spring이 보내는 §4.4 본문):
     - `userId`
     - `prompt`
     - `s3ObjectUrl` (MVP에서는 저장 destination으로 신뢰하지 않고, echo/무시. 로컬 저장으로 대체)
   - `prompt`는 Story 1 sanitizer를 거쳐 safe input으로 축약하거나 fallback 처리한다.
   - request body의 `s3ObjectUrl`, file path, object key, callback URL은 **로컬 저장 destination으로 신뢰하지 않는다.**
     (S3 업로드는 Story 3에서 검증된 어댑터로만 처리)

권장 request shape (§4.4):

```json
{
  "userId": 1,
  "s3ObjectUrl": "s3://commitgotchi-sprites/characters/42/sprite-sheet.png",
  "prompt": "A retro Tamagotchi character design sheet ... design keyword \"작고 둥근 초록 슬라임\" ... --ar 3:1"
}
```

4. Success response (§4.4 호환)
   - Story 1 core service가 success를 반환하면 `status="OK"`로 응답한다.
   - `spriteSheetUrl`은 local URL-compatible path로 채운다(S3 전까지). Spring은 이 값을 non-empty로 요구한다.
   - `s3ObjectUrl`은 요청 값 echo 또는 null.
   - `spriteMeta`는 `frameMap`에 `joy/sad/angry` 좌표를 반드시 포함한다(Spring 검증 대상).

권장 success response (§4.4):

```json
{
  "userId": 1,
  "status": "OK",
  "s3ObjectUrl": "s3://commitgotchi-sprites/characters/42/sprite-sheet.png",
  "spriteSheetUrl": "/runtime/character-images/characters/42/sprite-sheet.png",
  "spriteMeta": {
    "columns": 3,
    "rows": 1,
    "frameMap": {
      "joy": [0, 0],
      "sad": [0, 1],
      "angry": [0, 2]
    },
    "transparent": true
  }
}
```

5. Fallback response (§4.4 호환)
   - generation/validation/storage failure는 `status="FAIL"`로 반환한다.
   - Spring Boot는 `status != "OK"`를 받으면 기본 sprite set을 배정한다(FR-16). 즉 실패도 캐릭터 생성을 막지 않는다.
   - `spriteSheetUrl`/`spriteMeta`는 null, `errorMessage`는 safe reason code만.
   - API key, full prompt, image bytes, stack trace는 response에 포함하지 않는다.

권장 fallback response (§4.4):

```json
{
  "userId": 1,
  "status": "FAIL",
  "s3ObjectUrl": "s3://commitgotchi-sprites/characters/42/sprite-sheet.png",
  "spriteSheetUrl": null,
  "spriteMeta": null,
  "errorMessage": "IMAGE_GENERATION_FAILED"
}
```

## Acceptance Criteria

1. FastAPI exposes `POST /api/ai/commitgotchi`.
2. endpoint는 existing FastAPI app에 등록된다.
3. endpoint는 Spring internal auth guard를 적용한다.
4. auth guard는 기존 Spring internal auth helper/config 패턴을 재사용하거나 동일 계약으로 구현한다.
5. auth 실패 시 core service는 호출되지 않는다.
6. request schema는 `userId`를 필수로 받는다.
7. request schema는 `prompt`를 받는다(§4.4).
8. request schema는 `s3ObjectUrl`을 받되, 로컬 저장 destination으로는 신뢰하지 않는다.
9. (S3 미사용) `s3ObjectUrl`은 echo/무시 대상이며 실제 업로드는 Story 3에서 처리한다.
10. invalid request는 validation error를 반환하고 image generation을 호출하지 않는다.
11. valid request는 Story 1 core service를 호출한다.
12. endpoint는 Spring Boot DB에 접근하지 않는다.
13. endpoint는 character record를 생성하거나 저장하지 않는다.
14. success response는 `status="OK"`를 포함한다.
15. success response의 `spriteSheetUrl`은 local URL-compatible path로 non-empty다(Spring 검증 통과).
16. success response의 `s3ObjectUrl`은 요청 값 echo 또는 null이다.
17. success response의 `spriteMeta.frameMap`은 `joy/sad/angry` 좌표를 포함한다(Spring 검증 대상).
18. S3 bucket이 없으므로 실제 S3 업로드는 하지 않고 로컬에 저장한다.
19. generation failure는 `status="FAIL"`로 반환한다.
20. validation failure는 `status="FAIL"`로 반환한다.
21. local storage failure는 `status="FAIL"`로 반환한다.
22. fallback(`status="FAIL"`) response는 safe `errorMessage` code를 포함하고 `spriteSheetUrl`/`spriteMeta`는 null이다.
23. response에는 secret, API key, generated image bytes, full prompt, stack trace가 포함되지 않는다.
24. endpoint는 report SQS consumer를 수정하지 않는다.
25. endpoint는 quiz grading endpoint/callback을 수정하지 않는다.
26. endpoint tests는 fake core service/fake auth config만 사용한다.
27. endpoint tests는 실제 Gemini API를 호출하지 않는다.
28. endpoint tests는 실제 S3를 호출하지 않는다.

## Test Requirements

필수 테스트:

- auth header가 없거나 invalid이면 401/403 계열 응답을 반환하고 core service가 호출되지 않는지 검증한다.
- valid auth + valid request이면 core service가 정확한 input으로 호출되는지 검증한다.
- `prompt` request가 success response `status="OK"` + non-empty `spriteSheetUrl`로 mapping되는지 검증한다.
- `prompt`가 sanitizer/fallback 정책에 따라 처리되는지 검증한다.
- 요청의 `s3ObjectUrl`을 로컬 저장 경로로 신뢰하지 않는지(echo/무시) 검증한다.
- core service success result가 HTTP response의 `spriteSheetUrl`(local path), `spriteMeta`(joy/sad/angry)로 보존되는지 검증한다.
- core service fallback result가 `status="FAIL"` + null `spriteSheetUrl`/`spriteMeta` response로 보존되는지 검증한다.
- Spring 클라이언트 호환: 응답이 `status`/`spriteSheetUrl`/`spriteMeta.frameMap(joy,sad,angry)` 필드를 가지는지 검증한다.
- invalid request는 core service를 호출하지 않는지 검증한다.
- response/log snapshot에 secret, full prompt, generated image bytes가 없는지 검증한다.
- endpoint test가 fake service를 사용하며 실제 Gemini/S3 호출을 하지 않는지 검증한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.image.test_commitgotchi_endpoint
```

전체 회귀 확인:

```bash
cd fastapi
python3 -m unittest discover -s tests
```

## Guardrails

- request body의 `s3ObjectUrl`, `callbackUrl`, file path, object key는 local storage destination으로 신뢰하지 않는다.
- `designKeyword`나 `prompt`를 filename/path segment에 직접 쓰지 않는다.
- `prompt`가 제공되어도 full prompt를 logs/errors에 남기지 않는다.
- FastAPI는 Spring Boot DB에 접근하지 않는다.
- endpoint 구현 중 `POST /api/report`, `POST /api/internal/quizzes/grade`, report SQS consumer를 변경하지 않는다.
- 실패 응답은 캐릭터 생성 중단을 의미하지 않는다. Spring Boot가 기본 sprite를 사용할 수 있게 fallback classification을 반환한다.

## Tasks / Subtasks

- [x] request/response schema 설계 (AC: 6, 7, 8, 9, 14-22)
- [x] Spring internal auth guard 재사용 또는 endpoint용 wrapper 구현 (AC: 3, 4, 5)
- [x] `POST /api/ai/commitgotchi` route 구현과 app 등록 (AC: 1, 2)
- [x] Story 1 core service dependency injection 연결 (AC: 11, 26)
- [x] success result to response mapping 구현 (AC: 14, 15, 16, 17, 18)
- [x] fallback result to response mapping 구현 (AC: 19, 20, 21, 22)
- [x] response/log redaction guardrail 구현 (AC: 23)
- [x] endpoint가 Spring DB/report/quiz flow를 건드리지 않는지 확인 (AC: 12, 13, 24, 25)
- [x] fake core service 기반 endpoint tests 작성 (AC: 26, 27, 28)

## Dev Notes

### Recommended File Structure

구현 후보:

- `fastapi/app/api/commitgotchi.py`
- `fastapi/app/main.py`
- `fastapi/app/image/schemas.py`
- `fastapi/app/image/sprite_service.py`
- `fastapi/app/integration/spring_client.py` 또는 기존 internal auth helper

테스트 후보:

- `fastapi/tests/image/test_commitgotchi_endpoint.py`

### HTTP Status Policy

권장 정책:

- schema/auth 실패: HTTP error
- core service success: `200 OK` + `status="OK"`
- generation/validation/storage failure: `200 OK` + `status="FAIL"`

fallback을 HTTP 5xx로 표현하면 Spring Boot character creation flow가 실패로 오인될 수 있다. 장애 관측이 필요하면 safe reason code와 metric/log classification으로 분리한다.

### Compatibility Note (Option A 확정)

Architecture §4.4의 response field `status="OK"|"FAIL"` + `spriteSheetUrl` + `spriteMeta`를 **그대로 따른다**.
Spring Boot `FastApiCharacterImageClient`가 이 shape를 이미 파싱하므로 Spring 쪽 변경은 없다.
S3 미가용 상태이므로 MVP에서는 `spriteSheetUrl`을 local URL-compatible path로, `s3ObjectUrl`은 echo/null로 둔다.
실제 S3 업로드는 Story 3에서 storage adapter로 추가하며, 그때도 이 응답 shape는 유지된다.

| §4.4 field | MVP(local) 채움 |
|------------|------------------|
| `status="OK"` | core service 성공 |
| `status="FAIL"` | 생성/검증/저장 실패(Spring이 기본 sprite 사용) |
| `spriteSheetUrl` | local URL-compatible sprite path |
| `s3ObjectUrl` | 요청 값 echo 또는 null (S3 unavailable) |
| `spriteMeta.frameMap` | `joy/sad/angry` 좌표 필수 |

## Dev Agent Record

### Debug Log

- 2026-06-14: Story 문서 생성. 코드 구현 없음.
- 2026-06-23: `tests.image.test_commitgotchi_endpoint` 8 tests pass. 전체 스위트 `unittest discover -s tests` 247 tests pass.
- 2026-06-23: report consumer guardrail test가 `/api/ai/commitgotchi` 부재를 단언하고 있어, 본 엔드포인트 도입에 맞춰 `/api/report` 불변식만 남기고 갱신.
- 2026-06-23: Spring `FastApiCharacterImageClientTest` 기대 계약(요청 `{userId, s3ObjectUrl, prompt}`, 응답 `status:"OK"` + `spriteSheetUrl` + `spriteMeta.frameMap{joy,sad,angry}`)과 응답 shape 일치 확인.

### Completion Notes

- Option A(§4.4 호환)로 `POST /api/ai/commitgotchi` 구현. 요청 `prompt`(=design keyword)를 story-1 `generate_commitgotchi_sprite(design_keyword=...)`에 전달.
- 응답은 `status="OK"|"FAIL"` + `spriteSheetUrl`(MVP: 로컬 경로) + `spriteMeta`(joy/sad/angry). `s3ObjectUrl`은 echo만 하고 저장 destination으로 신뢰하지 않음.
- 내부 인증은 quiz grading과 동일 패턴(`Authorization: Internal <secret>`, `hmac.compare_digest`)을 재사용. blank secret이면 local dev 통과.
- 실제 S3 업로드는 Story 3에서 storage adapter로 추가(응답 shape 유지). quiz grading / report consumer / Spring 코드는 변경하지 않음.

## File List

- `fastapi/app/api/commitgotchi.py` (신규)
- `fastapi/app/main.py` (router 등록)
- `fastapi/tests/image/test_commitgotchi_endpoint.py` (신규)
- `fastapi/tests/integration/test_report_consumer.py` (guardrail 갱신)
- `fastapi/docs/character-image/character-image-generation-sprint-status.yaml` (status review)
- `fastapi/docs/character-image/stories/character-image-2-commitgotchi-image-endpoint-local-result.md`

## Change Log

- 2026-06-14: Story 2 생성. S3 없는 local-result endpoint MVP 계약으로 범위를 제한했다.
- 2026-06-23: **Contract Decision Option A 확정.** 응답 계약을 architecture §4.4 호환(`status="OK"|"FAIL"` + `spriteSheetUrl` + `spriteMeta`)으로 고정. Spring `FastApiCharacterImageClient` 무수정. 요청은 §4.4 `{userId, s3ObjectUrl, prompt}` 수신. S3 미가용으로 로컬 저장 + local URL `spriteSheetUrl`, 실제 S3 업로드는 Story 3. 기존 `imageStatus="READY"|"FALLBACK"` 표기 폐기.
