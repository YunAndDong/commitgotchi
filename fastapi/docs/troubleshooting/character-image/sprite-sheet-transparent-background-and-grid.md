# Character Image Troubleshooting - Transparent Background & Sliceable 2x3 Grid

## Symptom

캐릭터 스프라이트시트 생성이 보기엔 멀쩡한데도 `FALLBACK`으로 떨어지거나, 프런트가 프레임을 잘라 쓸 수 없었다.

- Gemini가 배경을 진짜 투명이 아니라 체커보드/단색으로 픽셀에 구워서 반환 → 검증기가 `MISSING_ALPHA_CHANNEL`로 거름(`differ/character-image/transparent-png-alpha-background.md`).
- 검증기는 "거르기"만 하고 교정하지 않아, 시각적으로 쓸 만한 결과도 버려짐.
- 한 장 시트가 균일 2×3 격자에 정렬돼 있지 않아, `spriteMeta` 기반 슬라이싱이 깨질 위험.

## Cause

1. 생성 모델은 "transparent background"를 요청해도 실제 알파를 주지 않고 배경을 픽셀로 그려 넣는다.
2. 캐논 프롬프트의 `logical 16x16` / `18x18` 표현을 모델이 **그릴 글자로 오해**해 "16x1", "18x7" 같은 라벨을 박아 넣었다. 이 텍스트가 격자 분할을 망가뜨렸다.
3. 후처리(배경 제거 → 격자 정규화) 단계가 아예 없었다. 생성 결과를 그대로 검증만 했다.

## Fix

흐름 C 계약(§4.4/§7.6)을 유지한 채, "거르기"를 "결정적 후처리로 규격에 맞게 교정하기"로 바꿨다(옵션 A). POC: `fastapi/app/image/`.

- **배경 제거 → 진짜 알파** (`background_removal.py`): 검정 전역 키잉(외곽선과 충돌) 대신, **테두리에서 시작하는 플러드필 + 허용오차**로 배경에 연결된 영역만 투명화한다. 캐릭터 내부 검정(외곽선·눈)은 배경과 분리돼 보존된다.
- **프롬프트 경화** (`sprite_preview_service.py`): 픽셀 치수 표기를 제거하고 **"글자·숫자·라벨 절대 금지" + "단색 평면 마젠타 배경(체커보드/그라데이션 금지)" + "균일 6칸 2×3 격자, 칸당 1마리 중앙배치"**를 강하게 명시. 텍스트 베이크가 사라지고 격자가 균일해졌다.
- **프레임 그리드 정규화** (`frame_normalizer.py`): alpha projection으로 행/열 밴드를 찾아 6스프라이트를 크롭·중앙정렬해 균일 셀 시트로 재배치. 분노마크 등 **작은 노이즈 밴드는 최소 두께 필터로 무시**(`MIN_BAND_FRACTION`).
- 진짜 알파만 `READY`로 저장하는 안전규칙은 유지. 교정 실패는 안전하게 `FALLBACK`.

### 검증 (2026-06-17, 실제 gemini-2.5-flash-image)

| 키워드 | 결과 |
| --- | --- |
| 별의 커비에 나오는 커비 (×5) | 깨끗한 READY, 균일 2×3 + 투명 |
| 노란 병아리 | 깨끗한 READY |
| 작고 둥근 초록 슬라임 | 노이즈-밴드 필터 추가 후 READY |
| 보라색 작은 용 | 검증 통과하나 셀별 패널 배경 잔존(Story 3 셀별 투명 게이트로 걸러야 함) |
| 귀여운 회색 고양이 로봇 | 스프라이트 밀착으로 분할 실패 → 안전 FALLBACK |

→ 배경 제거 + 격자 정합을 **계약 변경 없이** 후처리·프롬프트만으로 다수 케이스 해결. 잔여 케이스(패널 배경·밀착 스프라이트)는 품질 게이트의 FALLBACK이 막는다.

## Known Limitations / Follow-up

- **셀별 패널 배경**: 모델이 캐릭터마다 색 패널을 깔면 전역 배경만 제거된다. 셀별 투명 비율 게이트(품질 게이트 Story 3)와 "패널 금지" 프롬프트로 대응.
- **밀착 스프라이트**: 투명 간격 없이 붙은 캐릭터는 projection으로 못 나눈다. 연결요소 라벨링 또는 옵션 B(`spriteMeta`에 프레임별 실측 bbox) 계약 변경이 대안.
- 미세 핑크 AA 프린지는 비치명 수준으로 남는다.

## 관련 문서

- 계획: `fastapi/docs/character-image-quality/` (epic + Story 1~3)
- 보류 노트: `fastapi/docs/differ/character-image/transparent-png-alpha-background.md`
- POC 코드: `fastapi/app/image/background_removal.py`, `frame_normalizer.py`, `sprite_preview_service.py`, `scripts/character_image_preview.py`

## Reproduce

```bash
cd fastapi
. .venv/bin/activate   # google-genai, Pillow 필요
python -m scripts.character_image_preview "별의 커비에 나오는 커비"
# 결과: runtime/data/character-images/preview/<slug>/{1-raw,2-background-removed,3-normalized}.png (git 제외)
```
