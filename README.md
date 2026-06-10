# Commit-Gotchi (commitgotchi)

> 혼자 CS를 공부하는 사람의 매일 학습을 **가상 캐릭터의 성장(육아)** 으로 바꿔주는 학습 동반자 서비스.

사용자가 하루 학습을 리포트로 기록하고 추천 퀴즈를 풀면, AI가 채점·분석해 피드백과 다음 학습 방향을 돌려주고, 그 결과가 캐릭터의 다섯 능력치(DB·알고리즘·CS·네트워크·프레임워크)와 감정·진화 상태로 반영된다. 공부한 만큼 내 분신이 자라고 진화하는 구조가 "오늘도 공부할 이유"를 만든다.

이번 버전은 **포트폴리오 MVP**이며, 가장 증명하려는 가치는 **AI 일일 레포트·채점·피드백·추천의 품질**이다.

---

## 현재 진행 상황 (2026-06-07)

기획·설계 단계(BMAD 워크플로) 산출물이 모두 **확정(final)** 되었고, 구현은 아직 착수 전이다.

| 단계 | 산출물 | 상태 |
|------|--------|------|
| Product Brief | `brief.md`, `addendum.md` | ✅ final |
| PRD | `prd.md` (FR-1~23), `addendum.md` | ✅ final |
| Architecture | `architecture.md` (ADR 10건, 핵심 계약 2건) | ✅ final |
| UX Design | `DESIGN.md`(3테마 디자인 토큰), `EXPERIENCE.md`(IA·플로우) | ✅ final |
| UI 목업 | `docs/Commit-Gotchi 목업 (Vue) - 단독.html` | ✅ 기준 목업(SSOT) |
| 구현 (FE/BE/AI) | — | ⬜ 미착수 |

**다음 단계:** 위 문서를 입력으로 에픽/스토리 분해(`bmad-create-epics-and-stories`) 후 구현 시작.

---

## 핵심 기능 (MVP 범위)

- **회원 관리** — 이메일·비밀번호 가입/로그인, JWT 인증 (FR-1~2)
- **캐릭터 관리** — 생성·조회·수정·삭제·활성화. 사용자당 최대 3개, 활성은 항상 1개. 생성 시 AI 이미지 생성 (FR-3~7)
- **일일 학습 리포트** — 하루 1개 작성·저장 (덮어쓰기) (FR-8)
- **AI 일일 레포트** — 자정 배치로 학습·답안 분석 → 점수 변화량 산출, 다음 학습 추천, 추천 퀴즈 생성 (FR-9~13)
- **퀴즈** — 추천 퀴즈 풀이·답안 제출 → 자정 배치 AI 채점·피드백 (FR-14~15)
- **성장 규칙** — 능력치·전투력(=5종 합)·진화(1,000점 도달 시 1회)·감정(기쁨/슬픔/화남) 반영 (FR-21~23)
- **랭킹·대시보드** — 전투력 기준 순위, 활성 캐릭터 홈 (FR-17~18)
- **공유 게시판·리뷰** — 캐릭터 공유 게시글·리뷰 CRUD (FR-19~20)
- **Fallback** — 이미지·레포트·채점 단계별 실패 시 기본 처리로 흐름 무중단 (FR-16)

**범위 밖(v2 이후):** 즉시 동기 채점, 결제·구독, 모바일 네이티브 앱, 다국어, 실시간 알림/푸시, 친구·팔로우 소셜 그래프.

---

## 아키텍처

소유권 경계 = 배포 가능한 서비스 경계. 두 백엔드 서버는 **DB를 공유하지 않으며**, 계약은 단 2개(SQS 메시지 + Internal API 콜백)뿐이다.

```
Vue SPA ──HTTPS/JWT──> Spring Boot (SoR) ──> PostgreSQL
                            │  ① 자정 배치 적재
                            ▼
                       AWS SQS  ──② 단건 소비──> FastAPI (AI) ──> 외부 AI API
                            ▲                         │
                            └──③ 결과 콜백────────────┘
                          POST /api/internal/reports/result
```

- **비동기 단방향 흐름:** SQS → FastAPI → Internal API 콜백
- **effectively-once:** SQS at-least-once 전달 + `requestId` 멱등 처리로 점수 중복 누적 방지
- **활성 캐릭터 단일성:** PostgreSQL 부분 유니크 인덱스로 DB 레벨 강제
- **시간 리듬:** 자정 적재 → 오전 9시까지 결과 제공 ("오늘 심고 내일 아침 수확")

자세한 ADR·데이터 모델·시퀀스는 `_bmad-output/planning-artifacts/architecture/`의 `architecture.md` 참고.

---

## 기술 스택

| 레이어 | 기술 |
|--------|------|
| Frontend | Vue 3 + Vite (SPA) |
| Backend (SoR) | Spring Boot 3.3.x, Java 17 LTS, Spring Data JPA, Spring Security(JWT) |
| AI Service | FastAPI, Python 3.11+ |
| Database | PostgreSQL 16 |
| Message Queue | AWS SQS (Standard) + DLQ |
| AI 모델 | LLM(채점·레포트·추천) + 이미지 생성 모델 (FastAPI 내부 캡슐화, 교체 가능) |

---

## 팀 & 담당

| 담당자 | 컴포넌트 | 핵심 책임 |
|--------|----------|-----------|
| **김윤석** | Spring Boot (System of Record) | 인증·캐릭터 CRUD·활성 단일성·리포트 저장·SQS 적재·결과 수신·점수/진화/감정 반영·랭킹·게시판 |
| **신동운** | FastAPI (Intelligence) | SQS 소비·일일 레포트·추천·채점·이미지 생성·콜백·멱등성·Fallback |
| **공통/FE** | Vue SPA | 대시보드·캐릭터·리포트·퀴즈·랭킹·게시판 화면, JWT 보관, REST 소비 |

---

## 디자인

Stardew풍 민트+크림 코지 픽셀 감성, Galmuri 픽셀 폰트. 3개 테마(`cozy` 기본 / `device` 다마고치 핸드헬드 / `cli` git 터미널) 전환 지원. IA·화면 구성의 기준(SSOT)은 `docs/Commit-Gotchi 목업 (Vue) - 단독.html` 목업이며, 동작·플로우는 `EXPERIENCE.md`, 시각 토큰은 `DESIGN.md`가 소유한다.

> **확정 필요(용어 불일치):** 목업은 주 지표를 "육아점수"로, PRD는 "전투력"(=능력치 5종 총합)으로 표기. UI 라벨은 "육아점수", 내부 정의는 PRD "전투력"을 계승 — 다운스트림에서 한 용어로 통일 예정.

---

## 디렉터리 구조

```text
commitgotchi/
├── docs/
│   └── Commit-Gotchi 목업 (Vue) - 단독.html   # 기준 목업(SSOT)
├── _bmad-output/planning-artifacts/
│   ├── briefs/        # Product Brief
│   ├── prds/          # PRD (FR-1~23)
│   ├── architecture/  # 아키텍처 결정 문서
│   └── ux-designs/    # DESIGN.md, EXPERIENCE.md
├── _bmad/             # BMAD 설정·스크립트
└── .agents/           # BMAD 스킬 정의
```

구현 착수 시 권장 구조: `frontend/` (Vue), `backend-spring/` (김윤석), `ai-fastapi/` (신동운).

---

## 미해결 / 확정 필요 항목

- 감정(기쁨/슬픔/화남) 산정의 구체 임계 (제안값 존재, 검토 필요)
- 진화 시 능력치 보너스 — MVP는 보너스 없음으로 제안
- AI 이미지 생성 실패용 기본 이미지 세트 구성·개수
- Internal API 서버 간 인증 방식 (공유 시크릿 헤더 vs VPC 내부 격리)
- UI 지표 용어 통일 ("육아점수" ↔ "전투력")
