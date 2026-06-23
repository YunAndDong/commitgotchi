---
title: Character Image Quality 2 - 프레임 그리드 정규화 & 스프라이트 재배치
status: done
created: 2026-06-17
owner: FastAPI AI 서버
epic: character-image-quality
source_docs:
  - ../character-image-quality-epic.md
  - ./character-image-quality-1-background-removal-alpha.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.4
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#7.6
---

# Character Image Quality 2. 프레임 그리드 정규화 & 스프라이트 재배치

## Status

done

## 목표

Story 1로 배경이 투명해져도, 프런트가 `spriteMeta`로 3개 감정 프레임을 잘라 쓰려면 **3개 스프라이트가 균일 셀 격자에 정렬**돼 있어야 한다. Gemini는 이를 픽셀 단위로 보장하지 않고 1024×1024 정사각 캔버스 안에 1행을 배치하는 경우가 많다. 이 story는 투명화된 시트에서 3개 스프라이트를 감지·크롭·baseline 정렬해 **균일 셀의 canonical 1×3 atlas**로 재배치한다(에픽 §4 옵션 A).

이 부분이 규격 정합의 어려운 지점이다. 옵션 A가 신뢰도 높게 안 되면 **계약 재협상(옵션 B/C)을 결정 포인트로 문서화**하고 무리하지 않는다.

## 배경

현재 계약: 1×3, 열=joy/happy/sad/angry 중 `joy/happy`, `sad`, `angry` 3프레임. pre-hatch/알 이미지는 AI가 생성하지 않고 정적 스프라이트를 사용한다. 프런트는 `spriteMeta`의 `frameMap`+셀 크기로 `background-position` 슬라이싱을 한다.

## 구현 범위 (옵션 A 우선)

- 투명 시트(Story 1 출력)에서 alpha projection으로 1행/3열 스프라이트 후보 감지.
- 각 스프라이트를 bounding box로 크롭 → **균일 square cell atlas에 baseline 정렬** 재배치. 시트 = 3·cell × 1·cell, 투명 패딩.
- 결과를 검증기 정합(`width%3==0`, `rows=1`, 프레임≥18px)으로 맞춤. `spriteMeta` 기존 1×3 형태 유지(균일 셀 가정 성립).
- 정규화 실패(3개 미감지/배치 모호) 시:
  - 안전 폴백: 원본(투명) 시트를 유지하되 품질 플래그를 남기거나 `FALLBACK` 분류로 떨어뜨림(정책은 Story 3 게이트와 합의).
  - **계약 재협상 결정 포인트** 문서화:
    - 옵션 B: `spriteMeta`에 프레임별 실측 bounding box/셀 지오메트리를 담아 비균일 격자 슬라이싱 지원.
    - 옵션 C: 프레임별 생성 후 코드 합성으로 전환.
- fake/합성 시트로 테스트.

## 주요 파일 경로

- 구현 후보: `fastapi/app/image/frame_normalizer.py`
- 수정: `fastapi/app/image/sprite_service.py` (Story 1 후처리 다음 단계로 연결)
- 수정: `fastapi/app/image/schemas.py` (`SpriteMetadata` — rows=1, joy/happy/sad/angry frameMap)
- 수정: `fastapi/app/image/png_validation.py` (`SPRITE_ROWS=1`)
- 테스트: `fastapi/tests/image/test_image_postprocessing.py`

## Acceptance Criteria

- 투명 시트에서 3개 스프라이트 영역을 감지하고 1×3 배치로 정렬 추정한다.
- 각 스프라이트가 균일 square cell에 baseline 정렬된 canonical atlas가 생성된다.
- canonical atlas가 검증기를 통과하고 `spriteMeta`(columns=3, rows=1, frameMap)와 정합한다.
- 프런트가 `spriteMeta`로 3개 셀을 겹침/잘림 없이 분해할 수 있다(셀 경계 안에 스프라이트가 들어감).
- 3개 감지 실패/배치 모호 시 안전 폴백으로 떨어지고 흐름을 막지 않는다.
- 옵션 A 한계 시 옵션 B/C 결정 포인트가 문서로 명확히 남는다.
- 같은 입력에서 정규화 결과가 deterministic하다.
- 흐름 C 계약을 깨지 않는다(옵션 A는 계약 유지; 옵션 B/C는 팀 합의 전까지 구현하지 않는다).

## 테스트 기준

- 3개 도형이 어긋나게 배치된 합성 시트가 균일 셀로 재정렬되는지 검증한다.
- 재배치 후 각 셀 영역에 정확히 하나의 스프라이트가 들어가는지 검증한다.
- 2개/4개 등 비정상 감지 시 안전 폴백 분기로 가는지 검증한다.
- 재정규화 결과가 검증기를 통과하고 셀 분해가 일관되는지 검증한다.
- 결정성: 동일 입력 2회 결과가 동일한지 검증한다.

## 제외 범위

- 배경 제거 (Story 1)
- 품질 게이트·스모크 (Story 3)
- 옵션 B(`spriteMeta` 확장) / 옵션 C(프레임별 생성) 실제 구현 — 팀 계약 합의 전까지 문서화만
- 실제 Gemini/S3 호출 테스트
- 프런트 렌더 구현(타 담당)

## 개발 메모

- 연결요소 라벨링은 Pillow + 간단한 flood/scipy 없이도 BFS로 충분하다(시트가 작음).
- "중앙정렬 + 투명 패딩"이 프런트 슬라이싱과 가장 호환된다. 셀 크기는 가장 큰 스프라이트 bbox 기준으로 잡아 잘림을 막는다.
- 옵션 A가 80~90% 케이스를 커버하면 충분하다. 나머지는 `FALLBACK`이 안전망이다. 완벽한 격자에 집착해 시간 쓰지 말고, 한계가 보이면 옵션 B(실측 박스)를 팀에 제안하는 게 빠르다 — 사용자가 계약 변경 권한이 있다고 명시함.

## POC 검증 결과 (2026-06-17)

alpha projection 분할로 옵션 A를 POC 구현(`app/image/frame_normalizer.py`)해 실제 생성물에 적용한 결과:

- **경화 프롬프트 + projection 정규화로 균일 1×3 atlas 산출**(Gemini가 1024×1024 정사각 raw를 반환해도 최종 atlas는 3:1 → 프런트 슬라이싱 OK).
- **발견된 한계 1 — 노이즈 밴드:** 화남 표정의 분노마크/잔여 speckle이 1~6px projection 밴드로 잡혀 "행 밴드 7개"로 실패("초록 슬라임"). → **작은-밴드 무시 필터**(`MIN_BAND_FRACTION`/`MIN_BAND_FLOOR_PX`)를 추가하니 슬라임이 통과(861×436). POC 코드에 반영됨.
- **발견된 한계 2 — 붙은 스프라이트:** 하단 두 캐릭터가 투명 간격 없이 붙으면 열 projection이 2개로 병합돼 실패("회색 고양이 로봇"). projection만으로는 분리 불가. → 안전 폴백(`FALLBACK`)으로 떨어지며, 정밀 분리가 필요하면 **연결요소(BFS) 라벨링** 또는 옵션 B(실측 bbox)로 넘긴다.
- **결론:** 검증한 키워드 기준 3개 깨끗한 READY, 1개 검증통과-but-패널(Story 1/3 영역), 1개 안전 FALLBACK. 옵션 A는 **프롬프트 경화 + 노이즈 필터**로 다수 케이스를 계약 유지로 처리하고, 잔여 케이스는 FALLBACK이 막는다. 현재 표본에선 옵션 B/C로의 계약 변경이 필수는 아니다.

## 구현 완료 (2026-06-17)

- `app/image/frame_normalizer.py`: alpha projection으로 행/열 밴드 분할 → 3스프라이트 bbox 크롭 → 균일 square cell(최대 bbox+패딩) baseline 정렬 재배치. `normalize_sprite_grid()`.
- 노이즈 밴드 무시 필터(`MIN_BAND_FRACTION`/`MIN_BAND_FLOOR_PX`)로 분노마크 등 1~6px 밴드 제거.
- `sprite_service`가 정규화 실패 시 `GRID_NORMALIZATION_FAILED`로 FALLBACK(저장 안 함). 옵션 A 계약 유지 — `spriteMeta`(columns=3, rows=1).
- 테스트: `tests/image/test_image_postprocessing.py`(3스프라이트→균일 atlas 검증 통과, 2스프라이트→실패, 1024×1024 raw→3:1 atlas 재조립, 노이즈 밴드 무시).
- 한계(붙은 스프라이트)는 여전히 FALLBACK 처리. 옵션 B/C(실측 bbox·프레임별 생성)는 미착수로 남김 — 현재 표본에선 불필요.
