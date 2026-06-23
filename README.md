# Commit-Gotchi (commitgotchi)

> 혼자 CS를 공부하는 사람의 매일 학습을 **가상 캐릭터의 성장(육아)** 으로 바꿔주는 학습 동반자 서비스.

사용자가 하루 학습을 리포트로 기록하고 추천 퀴즈를 풀면, AI가 채점·분석해 피드백과 다음 학습 방향을 돌려주고, 그 결과가 캐릭터의 다섯 능력치(DB·알고리즘·CS·네트워크·프레임워크)와 감정·진화 상태로 반영된다. 공부한 만큼 내 분신이 자라고 진화하는 구조가 "오늘도 공부할 이유"를 만든다.

이번 버전은 **포트폴리오 MVP**이며, 가장 증명하려는 가치는 **AI 일일 레포트·채점·피드백·추천의 품질**이다.

---

## 현재 구현 상태 (2026-06-12)

Spring Boot 인증 에픽이 구현되어 있다. 회원가입, 로그인, JWT 보호 API, Role 인가,
Refresh Token Rotation·재사용 탐지, 멱등 로그아웃, 최소 CORS allowlist를 제공한다.
Vue와 FastAPI 기능은 각 서비스 디렉터리에서 점진적으로 구현한다.

구현 범위, 보안 계약, 테스트 근거와 남은 작업은
[`Epic 1 구현 요약`](_bmad-output/implementation-artifacts/epic-1-implementation-summary.md)에서
한 번에 확인할 수 있다.

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

## 빠른 시작

### 필수 환경변수

`.env.example`을 `.env`로 복사한 뒤 실제 값을 입력한다. 실제 Secret, 토큰, 비밀번호,
운영 자격 증명은 커밋하지 않는다.

```bash
cp .env.example .env
openssl rand -base64 32
```

| 변수 | 형식 |
|------|------|
| `JWT_SECRET_BASE64` | Base64로 인코딩한 256-bit 이상 무작위 키 |
| `JWT_ISSUER` | Access Token issuer, 기본값 `commitgotchi-springboot` |
| `CORS_ALLOWED_ORIGINS` | 쉼표로 구분한 exact origin 목록 |
| `DB_USER`, `DB_PASSWORD`, `DB_PORT`, `SPRING_DB_NAME` | Spring Boot PostgreSQL 접속값 |

운영 `prod` 프로필은 하나 이상의 정확한 HTTPS CORS origin을 요구한다. wildcard,
origin pattern, HTTP origin, path/query/fragment가 포함된 값은 시작 시 거부된다.
`local`, `dev`, `test`는 명시적인 `http://localhost:<port>` 또는
`http://127.0.0.1:<port>`를 사용할 수 있지만 wildcard는 허용하지 않는다.

### Docker Compose

```bash
docker compose up --build
```

Spring Boot는 `http://localhost:${SPRING_PORT:-8080}`, Vue는
`http://localhost:${VUE_PORT:-5173}`에서 접근한다.

### 운영 Origin 모델

운영 기본안은 **same-origin reverse proxy**다. `https://app.example.com` 하나에서 Vue 정적 파일과
Spring Boot `/api/**`를 함께 제공하고, public Nginx가 `/api/**`를 Spring Boot로 프록시한다.

| 선택지 | 장점 | 단점 | 결정 |
| --- | --- | --- | --- |
| Option A: same-origin reverse proxy | 웹 Vue가 `/api/**` 상대 경로를 호출하므로 CORS 의존도가 작고 쿠키/프록시 진단이 단순하다. | public Nginx가 Vue와 Spring Boot 라우팅을 함께 책임져야 한다. | 운영 기본안 |
| Option B: cross-origin API | `app`과 `api`를 독립적으로 스케일링하거나 외부 API 도메인을 명확히 분리하기 쉽다. | `VITE_API_BASE_URL`, `CORS_ALLOWED_ORIGINS`, TLS, 쿠키 정책이 모두 어긋나기 쉬워 초기 운영 부담이 크다. | 보류 |

| 환경 | Expected origin | `VITE_API_BASE_URL` | `CORS_ALLOWED_ORIGINS` | `SPRING_PROFILES_ACTIVE` | Refresh cookie |
| --- | --- | --- | --- | --- | --- |
| Local Docker Compose | `http://localhost:5173` | `http://localhost:8080` | `http://localhost:5173` | `local` | secure false, `SameSite=Lax` |
| Local Vite dev | `http://localhost:5173` | `http://localhost:8080` | `http://localhost:5173` | `local` | secure false, `SameSite=Lax` |
| Production web | `https://app.example.com` | empty string | `https://app.example.com` | `prod` | secure true, `SameSite=None` |
| Production extension | `chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn` | `https://app.example.com` | `https://app.example.com` + 고정 extension origin | `prod` | secure true, `SameSite=None` |

운영 웹 Vue build는 `VITE_API_BASE_URL`을 비워 `/api/**`를 현재 origin으로 호출한다. 운영 extension build는
상대 경로를 사용할 수 없으므로 `VITE_API_BASE_URL=https://app.example.com`처럼 절대 origin을 넣는다.
상세 public Nginx 설정과 smoke test는
[`public-nginx-reverse-proxy-runbook.md`](springboot/docs/public-nginx-reverse-proxy-runbook.md)를 따른다.

### Spring Boot 로컬 실행

PostgreSQL이 실행 중이고 환경변수가 설정된 상태에서:

```bash
cd springboot
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local`, `dev`, `test` 프로필에서는 Swagger UI와 `/v3/api-docs`가 활성화된다.
기본 프로필, `prod`, `prod+dev`에서는 모두 비활성화된다.

```text
http://localhost:8080/swagger-ui/index.html
```

## 인증 API

| Method | Path | 인증 | 의미 |
|--------|------|------|------|
| `POST` | `/api/auth/signup` | 공개 | USER 계정 생성 |
| `POST` | `/api/auth/login` | 공개 | Access/Refresh Token Pair 발급 |
| `POST` | `/api/auth/refresh` | 공개 | Refresh Token Rotation으로 새 Token Pair 발급 |
| `POST` | `/api/auth/logout` | 공개 | 제출한 Refresh Token 세션을 멱등 종료, 항상 빈 `204` |
| `GET` | `/api/users/me` | Bearer USER/ADMIN | 현재 사용자 조회 |
| `GET` | `/api/admin/ping` | Bearer ADMIN | Role 인가 검증 |

Refresh Token 요청 예시는 항상 placeholder를 사용한다.

```json
{ "refreshToken": "<refresh-token>" }
```

로그아웃한 Refresh Token은 이후 재발급 시 `401 AUTH_REFRESH_TOKEN_INVALID`로 실패한다.
반복 로그아웃, 미존재 토큰, 형식이 잘못된 토큰 문자열도 존재 여부를 노출하지 않고
빈 `204`를 반환한다. 다만 body 누락/null, 잘못된 JSON, unknown field는
`400 VALIDATION_FAILED`다.

## 토큰 및 보안 계약

- Access Token 유효기간은 15분이며 무상태 JWT다.
- Refresh Token 유효기간은 30일이며 DB에는 SHA-256 hash만 저장한다.
- Refresh Token 재발급 시 기존 토큰을 폐기하고 새 Token Pair를 발급한다.
- Rotation으로 폐기된 토큰 재사용을 탐지하면 해당 사용자의 활성 Refresh Token을 폐기한다.
- 로그아웃은 제출된 Refresh Token 세션 하나만 종료한다.
- 로그아웃 후에도 기존 Access Token은 만료 전 최대 15분 동안 기술적으로 유효할 수 있다.
- Access Token blacklist와 즉시 무효화는 현재 범위가 아니다.
- CORS source of truth는 Spring Boot의 `CommitgotchiCorsConfiguration`이며,
  Spring Security가 이 설정을 `/api/**`에만 적용한다.
- 허용 origin은 `CORS_ALLOWED_ORIGINS`의 exact origin 목록과 고정 Chrome 확장 origin
  `chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`을 합친 값이다. 운영 `prod`
  프로필은 최소 하나의 HTTPS origin을 요구하고 wildcard, origin pattern, path/query/fragment를 거부한다.
- CORS는 `/api/**`에 대해 `GET`, `POST`, `PATCH`, `DELETE`, `OPTIONS`와
  `Authorization`, `Content-Type`만 허용하며 `Access-Control-Allow-Credentials: true`를
  반환한다. credentials가 활성화되어 있으므로 `Access-Control-Allow-Origin: *`는 사용하지 않는다.
- 브라우저-facing API는 Spring Boot가 소유한다. Vue는 FastAPI를 직접 호출하지 않으며,
  FastAPI에는 browser CORS를 선제적으로 추가하지 않는다. 브라우저가 FastAPI를 직접 호출해야 하는
  요구가 생기면 별도 결정 기록과 회귀 테스트를 먼저 추가한다.
- 응답과 기본 로그에는 비밀번호, token 원문/hash, Authorization 헤더, JWT Secret,
  DB 자격 증명, 내부 예외 상세와 stack trace를 노출하지 않는다.

## Swagger 시연

`local`, `dev`, `test` 프로필에서 다음 흐름을 확인할 수 있다.

1. `/api/auth/signup`으로 USER 가입
2. `/api/auth/login`으로 Token Pair 발급
3. Swagger UI `Authorize`에 Access Token 입력
4. `/api/users/me`가 `200`인지 확인
5. USER로 `/api/admin/ping` 호출 시 `403 AUTH_FORBIDDEN` 확인
6. `/api/auth/logout` 후 동일 Refresh Token의 `/api/auth/refresh`가
   `401 AUTH_REFRESH_TOKEN_INVALID`인지 확인

## 테스트

Docker Desktop이 실행 중이어야 Testcontainers가 PostgreSQL 16을 시작할 수 있다.

```bash
cd springboot
./gradlew clean test
```

테스트는 실제 SecurityFilterChain, Flyway, PostgreSQL을 사용해 회원가입 → 로그인 →
`/me` → Rotation → 로그아웃 → 재발급 실패 흐름, JWT 오류, Role 인가, Rotation 재사용,
CORS allowlist/fail-fast, 민감정보 비노출을 검증한다.

## 초기 ADMIN 프로비저닝

공개 회원가입 API는 항상 `USER`만 생성한다. 초기/운영 ADMIN은 공개 API나 Flyway
마이그레이션으로 만들지 않고, 접근이 제한되고 감사 가능한 운영 DB 또는 CLI 절차로
프로비저닝해야 한다. 자동화 테스트에서는 격리된 fixture만 ADMIN을 생성한다.

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
├── springboot/        # Spring Boot System of Record
├── fastapi/           # AI Intelligence 서비스
├── vue/               # Vue SPA
├── postgres/init/     # 로컬 PostgreSQL 초기화
├── docs/
│   └── Commit-Gotchi 목업 (Vue) - 단독.html   # 기준 목업(SSOT)
├── _bmad-output/planning-artifacts/
│   ├── briefs/        # Product Brief
│   ├── prds/          # PRD (FR-1~23)
│   ├── architecture/  # 아키텍처 결정 문서
│   └── ux-designs/    # DESIGN.md, EXPERIENCE.md
├── _bmad-output/implementation-artifacts/
│   ├── README.md       # 구현 산출물 탐색 인덱스
│   └── epic-1-implementation-summary.md  # Epic 1 구현 완료 요약
├── _bmad/             # BMAD 설정·스크립트
└── .agents/           # BMAD 스킬 정의
```

---

## 미해결 / 확정 필요 항목

- 감정(기쁨/슬픔/화남) 산정의 구체 임계 (제안값 존재, 검토 필요)
- 진화 시 능력치 보너스 — MVP는 보너스 없음으로 제안
- AI 이미지 생성 실패용 기본 이미지 세트 구성·개수
- Internal API 서버 간 인증 방식 (공유 시크릿 헤더 vs VPC 내부 격리)
- UI 지표 용어 통일 ("육아점수" ↔ "전투력")
