---
title: Character Image 3 - Storage Adapter and S3-Ready Contract Hardening
status: backlog
created: 2026-06-14
owner: FastAPI AI 서버
epic: character-image-generation-epic
story_key: character-image-3-storage-adapter-s3-ready-contract
source_docs:
  - ../character-image-generation-epic.md
  - ../character-image-generation-sprint-status.yaml
  - ./character-image-1-gemini-sprite-generation-local-storage.md
  - ./character-image-2-commitgotchi-image-endpoint-local-result.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.4
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#7.6
---

# Character Image 3. Storage Adapter And S3-Ready Contract Hardening

## Status

backlog

## Story

As a FastAPI AI 서버 개발자,
I want local sprite storage to sit behind a stable `SpriteStorage` boundary with an S3-ready response contract,
so that the current local MVP can run without an S3 bucket while a future S3 implementation can be added without rewriting image generation or endpoint logic.

## Goal

현재 local storage를 유지하되, future S3 bucket 연결이 쉬운 storage adapter boundary와 response contract를 고정한다.

이번 story는 S3 bucket을 만들거나 실제 S3 upload를 완성하지 않는다. local implementation을 유지하면서 contract와 guardrail을 단단하게 만드는 작업이다.

## Background

Story 1은 core generation과 local save를 만든다. Story 2는 `POST /api/ai/commitgotchi` endpoint가 local result를 반환하도록 연결한다. 이후 S3 bucket이 준비되면 저장 계층만 교체하거나 추가할 수 있어야 한다.

Architecture §4.4는 `s3ObjectUrl`, `spriteSheetUrl`, `spriteMeta`를 포함한 S3/CDN 기반 계약을 가정했다. 현재는 S3가 없으므로 이 story는 그 계약을 future field로 보존하되 local MVP가 깨지지 않는 adapter boundary를 만든다.

## Scope

1. Storage protocol/interface
   - `SpriteStorage` 또는 동등 protocol/interface를 둔다.
   - image generation service는 storage implementation이 local인지 S3인지 몰라야 한다.
   - endpoint는 storage result를 response contract로 mapping한다.

권장 protocol shape:

```python
class SpriteStorage(Protocol):
    def save_sprite(
        self,
        *,
        user_id: int,
        image_request_id: str,
        image_bytes: bytes,
        content_type: str,
    ) -> SpriteStorageResult:
        ...
```

권장 result shape:

```json
{
  "storageKind": "LOCAL",
  "localPath": "fastapi/runtime/data/character-images/users/1/image-request-uuid.png",
  "localUrlPath": "/runtime/character-images/users/1/image-request-uuid.png",
  "s3ObjectUrl": null,
  "spriteSheetUrl": "/runtime/character-images/users/1/image-request-uuid.png",
  "contentType": "image/png"
}
```

2. Local implementation
   - 기존 local storage를 `SpriteStorage` boundary 뒤로 이동하거나 맞춘다.
   - storage root는 env/config로 관리한다.
   - default는 repo 내부 runtime/data 경로다.
   - safe filename/object key 생성 정책을 한 곳에 둔다.
   - path traversal을 막는다.

3. Future S3 implementation boundary
   - S3 implementation은 optional/future로 분리한다.
   - S3 config가 없어도 app import/test가 깨지지 않아야 한다.
   - boto3 client creation은 lazy 또는 factory 기반이어야 한다.
   - S3 object key 정책을 문서화한다.
   - content type은 `image/png`로 고정한다.
   - signed URL/full object URL은 logs/errors에 남기지 않는다.

권장 future S3 key policy:

```text
sprites/users/{userId}/{imageRequestId}.png
```

금지:

- `designKeyword`를 object key에 직접 포함
- raw `prompt`를 object key에 포함
- request body의 임의 `s3ObjectUrl`을 그대로 write destination으로 사용
- path traversal 가능한 `../` segment 허용

4. Response contract hardening
   - current local MVP와 future S3 response가 같은 top-level field를 유지한다.
   - `s3ObjectUrl`/object key/CDN URL은 future field로 유지한다.
   - S3가 없을 때 `s3ObjectUrl`은 null/optional이다.
   - future S3가 준비되면 `storageKind="S3"`와 `spriteSheetUrl` public/CDN URL을 반환할 수 있다.

권장 response fields:

```json
{
  "imageStatus": "READY",
  "storageKind": "LOCAL",
  "localPath": "fastapi/runtime/data/character-images/users/1/image-request-uuid.png",
  "localUrlPath": "/runtime/character-images/users/1/image-request-uuid.png",
  "s3ObjectUrl": null,
  "objectKey": null,
  "spriteSheetUrl": "/runtime/character-images/users/1/image-request-uuid.png",
  "contentType": "image/png",
  "spriteMeta": {
    "columns": 3,
    "rows": 1,
    "frameMap": {
      "joy": [0, 0],
      "happy": [0, 0],
      "sad": [0, 1],
      "angry": [0, 2]
    },
    "transparent": true
  }
}
```

5. Fallback policy
   - S3 upload failure in the future maps to `imageStatus="FALLBACK"` unless local fallback write is explicitly supported.
   - local write failure maps to `imageStatus="FALLBACK"`.
   - fallback does not expose bucket name, signed URL, full prompt, or stack trace.
   - fallback reason code is safe and bounded.

6. Git/storage guardrail
   - generated files must not enter git.
   - implementation should add `.gitignore` entry for runtime image output or document an equivalent guardrail.
   - tests should use temp directories and should not write to real runtime storage by default.

## Acceptance Criteria

1. `SpriteStorage` 또는 동등 protocol/interface가 존재한다.
2. image generation service는 concrete local/S3 implementation에 직접 의존하지 않는다.
3. local storage implementation은 `SpriteStorage` boundary를 구현한다.
4. future S3 storage implementation boundary가 local implementation과 분리되어 있다.
5. S3 config가 없어도 app import가 실패하지 않는다.
6. S3 config가 없어도 local storage tests가 실패하지 않는다.
7. S3 dependency/client creation은 lazy/factory 기반이거나 optional path로 격리된다.
8. storage root는 env/config로 관리된다.
9. local storage default는 repo 내부 runtime/data 경로다.
10. local storage는 path traversal을 방지한다.
11. filename/object key는 server-controlled `imageRequestId`, UUID, hash 등으로 생성된다.
12. `designKeyword`와 raw `prompt`는 filename/object key에 포함되지 않는다.
13. future S3 object key policy가 문서화된다.
14. content type은 `image/png`로 고정된다.
15. response contract는 local MVP와 future S3에서 같은 top-level field를 유지한다.
16. S3가 없을 때 `s3ObjectUrl`은 null/optional field로 허용된다.
17. future `objectKey`/CDN URL/`s3ObjectUrl` 정책이 문서화된다.
18. S3 upload failure policy가 fallback 관점에서 문서화된다.
19. local write failure는 fallback result로 분류된다.
20. fallback reason code는 safe bounded value만 사용한다.
21. generated files가 git에 들어가지 않도록 `.gitignore` 또는 docs guardrail이 있다.
22. tests는 temp directory/fake storage/fake S3 client를 사용한다.
23. tests는 실제 S3를 호출하지 않는다.
24. tests는 actual Gemini image generation을 호출하지 않는다.
25. logs/errors/test snapshots에는 secret, API key, signed URL, full prompt, image bytes가 노출되지 않는다.
26. report SQS consumer와 quiz grading endpoint/callback은 변경하지 않는다.
27. FastAPI는 Spring Boot DB에 접근하지 않는다.

## Test Requirements

필수 테스트:

- core service가 `SpriteStorage` fake를 통해 save를 호출하는지 검증한다.
- local storage result가 stable response fields로 mapping되는지 검증한다.
- S3 config가 비어 있어도 app import와 local storage tests가 통과하는지 검증한다.
- storage root 밖으로 나가는 path가 거부되는지 검증한다.
- `../`, absolute path, path separator가 포함된 malicious `imageRequestId`가 safe filename으로 처리되거나 rejected 되는지 검증한다.
- `designKeyword`가 filename/object key에 포함되지 않는지 검증한다.
- content type이 `image/png`가 아니면 rejected 또는 normalized 되는지 검증한다.
- local write failure가 `FALLBACK` result로 mapping되는지 검증한다.
- fake S3 client failure가 future S3 adapter에서 fallback classification으로 mapping되는지 검증한다.
- generated files를 실제 repo runtime directory에 남기지 않도록 temp directory를 사용하는지 검증한다.
- logs/errors/test snapshots에 secret, signed URL, full prompt, image bytes가 없는지 검증한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.image.test_sprite_storage_contract tests.image.test_local_storage
```

전체 회귀 확인:

```bash
cd fastapi
python3 -m unittest discover -s tests
```

## Guardrails

- 이번 story는 실제 S3 bucket 생성, IAM policy, CDN 설정을 하지 않는다.
- request body의 `s3ObjectUrl`을 write destination으로 그대로 신뢰하지 않는다.
- future S3 URL이 signed URL인 경우 full URL을 logs/errors/test snapshots에 남기지 않는다.
- bucket name, object key, signed URL은 redaction 대상이다.
- local runtime files는 source control에 들어가지 않는다.
- test fixtures는 작은 deterministic PNG만 사용하고 generated production bytes를 fixture로 커밋하지 않는다.
- FastAPI는 Spring Boot DB에 접근하지 않는다.
- report/quiz flow는 수정하지 않는다.

## Tasks / Subtasks

- [ ] `SpriteStorage` protocol/interface와 `SpriteStorageResult` model 설계 (AC: 1, 15)
- [ ] core service가 storage protocol에만 의존하도록 조정 (AC: 2)
- [ ] local storage implementation을 protocol boundary에 맞춤 (AC: 3, 8, 9)
- [ ] path traversal 방지와 safe filename generation 구현 (AC: 10, 11, 12)
- [ ] future S3 adapter boundary와 lazy/factory config 설계 (AC: 4, 5, 6, 7)
- [ ] future S3 object key/CDN URL/`s3ObjectUrl` policy 문서화 (AC: 13, 16, 17)
- [ ] `image/png` content type policy 구현 (AC: 14)
- [ ] local/S3 failure to fallback mapping 구현 또는 문서화 (AC: 18, 19, 20)
- [ ] generated files git guardrail 추가 또는 문서화 (AC: 21)
- [ ] temp directory/fake storage/fake S3 tests 작성 (AC: 22, 23, 24)
- [ ] logging/error redaction tests 작성 (AC: 25)
- [ ] Spring DB/report/quiz flow 미변경 확인 (AC: 26, 27)

## Dev Notes

### Recommended File Structure

구현 후보:

- `fastapi/app/image/storage.py`
- `fastapi/app/image/local_storage.py`
- `fastapi/app/image/s3_storage.py`
- `fastapi/app/image/schemas.py`
- `fastapi/app/image/sprite_service.py`
- `fastapi/app/config.py`
- `fastapi/.gitignore` 또는 repo root `.gitignore`

테스트 후보:

- `fastapi/tests/image/test_sprite_storage_contract.py`
- `fastapi/tests/image/test_local_storage.py`
- `fastapi/tests/image/test_s3_storage_contract.py`

### Future S3 Contract

S3가 준비된 뒤의 권장 mapping:

| Field | Local MVP | Future S3 |
|-------|-----------|-----------|
| `storageKind` | `LOCAL` | `S3` |
| `localPath` | runtime path | null |
| `localUrlPath` | local URL-compatible path | null |
| `s3ObjectUrl` | null | `s3://bucket/sprites/users/{userId}/{imageRequestId}.png` |
| `objectKey` | null | `sprites/users/{userId}/{imageRequestId}.png` |
| `spriteSheetUrl` | local URL-compatible path | CDN/public URL |
| `contentType` | `image/png` | `image/png` |

### Fallback Reason Codes

권장 reason code:

- `LOCAL_STORAGE_FAILED`
- `S3_STORAGE_UNAVAILABLE`
- `S3_UPLOAD_FAILED`
- `INVALID_STORAGE_KEY`
- `UNSUPPORTED_CONTENT_TYPE`
- `STORAGE_CONTRACT_ERROR`

## Dev Agent Record

### Debug Log

- 2026-06-14: Story 문서 생성. 코드 구현 없음.

### Completion Notes

- Story 3은 local MVP를 유지하면서 S3-ready contract를 고정하는 hardening story다.
- 실제 S3 연결은 bucket/IAM/CDN 준비 후 별도 story 또는 후속 구현으로 다룬다.

## File List

- `fastapi/docs/character-image/stories/character-image-3-storage-adapter-s3-ready-contract.md`

## Change Log

- 2026-06-14: Story 3 생성. Storage adapter boundary와 S3-ready response contract를 문서화했다.
