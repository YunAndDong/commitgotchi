---
title: Character Image Quality 3 - 품질 게이트 & 프로덕션 스모크/회귀
status: backlog
created: 2026-06-17
owner: FastAPI AI 서버
epic: character-image-quality
source_docs:
  - ../character-image-quality-epic.md
  - ./character-image-quality-1-background-removal-alpha.md
  - ./character-image-quality-2-frame-grid-normalization.md
  - ../../differ/character-image/transparent-png-alpha-background.md
---

# Character Image Quality 3. 품질 게이트 & 프로덕션 스모크/회귀

## Status

backlog

## 목표

"한 번에 제대로 됐다"를 정형적으로 보장한다. 알파 존재 여부만 보던 검증을 넘어, **배경 투명 비율·6셀 점유·셀 정렬** 같은 산출물 품질 게이트를 추가하고, 실제 Gemini로 도는 안전한 프로덕션 스모크 스크립트(메타데이터만 기록)로 파이프라인을 사람이 확인할 수 있게 한다.

## 배경

`differ/character-image/transparent-png-alpha-background.md`의 후속 방향(프롬프트 강화, 2차 후처리, 안전 메타데이터만 남기는 스모크, 생성물 git 제외)을 이 story가 정형화한다. Story 1·2가 만든 파이프라인이 실제로 규격을 충족하는지 자동으로 게이트한다.

## 구현 범위

- **품질 게이트 함수**(검증기와 별개, `READY` 직전 적용):
  - 네 모서리/배경이 충분히 투명한지(배경 투명 픽셀 비율 ≥ 임계).
  - 6개 셀 각각에 불투명 스프라이트 픽셀이 존재(빈 셀 없음).
  - (옵션 A 채택 시) 스프라이트가 셀 경계 안에 있고 셀 간 겹침이 없음.
  - 게이트 실패는 `FALLBACK`으로 분류(신규 reason 예: `QUALITY_GATE_FAILED`).
- **프로덕션 스모크 스크립트**: env 플래그로만 켜지고 실제 Gemini를 호출해 전체 파이프라인(생성→배경제거→정규화→게이트→저장)을 돌린다.
  - 기록은 **안전 메타데이터만**(상태, reason, 크기, 투명 비율, 셀 점유, prompt sha — 원문/바이트/키 금지).
  - 생성물은 ignored runtime 경로에만 저장, git 추적 금지(`.gitignore` 확인/추가).
  - 단위 테스트에 포함하지 않음(실제 API 호출이므로).
- **회귀**: 기존 `FALLBACK` 의미·endpoint/storage 계약·report/quiz 흐름 불변 확인.
- fake/합성 이미지로 게이트 단위 테스트.

## 주요 파일 경로

- 구현 후보: `fastapi/app/image/quality_gate.py`
- 수정: `fastapi/app/image/sprite_service.py` (저장 직전 게이트 적용)
- 수정: `fastapi/app/image/schemas.py` (게이트 fallback reason)
- 스모크: `fastapi/scripts/character_image_smoke.py`
- guardrail: `fastapi/.gitignore` (runtime 생성물 제외 확인)
- 테스트 후보: `fastapi/tests/image/test_quality_gate.py`

## Acceptance Criteria

- 배경 투명 비율·6셀 점유 게이트가 합성 이미지에서 올바르게 통과/실패를 판정한다.
- 게이트 실패가 `FALLBACK`으로 분류되어 흐름을 막지 않는다.
- 진짜 알파만 `READY` 저장하는 안전규칙이 유지된다.
- 프로덕션 스모크 스크립트가 env 플래그로만 실행되고, 안전 메타데이터만 기록한다(원문 프롬프트/이미지 바이트/API key 미기록).
- 생성 스모크 산출물이 git에 추적되지 않는다.
- 단위 테스트는 실제 Gemini/S3를 호출하지 않는다.
- endpoint/storage 계약, report/quiz 흐름, 흐름 C 계약을 변경하지 않는다.

## 테스트 기준

- 배경이 충분히 투명한 합성 시트는 게이트 통과, 불투명 배경 잔존 시트는 실패하는지 검증한다.
- 한 셀이 빈 합성 시트가 게이트 실패로 분류되는지 검증한다.
- 게이트 실패 시 `CharacterImageResult`가 `FALLBACK`과 적절한 reason을 반환하는지 검증한다.
- 스모크 스크립트가 fake 모드(또는 dry-run)에서 안전 메타데이터만 출력하는지 검증한다.
- 기존 image 테스트 전체 회귀가 깨지지 않는지 확인한다.

## 제외 범위

- 배경 제거 알고리즘 (Story 1)
- 프레임 정규화 (Story 2)
- 실제 Gemini/S3 호출을 단위 테스트에 포함
- S3 업로드 구현(후속 storage epic)
- 프런트 렌더 구현

## 개발 메모

- 게이트 임계값은 상수/설정으로 노출해 튜닝 가능하게 둔다. 너무 빡빡하면 멀쩡한 결과도 FALLBACK이 된다 — Story 1/2 통과율을 보며 조정.
- 스모크는 포트폴리오에서 "파이프라인이 실제로 규격 이미지를 만든다"를 보이는 증거가 된다. 안전 메타데이터 표(상태·투명비율·셀점유)를 리포트로 남기면 좋다(키/원문/바이트 제외).
- 외부 배경제거 모델(rembg 등)은 Story 1 결정적 후처리가 특정 키워드에서 부족할 때만 여기서 옵션으로 검토한다(의존성·비결정성 트레이드오프 명시).

## POC 검증 결과 (2026-06-17)

POC가 게이트의 필요성을 구체적으로 보여줬다.

- **셀별 투명 비율 검사가 핵심.** "보라색 작은 용"은 alpha·격자 검증을 통과하지만 각 셀에 캐릭터 패널 배경이 남았다. 전역 검증만으론 못 잡고, **셀별 배경 투명 비율 임계**가 있어야 FALLBACK으로 거른다.
- **붙은 스프라이트("회색 고양이 로봇")**는 Story 2에서 `FALLBACK`으로 떨어지므로, 게이트가 빈 셀/병합 셀을 추가로 점검하면 이중 안전망이 된다.
- 권장 게이트 임계 출발점(POC 관측 기반): 전체 투명비율 ≥ 0.6, 셀별 배경 투명비율 ≥ 임계, 6셀 모두 불투명 스프라이트 픽셀 존재. "별의 커비/병아리/슬라임"은 통과, "용(패널)/고양이(병합)"는 탈락하도록 캘리브레이션.
