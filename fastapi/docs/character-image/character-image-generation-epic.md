---
title: FastAPI Character Image Generation Epic
status: in-progress
created: 2026-06-14
updated: 2026-06-14
owner: FastAPI AI 서버
scope: Gemini 스프라이트시트 생성, FastAPI 이미지 endpoint, local storage MVP, S3-ready storage boundary
aliases:
  - Character Image Generation
  - Commitgotchi Sprite Generation
related_docs:
  - fastapi/docs/character-image/character-image-generation-sprint-status.yaml
  - fastapi/docs/integration/integration-contracts-epic.md
  - fastapi/docs/report/report-generation-epic.md
  - _bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md
  - _bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/addendum.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.4
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#7.6
---

# FastAPI Character Image Generation Epic

## 1. 목적

Spring Boot가 캐릭터 생성 흐름에서 FastAPI `POST /api/ai/commitgotchi`를 호출할 수 있도록, Commitgotchi 캐릭터 스프라이트시트 이미지 생성 기능을 별도 epic으로 설계한다.

이번 epic은 먼저 Gemini image generation으로 1x3 투명 PNG 스프라이트 atlas를 만들고 FastAPI 내부 local directory에 저장하는 MVP를 완성한다. S3 bucket은 아직 준비되어 있지 않으므로 실제 S3 upload는 구현하지 않는다. 대신 future S3 전환이 쉽도록 storage adapter/contract 경계를 문서와 story에 고정한다.

FastAPI의 책임은 이미지 생성, 검증, 저장, 결과 응답이다. 캐릭터 생성과 캐릭터 record의 system of record는 Spring Boot이며, FastAPI는 Spring Boot DB에 직접 접근하지 않는다.

## 2. 현재 기준

완료된 것으로 간주한다.

- report generation epic 구현
- Spring Boot/FastAPI integration contracts epic 구현
- quiz grading endpoint/callback 구현
- report SQS consumer/callback 구현

아직 미구현이다.

- 캐릭터 이미지 생성 core service
- Gemini image model config와 prompt template
- `designKeyword`/사용자 prompt sanitization
- 투명 PNG sprite sheet validation
- FastAPI `POST /api/ai/commitgotchi`
- local image storage와 runtime path guardrail
- S3-ready storage adapter boundary

## 3. 핵심 원칙

- Spring Boot가 캐릭터 생성의 system of record다.
- FastAPI는 캐릭터를 생성하거나 저장하지 않는다. FastAPI가 저장하는 것은 생성된 sprite image artifact뿐이다.
- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- 이미지 생성 실패는 캐릭터 생성 자체를 막지 않는다.
- 실패 시 Spring Boot가 동일 1x3 레이아웃의 기본 fallback sprite를 사용할 수 있도록 명확한 fallback result를 반환한다.
- S3 bucket이 준비될 때까지 FastAPI는 local runtime directory에 PNG를 저장한다.
- S3 upload, S3 bucket 생성, CDN 연결, infra provisioning은 이번 epic의 직접 구현 범위가 아니다.
- Gemini/S3 실제 호출 테스트는 금지한다. 모든 unit/integration test는 fake/mock 기반이어야 한다.
- prompt 원문 전체, secret, API key, signed URL, generated image bytes는 logs/errors/test fixtures에 남기지 않는다.
- report SQS consumer, quiz grading endpoint/callback, report callback 계약은 수정하지 않는다.

## 4. Canonical Prompt Template

Story 1의 core service는 사용자 입력을 정규화한 `designKeyword`만 아래 template에 안전하게 주입한다.

```text
Create a pixel-art creature sprite sheet for Commit-gotchi, a cozy retro virtual pet game.

Draw exactly three pixel-art creatures laid out as a 1 row by 3 columns horizontal sprite sheet.
Frame order from left to right: joy, sad, angry.

These three sprites are the post-hatch evolved character only.
Do NOT draw an egg, cracked egg, baby form, pre-hatch form, evolution sequence, before/after comparison, or any second row.

All three sprites share the same creature identity based on this design keyword: "{designKeyword}".
Use cute old-game pixel art, deep navy or near-black outlines, a limited pastel palette, a solid magenta chroma-key background, no text, no labels, no grid lines, no panels, no UI.
```

Prompt handling rules:

- `designKeyword`는 길이 제한, control character 제거, whitespace normalization, unsafe filename 문자 제거와 별개로 prompt injection 방어를 위한 plain text escaping을 거친다.
- 사용자가 준 raw prompt를 그대로 log하거나 model call에 넘기지 않는다.
- model config는 env/config로 관리한다. 기본 후보는 배포 시점에 확인된 image generation 가능한 작은/빠른 Gemini 계열 모델이며, 테스트에서는 사용하지 않는다.

## 5. Story 목록

권장 구현 순서:

1. Gemini Sprite Generation Core + Local Storage
2. FastAPI `POST /api/ai/commitgotchi` Endpoint With Local Result
3. Storage Adapter And S3-Ready Contract Hardening

---

## Story 1. Gemini Sprite Generation Core + Local Storage

### 목표

사용자 디자인 키워드를 canonical prompt template에 주입하고, Gemini image model client abstraction을 통해 1x3 transparent PNG sprite atlas bytes를 생성한 뒤 FastAPI 내부 local directory에 저장하는 core service를 설계한다.

### 구현 범위

- Gemini image model env/config
- prompt template과 `designKeyword` sanitization
- fake 주입 가능한 image generation client abstraction
- PNG bytes 처리
- PNG validation
  - PNG signature
  - non-empty bytes
  - image dimensions
  - alpha channel 존재 여부
- local runtime directory 저장
- safe filename/object key 생성
- fallback result classification
- fake image client와 fake storage 기반 테스트

### 제외 범위

- FastAPI endpoint
- Spring Boot DB 접근
- Spring Boot character CRUD
- 실제 Gemini 호출 테스트
- 실제 S3 호출 테스트
- S3 adapter formalization
- report/quiz flow 변경

---

## Story 2. FastAPI `POST /api/ai/commitgotchi` Endpoint With Local Result

### 목표

Spring Boot가 호출할 FastAPI endpoint를 만든다. S3가 아직 없으므로 response는 local storage result를 반환하는 MVP 계약으로 시작한다.

### 구현 범위

- `POST /api/ai/commitgotchi`
- Spring internal auth guard 재사용
- request schema
  - `userId`
  - `designKeyword` 또는 `prompt`
  - optional `imageRequestId`
- Story 1 core service 호출
- success response
  - `imageStatus="READY"`
  - local stored path 또는 local URL-compatible path
  - image metadata
- failure/fallback response
  - `imageStatus="FALLBACK"`
  - fallback reason classification
- fake core service 기반 endpoint tests

### 제외 범위

- S3 upload
- Spring Boot DB 접근
- Spring Boot character CRUD
- report SQS consumer 변경
- quiz grading endpoint/callback 변경
- 실제 Gemini/S3 호출 테스트

---

## Story 3. Storage Adapter And S3-Ready Contract Hardening

### 목표

현재 local storage를 유지하면서, future S3 bucket 연결이 쉬운 storage adapter boundary와 response contract를 고정한다.

### 구현 범위

- `SpriteStorage` 또는 동등 protocol/interface
- local storage implementation과 future S3 implementation boundary 분리
- S3 dependency/config가 없어도 app import/test가 깨지지 않는 구조
- `s3ObjectUrl`/object key/CDN URL 정책 문서화
- S3 upload 실패 시 fallback 정책 문서화
- path traversal 방지
- safe filename/object key generation
- `image/png` content type 정책
- generated files git tracking 방지 guardrail
- secret/API key/signed URL/full prompt/image bytes 로그 노출 방지 테스트 요구사항

### 제외 범위

- 실제 S3 bucket 생성
- 실제 S3 upload 구현 완료
- CDN 배포
- Spring Boot character CRUD
- report/quiz flow 변경

## 6. Epic 완료 기준

- `designKeyword`가 canonical prompt template에 안전하게 주입된다.
- Gemini image model 이름, timeout, retry limit 등 model 호출 설정이 env/config로 관리된다.
- image generation client는 fake/mock 주입이 가능하다.
- core service는 생성 결과를 PNG bytes로 처리한다.
- PNG validation은 최소 PNG 여부, non-empty bytes, dimensions, alpha channel 존재 여부를 확인한다.
- local 저장 경로는 repo 내부 `fastapi/runtime/data/character-images/` 같은 runtime/data 경로로 제한된다.
- generated image artifact가 git에 들어가지 않도록 `.gitignore` 또는 문서 guardrail이 명확하다.
- `POST /api/ai/commitgotchi`가 Spring Boot에서 호출 가능한 request/response schema를 가진다.
- S3 bucket이 없어도 endpoint success response가 local result를 반환할 수 있다.
- generation/validation/storage failure는 `FALLBACK` classification으로 반환되어 캐릭터 생성 흐름을 막지 않는다.
- storage adapter boundary가 local/S3 구현을 분리한다.
- 실제 Gemini/S3 호출 없는 fake/mock 테스트 요구사항이 각 story에 반영된다.
- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- report SQS consumer와 quiz grading endpoint/callback은 변경하지 않는다.

## 7. Guardrails

- Unit/integration tests는 실제 Gemini API, 실제 S3, 실제 Spring Boot 서버를 호출하지 않는다.
- logs/errors/test snapshots에 API key, secret, signed URL, prompt 원문 전체, generated image bytes를 남기지 않는다.
- `designKeyword`와 optional `prompt`는 loggable summary로만 축약한다.
- user-provided text는 파일명, object key, path segment에 직접 사용하지 않는다.
- local storage는 configured runtime root 밖으로 write할 수 없어야 한다.
- FastAPI endpoint는 Spring Boot internal caller만 허용한다.
- response contract는 Spring Boot가 `READY`와 `FALLBACK`을 안정적으로 구분할 수 있어야 한다.
- fallback sprite는 동일 1x3 레이아웃을 유지해야 하며, 프런트 렌더링 계약을 바꾸지 않는다.

## 8. Dev Agent Record

### Debug Log

- 2026-06-14: Epic/story 문서 생성. 코드 구현 없음.

### Completion Notes

- 이 epic은 planning artifact다.
- 구현자는 각 story의 Acceptance Criteria와 Test Requirements를 기준으로 개발을 시작한다.

## 9. Change Log

- 2026-06-14: Character image generation epic 생성. S3 미준비 현실 제약을 반영해 local storage MVP와 S3-ready contract를 분리했다.
