---
title: Character Image 2 - Commitgotchi Image Endpoint With Local Result
status: backlog
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

backlog

## Story

As a FastAPI AI 서버 개발자,
I want `POST /api/ai/commitgotchi` to accept Spring Boot internal character-image requests and return a local image generation result,
so that Spring Boot can complete character creation with `READY` or `FALLBACK` image status even before S3 is available.

## Goal

Spring Boot가 호출할 FastAPI endpoint를 만든다. S3 bucket이 아직 없으므로 endpoint는 local storage result를 반환하는 MVP 계약으로 시작한다.

이번 story는 Story 1 core service를 HTTP endpoint에 연결한다. S3 upload와 storage adapter hardening은 Story 3에서 다룬다.

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

3. Request schema
   - 최소 필드:
     - `userId`
     - `designKeyword` 또는 `prompt`
     - optional `imageRequestId`
   - S3 bucket이 없으므로 `s3ObjectUrl`은 optional/future field로만 취급한다.
   - `designKeyword`가 있으면 canonical prompt template을 사용한다.
   - `prompt`만 있는 경우에도 Story 1 sanitizer를 거쳐 safe keyword/prompt input으로 축약하거나 fallback 처리한다.
   - request body의 file path, object key, callback URL은 storage destination으로 신뢰하지 않는다.

권장 request shape:

```json
{
  "userId": 1,
  "designKeyword": "작고 둥근 초록 슬라임",
  "prompt": null,
  "imageRequestId": "image-request-uuid",
  "s3ObjectUrl": null
}
```

4. Success response
   - Story 1 core service가 success를 반환하면 `imageStatus="READY"`로 응답한다.
   - local stored path 또는 local URL-compatible path를 포함한다.
   - `s3ObjectUrl`은 optional 또는 null/future field다.
   - image metadata를 포함한다.

권장 success response:

```json
{
  "userId": 1,
  "imageRequestId": "image-request-uuid",
  "imageStatus": "READY",
  "storageKind": "LOCAL",
  "localPath": "fastapi/runtime/data/character-images/users/1/image-request-uuid.png",
  "localUrlPath": "/runtime/character-images/users/1/image-request-uuid.png",
  "s3ObjectUrl": null,
  "spriteSheetUrl": "/runtime/character-images/users/1/image-request-uuid.png",
  "contentType": "image/png",
  "spriteMeta": {
    "columns": 3,
    "rows": 2,
    "frameMap": {
      "baby": { "happy": [0, 0], "sad": [0, 1], "angry": [0, 2] },
      "mature": { "happy": [1, 0], "sad": [1, 1], "angry": [1, 2] }
    },
    "frame": { "babyPx": 16, "maturePx": 18 },
    "transparent": true
  }
}
```

5. Fallback response
   - generation/validation/storage failure는 `imageStatus="FALLBACK"` 또는 동등 fallback classification으로 반환한다.
   - Spring Boot는 fallback response를 받으면 기본 sprite set을 배정할 수 있어야 한다.
   - error detail은 safe reason code만 반환한다.
   - API key, full prompt, image bytes, stack trace는 response에 포함하지 않는다.

권장 fallback response:

```json
{
  "userId": 1,
  "imageRequestId": "image-request-uuid",
  "imageStatus": "FALLBACK",
  "storageKind": "NONE",
  "localPath": null,
  "localUrlPath": null,
  "s3ObjectUrl": null,
  "spriteSheetUrl": null,
  "spriteMeta": null,
  "fallbackReason": "IMAGE_GENERATION_FAILED"
}
```

## Acceptance Criteria

1. FastAPI exposes `POST /api/ai/commitgotchi`.
2. endpoint는 existing FastAPI app에 등록된다.
3. endpoint는 Spring internal auth guard를 적용한다.
4. auth guard는 기존 Spring internal auth helper/config 패턴을 재사용하거나 동일 계약으로 구현한다.
5. auth 실패 시 core service는 호출되지 않는다.
6. request schema는 `userId`를 필수로 받는다.
7. request schema는 `designKeyword` 또는 `prompt` 중 하나를 받는다.
8. request schema는 optional `imageRequestId`를 받는다.
9. `s3ObjectUrl`은 optional/future field로 취급하며 required가 아니다.
10. invalid request는 validation error를 반환하고 image generation을 호출하지 않는다.
11. valid request는 Story 1 core service를 호출한다.
12. endpoint는 Spring Boot DB에 접근하지 않는다.
13. endpoint는 character record를 생성하거나 저장하지 않는다.
14. success response는 `imageStatus="READY"`를 포함한다.
15. success response는 local stored path 또는 local URL-compatible path를 포함한다.
16. success response는 `storageKind="LOCAL"` 또는 동등 local classification을 포함한다.
17. success response는 image metadata와 `contentType="image/png"`를 포함한다.
18. S3 bucket이 없으므로 success response의 `s3ObjectUrl`은 null/optional/future field로 허용된다.
19. generation failure는 `imageStatus="FALLBACK"` 또는 명확한 fallback classification을 반환한다.
20. validation failure는 `imageStatus="FALLBACK"` 또는 명확한 fallback classification을 반환한다.
21. local storage failure는 `imageStatus="FALLBACK"` 또는 명확한 fallback classification을 반환한다.
22. fallback response는 safe `fallbackReason` code를 포함한다.
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
- `designKeyword` request가 success response `READY`로 mapping되는지 검증한다.
- `prompt` only request가 sanitizer/fallback 정책에 따라 처리되는지 검증한다.
- `imageRequestId`가 없을 때 server-side id 생성 또는 nullable 정책이 schema와 일치하는지 검증한다.
- `s3ObjectUrl`이 없어도 request가 성공할 수 있는지 검증한다.
- core service success result가 HTTP response의 local path, content type, sprite metadata로 보존되는지 검증한다.
- core service fallback result가 `imageStatus="FALLBACK"` response로 보존되는지 검증한다.
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

- [ ] request/response schema 설계 (AC: 6, 7, 8, 9, 14-22)
- [ ] Spring internal auth guard 재사용 또는 endpoint용 wrapper 구현 (AC: 3, 4, 5)
- [ ] `POST /api/ai/commitgotchi` route 구현과 app 등록 (AC: 1, 2)
- [ ] Story 1 core service dependency injection 연결 (AC: 11, 26)
- [ ] success result to response mapping 구현 (AC: 14, 15, 16, 17, 18)
- [ ] fallback result to response mapping 구현 (AC: 19, 20, 21, 22)
- [ ] response/log redaction guardrail 구현 (AC: 23)
- [ ] endpoint가 Spring DB/report/quiz flow를 건드리지 않는지 확인 (AC: 12, 13, 24, 25)
- [ ] fake core service 기반 endpoint tests 작성 (AC: 26, 27, 28)

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
- core service success: `200 OK` + `imageStatus="READY"`
- generation/validation/storage failure: `200 OK` + `imageStatus="FALLBACK"`

fallback을 HTTP 5xx로 표현하면 Spring Boot character creation flow가 실패로 오인될 수 있다. 장애 관측이 필요하면 safe reason code와 metric/log classification으로 분리한다.

### Compatibility Note

Architecture §4.4의 기존 response field인 `status="OK"|"FAIL"`과 `spriteSheetUrl`은 S3 전제 계약이었다. 이번 MVP는 `imageStatus="READY"|"FALLBACK"`와 local result를 우선한다. Spring Boot 쪽 호환을 위해 구현자는 필요 시 다음 adapter mapping을 둘 수 있다.

| MVP field | Legacy-compatible meaning |
|-----------|---------------------------|
| `imageStatus="READY"` | `status="OK"` |
| `imageStatus="FALLBACK"` | `status="FAIL"` 또는 fallback success |
| `localUrlPath`/`spriteSheetUrl` | local URL-compatible sprite path |
| `s3ObjectUrl=null` | S3 unavailable |

## Dev Agent Record

### Debug Log

- 2026-06-14: Story 문서 생성. 코드 구현 없음.

### Completion Notes

- Story 2는 Story 1 core service가 준비된 뒤 구현한다.
- S3 upload는 Story 3 이후 별도 구현으로 연결한다.

## File List

- `fastapi/docs/character-image/stories/character-image-2-commitgotchi-image-endpoint-local-result.md`

## Change Log

- 2026-06-14: Story 2 생성. S3 없는 local-result endpoint MVP 계약으로 범위를 제한했다.
