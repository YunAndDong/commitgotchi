---
title: 캐릭터 이미지 품질 고도화 Epic (한 번에 규격 충족)
status: in-progress
created: 2026-06-17
owner: FastAPI AI 서버
scope: fastapi/ 하위 이미지 생성 후처리. 흐름 C(POST /api/ai/commitgotchi) 계약 준수, RAG와 무관
related_docs:
  - fastapi/docs/character-image/character-image-generation-epic.md
  - fastapi/docs/differ/character-image/transparent-png-alpha-background.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.4
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#7.6
---

# 캐릭터 이미지 품질 고도화 Epic

## 1. 목적

현재 캐릭터 이미지 생성은 Gemini 결과를 **검증해서 거르기만** 한다. 그래서 배경이 진짜 투명이 아니거나(체커보드를 픽셀로 구워옴) 셀 정렬이 어긋나면 그대로 `FALLBACK`이 되어, 보기엔 멀쩡한 스프라이트도 버려진다(`differ/character-image/transparent-png-alpha-background.md`).

이 epic의 목표는 **거르기를 "규격에 맞게 교정하기"로 바꿔, 흐름 C 계약에 맞는 1×3 투명 PNG 스프라이트 atlas를 안정적으로 산출**하는 것이다. 프런트가 `spriteMeta`로 셀을 잘라 쓰는 계약을 깨지 않는 게 전제다.

한 줄: *생성 결과를 결정적 후처리로 "진짜 알파 + 잘라 쓸 수 있는 격자"로 정규화한 뒤에만 `READY`로 저장한다.*

## 2. 현재 기준 (왜 한 번에 안 되나)

| # | 문제 | 위치 | 효과 |
| --- | --- | --- | --- |
| 1 | 배경이 진짜 투명이 아님 | Gemini 산출물 | 체커보드/단색 배경이 픽셀로 구워져 옴 → `MISSING_ALPHA_CHANNEL` |
| 2 | 알파를 "만들지" 않고 "거르기"만 함 | `sprite_service.py` `validate_transparent_png` | 후처리 없이 바로 검증 → 보기 멀쩡해도 FALLBACK |
| 3 | 셀 정렬 미검증 | `png_validation.py` | `width%3`,`프레임≥18px`만 확인. 셀 안에 스프라이트가 균일 정렬됐는지는 안 봄 → 프런트 슬라이싱이 깨질 수 있음 |
| 4 | Pillow 미보유 | `requirements.txt` | 이미지 후처리 라이브러리가 없음 |

데이터/환경:

- 모델: `gemini-2.5-flash-image` (`image/config.py`)
- 저장: 현재 local runtime MVP(`fastapi/runtime/data/character-images/`), S3는 후속(흐름 C 계약은 유지)
- 검증기 계약: 진짜 알파 채널 필수, `READY`만 저장. 이 안전규칙은 유지한다.

## 3. 핵심 원칙 (계약 준수)

- **흐름 C 계약 준수.** `POST /api/ai/commitgotchi` 요청/응답 shape, `spriteMeta`(columns=3, rows=1, frameMap, transparent), 3프레임 1×3 레이아웃을 유지한다.
- **안전규칙 유지.** 진짜 알파 PNG만 `READY`로 저장. 후처리는 **알파를 만들어 통과**시키는 것이지 검증을 약화하지 않는다.
- **결정적 후처리 우선.** 외부 모델 의존(2차 배경제거 모델)보다 결정적·로컬 후처리를 1순위로. 같은 입력 → 같은 출력.
- **실패는 흐름을 막지 않는다.** 후처리/정규화 실패 시 기존 `FALLBACK` 분류를 그대로 반환한다(캐릭터 생성은 성공, 기본 스프라이트 대체).
- **테스트는 fake/합성 이미지로.** 실제 Gemini/S3 호출 금지(기존 epic Guardrails 승계).
- **시크릿/프롬프트 원문/이미지 바이트 비노출.** loggable summary만.

## 4. 규격 정합의 어려운 지점 (계약 재협상 레버)

현재 계약은 AI가 부화 후/진화 후 캐릭터의 감정 3종만 생성하고, 진화 전/pre-hatch 알 이미지는 정적 스프라이트를 사용한다. 생성 모델은 3개 스프라이트를 픽셀 단위 균일 격자와 3:1 캔버스에 정확히 안 놓는다. 따라서 "한 장 생성 → 그대로 슬라이스"는 신뢰도가 낮다.

이 epic은 **재격자화(re-grid) 후처리**로 균일 셀 격자를 만들어 정합을 맞추는 것을 1순위로 한다(Story 2). 다만 이게 신뢰도 높게 안 되면, 다음 중 하나로 **팀원과 계약을 조정**하는 선택지를 열어 둔다(무리하지 않는다):

- **옵션 A(권장·계약 유지):** FastAPI가 3개 스프라이트를 감지·크롭·baseline 정렬해 **균일 셀의 canonical 1×3 atlas**로 재배치. `spriteMeta`는 1행 3열 형태 유지(균일 셀 가정 성립).
- **옵션 B(계약 소폭 변경):** `spriteMeta`에 **실측 셀 지오메트리/프레임별 bounding box**를 담아 프런트가 비균일 격자도 잘라 쓰게 한다. (16/18 균일 격자 가정을 버림.)
- **옵션 C(생성 방식 변경):** 한 장 대신 **프레임별 생성 후 코드 합성**. 정렬은 확실하나 호출 비용·동일 캐릭터 일관성 리스크 증가.

Story 2에서 옵션 A를 시도하되, 한계가 확인되면 옵션 B/C를 **결정 포인트로 문서화**하고 팀 합의로 넘긴다.

## 5. Story 목록

권장 구현 순서:

1. **결정적 배경 제거 → 진짜 알파 후처리** (가장 확실한 이득)
2. **프레임 그리드 정규화 & 스프라이트 재배치** (규격 정합, 어려운 지점·재협상 레버)
3. **이미지 품질 게이트 & 프로덕션 스모크/회귀** (정형 검증)

| Story | 파일 | 성격 |
| --- | --- | --- |
| 1 | `stories/character-image-quality-1-background-removal-alpha.md` | 후처리 핵심 |
| 2 | `stories/character-image-quality-2-frame-grid-normalization.md` | 규격 정합 |
| 3 | `stories/character-image-quality-3-quality-gate-smoke-tests.md` | 검증·정형화 |

## 6. Epic 완료 기준

- Gemini가 비투명(체커보드/단색 배경) PNG를 반환해도, 결정적 후처리로 **진짜 알파 배경**을 만들어 검증기를 통과한다.
- 후처리 결과가 흐름 C 계약의 1×3 레이아웃·`spriteMeta`와 정합한다(옵션 A) 또는 재협상된 계약과 정합한다(옵션 B/C, 팀 합의 시).
- 프런트가 `spriteMeta`로 3개 프레임을 깨끗하게 잘라 쓸 수 있다(셀 내 스프라이트 정렬 보장 또는 실측 박스 제공).
- 진짜 알파만 `READY`로 저장하는 안전규칙이 유지된다. 후처리/정규화 실패는 `FALLBACK`으로 안전하게 떨어진다.
- 품질 게이트(배경 투명 비율, 3셀 비어있지 않음 등)가 자동 검증된다.
- 실제 Gemini/S3 호출 없는 fake/합성 테스트로 전 범위가 검증된다.
- 기존 character-image epic의 endpoint/storage 계약, report/quiz 흐름을 변경하지 않는다.

## 7. Fallback 정책 (생성·후처리 실패)

**원칙:** 어떤 단일 이미지 실패도 캐릭터 생성을 막지 않는다(아키텍처 §7.3, SM-3). `generate_commitgotchi_sprite`는 실패 시 항상 `CharacterImageResult(image_status="FALLBACK", storage_kind="NONE", sprite_meta=None, local_path=None, fallback_reason=<사유>)`를 반환하고, **저장은 일어나지 않는다.** 진짜 알파 + 규격 충족 시트만 `READY`로 저장된다.

**소비자 동작:** Spring Boot는 `FALLBACK`을 받으면 동일 1×3 레이아웃의 **기본 스프라이트 세트로 대체**하고 `image_status=FALLBACK`으로 표시한다. 향후 엔드포인트(`POST /api/ai/commitgotchi`, character-image epic Story 2)에서는 이 결과를 계약의 `status="FAIL"` + `errorMessage`로 매핑한다. `fallback_reason`은 안전한 enum 코드라 시크릿/프롬프트 원문이 노출되지 않는다.

**Fallback 사유 (SSOT: `app/image/schemas.py` `FallbackReason`):**

| reason | 발생 단계 | 트리거 |
| --- | --- | --- |
| `INVALID_DESIGN_KEYWORD` | 프롬프트 | 키워드 sanitize 실패(빈/무효). 모델·저장 호출 전에 차단 |
| `IMAGE_GENERATION_TIMEOUT` | 생성 | Gemini 타임아웃 |
| `IMAGE_GENERATION_FAILED` | 생성 | Gemini 호출 예외(재시도 소진) |
| `EMPTY_IMAGE_BYTES` | 생성 | 빈 응답 바이트 |
| `INVALID_PNG` | 후처리/검증 | PNG 시그니처 없음, 또는 최종 검증의 dimension 규칙 실패 |
| `BACKGROUND_REMOVAL_FAILED` | 후처리 | 배경 제거 디코드/처리 예외 |
| `GRID_NORMALIZATION_FAILED` | 후처리 | 3스프라이트 1×3 분할 실패(붙은 스프라이트·빈 셀·과다 노이즈 밴드) |
| `QUALITY_GATE_FAILED` | 후처리 | 셀 패널 배경/빈 셀(코너 투명도·점유율 게이트 탈락) |
| `LOCAL_STORAGE_FAILED` | 저장 | 로컬 저장 실패 |
| `MISSING_ALPHA_CHANNEL` | 검증 | 알파 부재. **후처리가 알파를 생성하므로 실사용상 거의 도달 불가**, 안전망으로 enum 유지 |

- `INVALID_PNG`·`*_FAILED`·`QUALITY_GATE_FAILED`는 **저장 없이 즉시 FALLBACK** → 깨지거나 지저분한 에셋은 절대 저장/응답되지 않는다.
- 후처리 실패(붙은 스프라이트 등)는 정상 동작의 일부다. 일부 생성 시도가 FALLBACK으로 떨어져도 소비자가 기본 스프라이트로 대체하므로 사용자 흐름은 끊기지 않는다.
