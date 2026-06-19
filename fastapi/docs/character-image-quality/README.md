# 캐릭터 이미지 품질 고도화

Gemini 스프라이트시트가 "보기엔 멀쩡한데 배경이 진짜 투명이 아니라" 버려지는 문제를 해소하고, 흐름 C 계약(§4.4/§7.6)에 맞는 2×3 투명 PNG 스프라이트시트를 한 번에 안정적으로 산출하기 위한 계획 폴더다.

핵심 전환: **거르기 → 규격에 맞게 교정하기.** 결정적 후처리로 진짜 알파와 잘라 쓸 수 있는 격자를 만든 뒤에만 `READY`로 저장한다. RAG 고도화와는 무관한 별도 epic이다.

## 문서

- [`character-image-quality-epic.md`](./character-image-quality-epic.md) — 목적, 문제 분석, 원칙, 규격 정합의 어려운 지점(계약 재협상 레버), Story 목록
- [`character-image-quality-sprint-status.yaml`](./character-image-quality-sprint-status.yaml) — Story 상태 트래킹
- `stories/` — Story 1~3 상세

## 범위 요약

| 성격 | 내용 | Story |
| --- | --- | --- |
| 후처리 핵심 | 결정적 배경 제거 → 진짜 알파 | 1 |
| 규격 정합 | 프레임 그리드 정규화 & 재배치 (어려운 지점) | 2 |
| 정형 검증 | 품질 게이트 & 프로덕션 스모크/회귀 | 3 |

관련 보류 노트: [`../differ/character-image/transparent-png-alpha-background.md`](../differ/character-image/transparent-png-alpha-background.md)
