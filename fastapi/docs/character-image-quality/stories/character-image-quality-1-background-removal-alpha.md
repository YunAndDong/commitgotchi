---
title: Character Image Quality 1 - 결정적 배경 제거 → 진짜 알파 후처리
status: done
created: 2026-06-17
owner: FastAPI AI 서버
epic: character-image-quality
source_docs:
  - ../character-image-quality-epic.md
  - ../../character-image/character-image-generation-epic.md
  - ../../differ/character-image/transparent-png-alpha-background.md
---

# Character Image Quality 1. 결정적 배경 제거 → 진짜 알파 후처리

## Status

done

## 목표

Gemini가 배경을 진짜 투명이 아니라 체커보드/단색으로 구워서 반환해도, **결정적 후처리로 배경을 진짜 알파 채널로 변환**해 검증기를 통과시킨다. 생성 결과를 거르기만 하던 흐름에 "교정" 단계를 추가하는 게 핵심이다.

검증기의 안전규칙(진짜 알파만 `READY`)은 약화하지 않는다. 후처리가 알파를 **만들어** 통과시키는 구조다.

## 배경

현재 `sprite_service.generate_commitgotchi_sprite()`는 `generate → validate_transparent_png → store` 순서다. 알파가 없으면 바로 `FALLBACK`(`MISSING_ALPHA_CHANNEL`). 이 story는 `generate`와 `validate` 사이에 **후처리 단계**를 끼운다.

배경 제거 방식 결정(에픽 §4 판단 반영):

- 검정(#000000) 전역 키잉은 쓰지 않는다. 정식 프롬프트가 "clean black outlines"(검은 외곽선)를 요구해 외곽선까지 뚫린다. 전역 정확값 매칭은 안티에일리어싱·노이즈로 실패한다.
- 대신 **테두리 기반 플러드필 + 허용오차**를 기본으로 한다. 이미지 네 모서리/테두리에서 연결된 영역만 배경으로 제거하므로, 캐릭터 내부의 검정(외곽선·눈)은 배경과 분리돼 보존된다.
- 프롬프트는 배경을 **단색의 흔치 않은 키 컬러**(예: solid magenta/green)로 깔고 "체커보드 금지, 캐릭터에 그 색 사용 금지"를 명시해 감지 신뢰도를 높인다.
- 체커보드가 와도 안전하게: 테두리에서 교차하는 두 그레이의 체커보드 패턴을 감지하면 투명 placeholder로 간주해 제거한다.

## 구현 범위

- `requirements.txt`에 Pillow 추가(이미지 디코드/알파 합성용).
- 후처리 모듈 신규: 입력 PNG bytes → RGBA로 디코드 → 배경 감지 → 테두리 플러드필(허용오차) → 알파=0 → RGBA PNG 재인코딩.
  - 배경색 추정: 네 모서리/테두리 샘플의 최빈색. 체커보드 패턴 감지 분기.
  - 플러드필: 테두리에서 시작하는 연결요소(BFS/flood)로 배경 영역만 투명화. 내부 동일색 보존.
  - 허용오차(키색과의 거리 ≤ N)로 AA·노이즈 흡수. AA 다크 프린지 최소화를 위한 가장자리 정리(선택: 알파 임계/디스페클).
- `sprite_service`에 후처리 단계 삽입: `generate → post_process_to_alpha → validate_transparent_png → store`. 후처리 실패는 예외로 흘려 기존 `FALLBACK` 분류로 떨어지게 한다(신규 fallback reason 추가 가능, 예: `BACKGROUND_REMOVAL_FAILED`).
- 프롬프트 업데이트: 단색 키 컬러 배경 + 체커보드 금지 + 키색을 캐릭터에 쓰지 말 것. `PROMPT_TEMPLATE_VERSION` 증가.
- fake/합성 PNG로 테스트(실제 Gemini 호출 없음).

## 주요 파일 경로

- 구현 후보: `fastapi/app/image/background_removal.py`
- 수정: `fastapi/app/image/sprite_service.py` (후처리 단계 삽입)
- 수정: `fastapi/app/image/prompts.py` (키 컬러 배경 지시, 템플릿 버전)
- 수정: `fastapi/app/image/schemas.py` (필요 시 fallback reason 추가)
- 수정: `fastapi/requirements.txt` (Pillow)
- 참고(유지): `fastapi/app/image/png_validation.py` (검증기는 약화하지 않음)
- 테스트 후보: `fastapi/tests/image/test_background_removal.py`, `fastapi/tests/image/test_sprite_service.py`

## Acceptance Criteria

- 단색 키 컬러 배경 PNG가 후처리 후 해당 배경이 알파=0(완전 투명)으로 바뀐다.
- 체커보드 배경 PNG가 후처리 후 투명으로 처리된다.
- 캐릭터 내부의 검정(외곽선·눈)은 보존된다(전역 키잉 금지).
- 후처리 결과가 진짜 알파 채널을 가져 `validate_transparent_png`를 통과한다.
- 후처리 실패/예외 시 기존 `FALLBACK` 분류로 안전하게 떨어지고 캐릭터 생성 흐름을 막지 않는다.
- 같은 입력 PNG에 대해 후처리 결과가 deterministic하다.
- 검증기(안전규칙)는 수정하지 않거나, 수정 시 진짜 알파 요구를 약화하지 않는다.
- 흐름 C 계약·`spriteMeta`·endpoint/storage 계약을 변경하지 않는다.
- 시크릿/프롬프트 원문/이미지 바이트가 로그에 남지 않는다.

## 테스트 기준

- 합성 PNG(단색 배경 + 중앙 도형 + 내부 검정 디테일)에서 배경만 투명화되고 내부 검정이 보존되는지 검증한다.
- 합성 체커보드 배경이 투명 처리되는지 검증한다.
- 허용오차로 near-key 노이즈가 흡수되는지, 캐릭터 본체는 안 먹는지 검증한다.
- 후처리 후 결과가 `validate_transparent_png`를 통과하는지 검증한다.
- 손상 PNG/후처리 예외에서 `FALLBACK`으로 떨어지는지 검증한다.
- 결정성: 동일 입력 2회 결과 바이트가 동일한지 검증한다.
- 기존 `test_sprite_service`, `test_png_validation` 회귀가 깨지지 않는지 확인한다.

## 제외 범위

- 프레임 그리드 정규화·재배치 (Story 2)
- 품질 게이트·프로덕션 스모크 (Story 3)
- 외부 배경제거 모델(rembg 등) 도입 — 결정적 후처리로 부족할 때 Story 3에서 옵션 검토
- 실제 Gemini/S3 호출 테스트
- FastAPI endpoint/storage 계약 변경
- report/quiz 흐름 변경

## 개발 메모

- 플러드필 키색을 마젠타/그린으로 두면 픽셀아트 팔레트와 충돌이 적어 감지가 안정적이다. 프롬프트와 후처리의 키색을 한 상수로 공유한다.
- 허용오차는 작게 시작해(예: 유클리드 거리 ≤ 24/255) 캐릭터 침범을 막고, 테두리 연결성 덕분에 내부 보존은 자동으로 된다.
- AA 프린지가 거슬리면 알파 0/255 이진화보다 가장자리만 소폭 매팅하는 편이 자연스럽다. MVP는 이진화로 시작하고 필요 시 개선.

## POC 검증 결과 (2026-06-17)

실제 `gemini-2.5-flash-image`로 옵션 A 파이프라인 POC를 돌려(`app/image/background_removal.py`, `sprite_preview_service.py`) 다음을 실증했다.

- **테두리 플러드필 배경 제거가 실전에서 동작.** "별의 커비에 나오는 커비" 5회 + 다양한 키워드에서 마젠타 배경을 자동 감지(키 팔레트 상위 색)·제거하고 내부 검정 외곽선을 보존했다. 투명비율 0.55~0.84.
- **검증된 프롬프트 교훈(가장 결정적):** 캐논 프롬프트의 `logical 16x16`/`18x18` 표현을 모델이 **글자로 그려 넣어**("16x1", "18x7" 라벨) projection을 망가뜨렸다. → 프롬프트에서 픽셀 치수 표기를 빼고 **"어떤 글자·숫자·라벨도 금지" + "단색 평면 마젠타 배경(체커보드/그라데이션 금지)"**를 강하게 명시하니 텍스트가 사라지고 배경 제거가 안정화됐다. POC의 `PREVIEW_PROMPT_TEMPLATE`가 검증본이며, 정식 `prompts.py` 템플릿(현재 `build_sprite_prompt` 테스트와 묶임) 갱신 시 이 방향을 반영한다.
- **발견된 한계 — 프레임별 패널 배경:** "보라색 작은 용"에서 모델이 캐릭터마다 색 패널을 깔아, 전역 마젠타만 제거되고 패널이 콘텐츠로 남았다(검증은 통과하나 시각적으로 지저분). 투명비율이 낮으면(<0.6) 이 신호다. → 프롬프트에 "캐릭터 뒤 패널/타일 금지, 배경은 전부 단색 마젠타"를 추가하고, Story 3 품질 게이트의 **셀별 투명 비율 검사**로 잡는다.
- 미세 핑크 AA 프린지는 비치명 수준으로 남는다(향후 매팅으로 개선 여지).

## 구현 완료 (2026-06-17)

- `app/image/background_removal.py`: 테두리 팔레트 추정 + 플러드필(허용오차)로 배경만 알파화, 내부 검정 보존. `remove_background_to_alpha()`.
- `app/image/sprite_service.py`: `generate → _post_process_sprite_sheet(배경제거→정규화→게이트) → 검증 → 저장`으로 통합. 배경제거 예외는 `BACKGROUND_REMOVAL_FAILED`, non-PNG는 `INVALID_PNG`.
- `app/image/prompts.py`: 캐논 템플릿 **v2**로 경화(텍스트/숫자/라벨 금지, 단색 마젠타 배경, 패널 금지). `PROMPT_TEMPLATE_VERSION="commitgotchi_character_sprite_v2"`. `sprite_preview_service`도 이 캐논 프롬프트를 재사용.
- `requirements.txt`: Pillow 추가.
- 테스트: `tests/image/test_image_postprocessing.py`(배경제거/내부검정 보존/결정성), `tests/image/test_sprite_generation.py`(RGB 시트가 알파를 얻어 READY 저장). 전체 183 통과.
- 실측: production 경로에서 READY 시트 저장 확인. 미세 AA 프린지만 잔존(비치명).
