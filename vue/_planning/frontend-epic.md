---
stepsCompleted: []
status: draft
scope: frontend-only
inputDocuments:
  - ../FRONTEND.md
  - ../EXTENSION.md
  - ../../_bmad-output/planning-artifacts/epics.md
  - ../../_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md
  - ../../_bmad-output/planning-artifacts/ux-designs/ux-commitgotchi-2026-06-07/EXPERIENCE.md
  - ../../_bmad-output/planning-artifacts/ux-designs/ux-commitgotchi-2026-06-07/DESIGN.md
  - ../../_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md
note: >
  이것은 vue/ 하위에만 존재하는 프론트 전용(격리) 에픽 계획이다.
  마스터 _bmad-output/planning-artifacts/epics.md 는 이 작업 중 수정하지 않는다.
  프론트 에픽 완료 후 bmad-correct-course 로 마스터에 편입한다(편입 시 번호 확정).
  충돌 회피를 위해 임시 식별자는 Epic 2 가 아니라 FE-* 를 사용한다.
---

# Commit-Gotchi — Frontend Epic (격리 계획)

## Overview

이 문서는 Vue 3 + Vite SPA 프론트 구현을 추적 가능한 스토리로 분해한다.
범위는 `FRONTEND.md`/`EXTENSION.md`와 SSOT 디자인 문서(`DESIGN.md`, `EXPERIENCE.md`),
그리고 마스터 `epics.md`의 FR-1~28 중 **프론트엔드 표면에 해당하는 책임**으로 한정한다.

> 작업 규칙(FRONTEND.md 재확인): 모든 프론트 변경은 `vue/` 하위에만 작성한다.
> `springboot/`, `docs/`, `_bmad-output/` 등 외부 파일은 참고만 하고 수정하지 않는다.

인증 화면은 실제 Spring Boot API에 연결되어 있고, 아직 백엔드 엔드포인트가 없는
캐릭터·리포트·퀴즈·랭킹·게시판은 mock 스토어(`stores/game.js`)로 동작한다.
각 mock 액션은 의도적으로 `authed('GET'|'POST', '/api/...')` 형태로 작성돼 있어,
백엔드 엔드포인트가 생기면 본문만 교체하면 실제 연동으로 전환된다.

## Epic Goal

목업/SSOT 디자인을 기준으로 EXPERIENCE.md의 모든 핵심 표면과 3개 흐름
(A 자정 배치 리포트 · B 즉시 채점 퀴즈 · C 비동기-즉시 이미지)을
3개 테마(cozy/device/cli)와 접근성 기준(색만으로 정보 전달 금지)을 지키며
SPA로 구현하고, 실제 API 연동 전환 지점을 명확히 표시한 상태로 완성한다.

## FR Coverage Map (프론트 책임 관점)

마스터 epics.md의 FR → 프론트 표면/스토리 매핑. (백엔드 책임은 마스터 에픽이 보유)

| FR | 프론트 책임 | 화면 / EXPERIENCE # | FE 스토리 |
|---|---|---|---|
| FR-1, FR-2, FR-24, FR-25, FR-27, FR-28 | 로그인/가입 UI, JWT 부착·refresh rotation, 인증 가드, 오류 표시 | 로그인/가입 #1 | FE-2 |
| FR-3 | 캐릭터 생성 입력 폼 + 생성 완료(컨페티) | 생성 폼(보강), 완료 #11 | FE-4 |
| FR-4 | 보유 캐릭터 목록·상세·도감 조회 | 상세 #4, 도감 #3 | FE-5 |
| FR-5, FR-6, FR-7 | 캐릭터 수정·삭제·활성 지정 UI(능력치/전투력/진화는 read-only) | 상세 #4, 대시보드 #2-2 | FE-5 |
| FR-8, FR-10, FR-12 | 일일 리포트 작성 폼 + AI 일일 레포트 결과 뷰(자정 배치 pending→도착) | 리포트 작성 #5, 결과(보강) | FE-6 |
| FR-13, FR-14, FR-15 | 추천 퀴즈 조회·답안 제출·즉시 채점 결과 인라인 표시 | 퀴즈(보강, 흐름 B) | FE-7 |
| FR-16 | AI 실패 시 Fallback/대기/에러 상태 패턴 일관 적용 | 전 표면 State Patterns | FE-10 |
| FR-17, FR-23 | 대시보드(활성 캐릭터·육아점수·감정·상태 메시지·활동 로그)·빈 상태 | 대시보드 #2-2 | FE-3 |
| FR-18 | 육아점수 기준 랭킹·내 순위 하이라이트 | 랭킹 #7 | FE-8 |
| FR-19, FR-20 | 공유 게시글 CRUD·리뷰 CRUD UI | 게시판 #8, 상세+리뷰 #9 | FE-9 |
| FR-21, FR-22, FR-23 | 성장 규칙 시각화(게이지·레이더·진화 연출·감정칩) | 공통 컴포넌트 | FE-1 (기반), FE-6 |

> 전역 기반(디자인 토큰·테마·라우팅·공통 컴포넌트)은 특정 FR이 아니라 전 표면을 떠받치므로 FE-1로 분리한다.

## FE Story List

상태 표기: `done(mock)` = mock 기반 구현 존재 / `done(real)` = 실제 API 연동 / `draft` = 미상세.
현재 SPA에 코드가 이미 존재하므로 대부분 초기 구현이 있다 — 각 스토리는
bmad-create-story로 AC를 채워 **현 구현을 명세에 맞춰 검증·보강**하는 것이 목표다.

| # | 제목 | 현재 상태 | 핵심 화면/컴포넌트 |
|---|---|---|---|
| FE-1 | 디자인 시스템·테마·공통 컴포넌트 기반 | done(mock) | tokens.css, base.css, CgSprite/Gauge/Radar/StatTile/Emo/Confetti, AppNav, Mascot, ThemeSwitcher |
| FE-2 | 라우팅·인증 가드·실제 인증 연동 | done(real) | LoginView, router/index.js, api/client.js, stores/auth.js |
| FE-3 | 대시보드 + 빈 상태 | done(mock) | DashboardView |
| FE-4 | 캐릭터 생성 흐름(폼 + 완료 컨페티) | done(mock) | CharacterCreateView, CreationCompleteView |
| FE-5 | 캐릭터 조회·상세·수정·삭제·활성 지정·도감 | done(mock) | CharacterDetailView, CodexView |
| FE-6 | 일일 리포트 흐름 A(작성 + 결과, 자정 배치) | done(mock) | ReportWriteView, ReportResultView |
| FE-7 | 퀴즈 즉시 채점 흐름 B | done(mock) | QuizView |
| FE-8 | 랭킹(포디움 TOP3 + 내 순위) | done(mock) | RankingView |
| FE-9 | 공유 게시판 + 리뷰 | done(mock) | BoardView, BoardDetailView |
| FE-10 | AI Fallback·로딩·에러 상태 패턴 | draft | 전 표면 공통 |

각 스토리 상세는 `stories/fe-*.md` 참조.

## 의존성

- FE-1(기반) → 모든 화면 스토리의 선행.
- FE-2(라우팅·인증) → 보호 라우트를 쓰는 모든 화면의 선행.
- FE-6/FE-7(리포트·퀴즈) → 성장 규칙 시각화에서 FE-1 컴포넌트(게이지·레이더·감정칩) 사용.
- FE-10(상태 패턴) → 각 화면 스토리에 횡단 적용(완료 기준에 포함).

## 마스터 편입 계획 (참고 — 지금은 실행하지 않음)

프론트 에픽이 완료되면:

1. `bmad-correct-course`("propose sprint change")로 본 FE 에픽을 마스터 `epics.md`에 편입한다.
2. 편입 시 `FR → Epic → Story → AC → Test` 추적성과 의존성 맵을 함께 갱신한다(ADR-E02 절차).
3. **Epic 번호 결정**: 현재 마스터의 Epic 2 = 백엔드 캐릭터 CRUD(FR-3~7)다.
   프론트를 "Epic 2"로 넣으려면 (a) 기존 Epic 2~6 재번호 후 삽입, (b) 별도 신규 에픽으로 추가,
   (c) 백엔드+프론트 도메인 에픽 병합 중 하나를 그 시점에 확정한다.
4. 편입 후 `bmad-sprint-planning`으로 sprint status를 갱신한다.
