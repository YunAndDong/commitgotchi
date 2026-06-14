# vue/_planning — 프론트 전용(격리) BMad 계획

이 폴더는 **프론트엔드 구현 에픽을 `vue/` 안에서만 기록**하기 위한 격리 작업 공간이다.
마스터 계획(`_bmad-output/planning-artifacts/epics.md`)은 이 작업 중 **수정하지 않는다.**

## 구성

- `frontend-epic.md` — 프론트 전용 에픽 정의 + FR 커버리지 맵 + FE 스토리 목록
- `stories/fe-*.md` — 스토리별 스펙(스켈레톤, AC는 `bmad-create-story`로 채움)

## 번호 규칙

임시 식별자는 `FE-*`를 쓴다. 마스터의 `Epic 2`는 현재 **백엔드 캐릭터 CRUD(FR-3~7)** 가
차지하고 있으므로, 지금 단계에서 "Epic 2"를 쓰면 충돌·혼선이 난다.
실제 Epic 번호는 **편입 시점에 확정**한다.

## 다음 작업 (새 컨텍스트 창 권장)

1. `bmad-create-story` — `stories/fe-*.md`의 Acceptance Criteria(Given/When/Then)를 채운다.
   출력 경로를 이 `vue/_planning/stories/` 하위로 지정한다.
2. `bmad-dev-story` 또는 `bmad-quick-dev` — 스토리대로 `vue/` 안에서만 구현·보강한다.

## 완료 후 마스터 편입 (지금은 실행 안 함)

1. `bmad-correct-course`("propose sprint change")로 본 FE 에픽을 마스터 `epics.md`에 편입.
2. `FR → Epic → Story → AC → Test` 추적성·의존성 맵 동시 갱신(ADR-E02 절차).
3. Epic 번호 확정: (a) 기존 Epic 2~6 재번호 후 삽입 / (b) 신규 에픽 추가 / (c) 도메인 병합.
4. `bmad-sprint-planning`으로 sprint status 갱신.
