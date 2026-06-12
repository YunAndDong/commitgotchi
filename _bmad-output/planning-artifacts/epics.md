---
stepsCompleted: [1, 2]
inputDocuments:
  - /Users/kimyunseok/.codex/attachments/65171465-fd28-4ad1-acec-65bd3038c444/pasted-text.txt
  - prds/prd-commitgotchi-2026-06-07/prd.md
  - prds/prd-commitgotchi-2026-06-07/addendum.md
  - prds/prd-commitgotchi-2026-06-07/.decision-log.md
  - architecture/architecture-commitgotchi-2026-06-07/architecture.md
  - architecture/architecture-commitgotchi-2026-06-07/.decision-log.md
  - ../../README.md
---

# commitgotchi - Epic Breakdown

## Overview

이 문서는 commitgotchi 전체 MVP 요구사항을 추적 가능한 에픽과 스토리로 분해한다. 전체 FR-1~28을 계획 범위로 유지하되, 첫 번째 구현 증분은 Spring Boot 인증·인가이며 해당 에픽을 즉시 개발 가능한 상세 수준으로 우선 정의한다.

## Requirements Inventory

### Functional Requirements

FR-1: 사용자는 이메일과 비밀번호로 가입할 수 있으며, 정규화된 이메일은 중복될 수 없고 공개 가입 계정의 Role은 항상 `USER`다.

FR-2: 사용자는 올바른 자격 증명으로 로그인해 서명된 Access Token과 Refresh Token을 발급받고, 유효한 Access Token으로 보호 API에 접근할 수 있다.

FR-3: 사용자는 이름·디자인 키워드·성격을 입력해 최대 3개의 캐릭터를 생성할 수 있으며, 이미지 생성 실패 시에도 Fallback으로 생성 흐름이 완료된다.

FR-4: 사용자는 자신이 보유한 캐릭터 목록과 개별 캐릭터의 능력치·전투력·감정·진화 상태·이미지를 조회할 수 있다.

FR-5: 사용자는 자신이 보유한 캐릭터의 편집 가능한 속성을 수정할 수 있으나 능력치·전투력·진화 상태를 직접 수정할 수 없다.

FR-6: 사용자는 자신이 보유한 캐릭터를 삭제할 수 있으며, 삭제 후에도 활성 캐릭터 단일성 규칙이 유지된다.

FR-7: 사용자는 보유 캐릭터 중 하나를 활성 캐릭터로 지정할 수 있으며, 한 사용자에게 활성 캐릭터는 최대 하나다.

FR-8: 사용자는 활성 캐릭터 기준으로 그날의 학습 리포트를 작성·저장할 수 있다.

FR-9: 시스템은 매일 자정 이후 사용자별 AI 일일 레포트 생성 요청을 멱등 식별자와 함께 비동기 큐에 적재한다.

FR-10: AI 서비스는 요청을 처리해 학습 분석, 능력치별 점수 변화량, 다음 학습 추천 및 추천 퀴즈를 포함한 AI 일일 레포트를 생성한다.

FR-11: 시스템은 AI 일일 레포트의 점수 변화량을 활성 캐릭터 능력치에 일 단위로 한 번만 누적하고 전투력을 갱신한다.

FR-12: 사용자는 매일 오전 9시까지 자신의 AI 일일 레포트 또는 생성 실패·대기 상태를 확인할 수 있다.

FR-13: 사용자는 AI가 생성한 추천 퀴즈와 모범답안을 기반으로 제공되는 문제를 조회하고 풀 수 있다.

FR-14: 사용자는 퀴즈 답안을 제출할 수 있으며, 시스템은 제출 답안을 저장하고 채점 흐름을 시작한다.

FR-15: 시스템은 제출된 퀴즈 답안을 AI로 채점해 피드백과 점수 변화량을 제공하고 활성 캐릭터에 중복 없이 반영한다.

FR-16: 이미지 생성·일일 레포트 생성·퀴즈 채점 중 단일 AI 처리가 실패해도 시스템은 Fallback 상태와 기본 처리를 제공해 사용자 흐름을 중단하지 않는다.

FR-17: 사용자는 대시보드에서 활성 캐릭터, 능력치, 전투력, 감정·상태 메시지 및 최근 학습 현황을 확인할 수 있다.

FR-18: 사용자는 활성 캐릭터 전투력 기준 랭킹과 자신의 순위를 조회할 수 있다.

FR-19: 사용자는 자신의 캐릭터 공유 게시글을 작성·조회·수정·삭제할 수 있고 다른 사용자의 공유 게시글을 조회할 수 있다.

FR-20: 사용자는 공유 게시글에 리뷰를 작성·조회·수정·삭제할 수 있으며 자신의 리뷰만 수정·삭제할 수 있다.

FR-21: 시스템은 점수 변화량 반영 후 능력치와 전투력을 갱신하며, 전투력은 항상 다섯 능력치의 합과 일치해야 한다.

FR-22: 캐릭터의 전투력이 1,000점 이상이 되면 캐릭터당 최대 한 번 진화하고 진화형 이미지 상태로 전환된다.

FR-23: 시스템은 학습 및 채점 결과에 따라 캐릭터 감정과 성격을 반영한 상태 메시지를 갱신한다.

FR-24: 시스템은 Refresh Token 원문 대신 해시를 저장하고, 유효한 Refresh Token 제출 시 Rotation을 적용해 새로운 Access Token과 Refresh Token을 발급한다.

FR-25: 사용자는 Refresh Token을 제출해 로그아웃할 수 있으며, 폐기된 Refresh Token은 이후 재발급에 사용할 수 없다.

FR-26: 시스템은 `USER`와 `ADMIN` Role을 구분하고, 일반 보호 API와 관리자 API의 접근 권한을 Role에 따라 허용하거나 거부한다.

FR-27: 시스템은 인증·인가를 종단 검증할 수 있도록 `GET /api/users/me`와 `GET /api/admin/ping`을 제공한다.

FR-28: 시스템은 회원가입·인증·인가 실패를 일관된 오류 형식과 정확한 HTTP 상태 및 애플리케이션 오류 코드로 제공하며 민감정보를 노출하지 않는다.

### NonFunctional Requirements

NFR-1: 비밀번호는 BCrypt cost 12로 단방향 해시하여 저장하고 평문 비밀번호와 `password_hash`를 응답 또는 로그에 노출하지 않는다.

NFR-2: JWT Secret, DB 자격 증명 및 기타 Secret은 환경변수나 외부 Secret 관리 수단으로 주입하고 소스코드·Git·Swagger 예시에 포함하지 않는다.

NFR-3: Access Token은 HS256, 15분 유효기간, 최소 256-bit Secret을 사용하며 서명·형식·알고리즘·issuer·type·필수 Claim·만료를 모든 보호 요청에서 검증한다.

NFR-4: Refresh Token은 32바이트 CSPRNG opaque 값, 30일 유효기간을 사용하고 DB에는 SHA-256 lowercase hex 해시만 저장하며 원문을 로그에 남기지 않는다.

NFR-5: 인증·인가 오류 응답과 로그에는 비밀번호, JWT, Refresh Token, Authorization 헤더, Secret, 내부 예외 상세 또는 stack trace가 포함되지 않아야 한다.

NFR-6: 인증 서버는 `SessionCreationPolicy.STATELESS`로 동작하고 Bearer Token 기반 API에 맞게 CSRF를 비활성화한다.

NFR-7: 운영 CORS는 `CORS_ALLOWED_ORIGINS` 환경변수의 정확한 HTTPS origin allowlist만 허용하고 wildcard를 금지한다.

NFR-8: DB 스키마 변경은 Flyway로 재현 가능하게 관리하고 모든 환경에서 Hibernate `ddl-auto=validate`로 스키마 매핑 불일치를 탐지한다.

NFR-9: Refresh Token Rotation은 행 잠금과 단일 트랜잭션으로 동시성을 제어하며, 폐기 토큰 재사용 대응으로 활성 토큰 전체 폐기가 인증 예외 때문에 롤백되지 않아야 한다.

NFR-10: 인증 스토리는 단위·Repository·Spring Security·API 통합 테스트를 기능 구현과 함께 포함하며, 인증 에픽의 필수 종단 승인 시나리오를 자동화 테스트로 검증한다.

NFR-11: AI 처리 실패는 사용자 흐름을 중단하지 않아야 하며, 점수 반영·활성 캐릭터 단일성·전투력 합계는 트랜잭션과 멱등성으로 유지되어야 한다.

NFR-12: Swagger UI와 OpenAPI 문서는 `local`, `dev`, `test` 프로필에서만 활성화하고 운영 프로필에서는 기본 비활성화하여 접근할 수 없어야 한다.

NFR-13: Swagger/OpenAPI 예시에는 실제 JWT, Refresh Token, 비밀번호 또는 Secret을 포함하지 않아야 한다.

### Additional Requirements

- 첫 번째 구현 증분은 사용자 가치 중심의 단일 인증·인가 에픽으로 구성하고, `springboot/`의 현재 초기 골격에 적용한다.
- 첫 번째 인증·인가 증분에서는 `users`, `refresh_tokens` 테이블만 생성하며 캐릭터·리포트·퀴즈·게시판·Spring AI 관련 테이블과 구현은 포함하지 않는다.
- 인증 에픽은 권장 구현 순서인 안전한 회원가입 → 로그인 및 JWT 보호 API → Role 기반 관리자 인가 → Refresh Token Rotation → 로그아웃 및 보안 마무리를 따른다.
- 기존 Web/JPA/PostgreSQL에 Spring Security, Validation, Spring Security OAuth2 JOSE, Flyway 및 PostgreSQL Flyway 모듈을 추가하고 테스트에는 Spring Security Test와 Testcontainers PostgreSQL을 사용한다.
- Spring Boot dependency management가 관리하는 버전을 사용하고 JWT 서명·파싱 암호 로직을 직접 구현하지 않는다.
- `V1__create_users.sql`은 정규화 이메일 unique/CHECK와 Role CHECK를 포함하고, `V2__create_refresh_tokens.sql`은 사용자 FK, token hash unique 및 상태 조회 인덱스를 포함한다.
- 이메일은 trim 후 lowercase로 정규화하며, 동일 이메일의 공백·대소문자 변형 중복 가입도 `409 USER_EMAIL_CONFLICT`로 거부한다.
- 공개 회원가입 DTO는 Role 필드를 허용하지 않으며 unknown Role 필드가 포함되면 `400 VALIDATION_FAILED`로 거부하고 공개 가입 Role은 항상 `USER`다.
- 초기 `ADMIN`은 공개 API 또는 운영 Flyway에서 생성하지 않고 제한된 운영 DB/CLI 절차로 프로비저닝한다. 인증 증분 테스트는 fixture/profile에서만 ADMIN을 준비한다.
- 인증 경로는 `POST /api/auth/signup`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/users/me`, `GET /api/admin/ping`으로 고정한다.
- 인증 성공 JSON은 camelCase, 시각은 UTC ISO-8601을 사용하고, 오류는 status·code·message·timestamp·traceId를 포함한 공통 형식을 사용한다.
- 오류 코드는 `AUTH_INVALID_CREDENTIALS`, `AUTH_ACCESS_TOKEN_MISSING`, `AUTH_ACCESS_TOKEN_INVALID`, `AUTH_ACCESS_TOKEN_EXPIRED`, `AUTH_REFRESH_TOKEN_INVALID`, `AUTH_REFRESH_TOKEN_REUSED`, `AUTH_FORBIDDEN`, `USER_EMAIL_CONFLICT`, `VALIDATION_FAILED`를 구분한다.
- `JwtAuthenticationFilter`는 요청당 한 번 실행되고 `UsernamePasswordAuthenticationFilter` 앞에 배치되며, JWT 검증 성공 시 최소 userId·email·role을 가진 불변 `AuthPrincipal`을 SecurityContext에 설정한다.
- 일반 보호 API는 `USER`, `ADMIN`이 접근할 수 있고 `/api/admin/**`는 `ADMIN`만 접근할 수 있다. 미인증은 `401`, 권한 부족은 `403`으로 구분한다.
- Refresh Token 재발급은 `SELECT ... FOR UPDATE`로 기존 토큰을 잠근 뒤 검증·폐기·새 토큰 저장을 수행하며, 폐기 토큰 재사용 시 사용자의 모든 활성 Refresh Token을 폐기한다.
- 로그아웃은 제출된 Refresh Token의 존재·형식·기존 폐기 여부를 노출하지 않는 멱등 `204`로 처리하며, Access Token은 로그아웃 후 최대 15분간 유효할 수 있음을 계약에 명시한다.
- 인증 에픽 완료 시 회원가입 → 로그인 → `/api/users/me`, 이메일 변형 중복 거부, 공개 ADMIN 생성 불가, 정상·누락·변조·만료 JWT, USER/ADMIN 권한, Rotation·재사용, 로그아웃, 민감정보 비노출 시나리오를 모두 검증한다.
- `springdoc-openapi` 의존성을 Story 1.1에 추가하고 회원가입 요청·응답·검증 오류를 문서화한다.
- Swagger UI와 `/v3/api-docs`는 `local`, `dev`, `test` 프로필에서 활성화하며, 활성 환경에서 관련 경로를 `permitAll` 처리하고 운영 프로필에서는 기본 비활성화한다.
- Story 1.2에서 OpenAPI Bearer JWT 인증 스키마와 Swagger UI Authorize 버튼을 구성하고 로그인, `/api/users/me`, JWT 인증 실패 응답을 문서화한다.
- Story 1.3에서 `/api/admin/ping`이 ADMIN 전용임을 문서화하고 USER 접근 시 `403 AUTH_FORBIDDEN` 응답을 문서화한다.
- Story 1.4와 Story 1.5에서 Refresh Token 재발급과 로그아웃 API를 문서화하며 실제 JWT, Refresh Token, 비밀번호 및 Secret을 예시에 포함하지 않는다.
- 인증 에픽 완료 시 Swagger UI에서 회원가입 → 로그인 → Access Token Authorize → `/api/users/me` 흐름과 USER 토큰의 관리자 API `403`을 검증할 수 있어야 한다.
- 후속 MVP 에픽을 상세화하기 전에 최신 PRD/Addendum의 퀴즈 동기 채점·이미지 비동기 큐 결정과 기존 아키텍처의 퀴즈 웹훅·이미지 동기 HTTP 충돌을 정합화해야 한다.

### UX Design Requirements

이번 요구사항 추출에서는 백엔드 인증·인가 우선 구현 범위와 직접 관련이 없는 UX 문서를 사용자 확인에 따라 입력에서 제외했다. Swagger UI는 개발·시연·검증 도구이며 관련 사용 흐름은 Additional Requirements와 인증 에픽 승인 기준에서 다룬다.

### FR Coverage Map

FR-1: Epic 1 - 안전한 회원가입
FR-2: Epic 1 - 로그인, Access Token 발급 및 보호 API 접근
FR-3: Epic 2 - 캐릭터 생성
FR-4: Epic 2 - 보유 캐릭터 조회
FR-5: Epic 2 - 캐릭터 수정
FR-6: Epic 2 - 캐릭터 삭제
FR-7: Epic 2 - 활성 캐릭터 지정
FR-8: Epic 3 - 일일 학습 리포트 작성·저장
FR-9: Epic 3 - AI 일일 레포트 생성 요청 적재
FR-10: Epic 3 - AI 일일 레포트 생성
FR-11: Epic 3 - 학습 리포트 점수 변화량 반영
FR-12: Epic 3 - AI 일일 레포트 결과 제공
FR-13: Epic 3 - 추천 퀴즈 제공
FR-14: Epic 4 - 퀴즈 답안 제출
FR-15: Epic 4 - AI 채점·피드백 및 성장 반영
FR-16: Epic 2, Epic 3, Epic 4 - 각 AI 기능의 필수 Fallback 완료 조건
FR-17: Epic 5 - 성장 대시보드
FR-18: Epic 5 - 전투력 랭킹
FR-19: Epic 6 - 캐릭터 공유 게시글 CRUD
FR-20: Epic 6 - 공유 게시글 리뷰 CRUD
FR-21: Epic 3 - 능력치·전투력 갱신
FR-22: Epic 3 - 캐릭터 진화
FR-23: Epic 3 - 감정·상태 메시지 반영
FR-24: Epic 1 - Refresh Token 저장·재발급·Rotation
FR-25: Epic 1 - 로그아웃
FR-26: Epic 1 - Role 기반 인가
FR-27: Epic 1 - 최소 인증·인가 검증 API
FR-28: Epic 1 - 인증·인가 실패 응답

## Epic List

### 에픽 계획 안정성 결정

**ADR-E01 — Epic 1 확정 기준선**

- Epic 1의 인증·인가 범위, Story 1.1~1.5 순서, 외부 API·보안·데이터·오류·Swagger 계약과 완료 기준은 즉시 구현할 확정 기준선이다.
- 자동화된 단위·Repository·통합·보안·계약 테스트와 Swagger 시연 흐름 및 운영 Swagger 접근 거부 검증을 기준선의 증거로 사용한다.
- 후속 에픽은 Epic 1의 인증된 사용자 식별자와 Role 계약에만 의존하며 JWT 라이브러리, 필터 구조, SecurityContext 구성, 토큰 저장 방식 등 내부 구현에는 의존하지 않는다.
- JWT 라이브러리·내부 필터 구조·Refresh Token 저장소 구현·Swagger annotation 배치 방식은 외부 계약과 완료 기준을 보존하는 한 변경할 수 있다.
- Epic 1 변경은 영향 분석과 명시적 승인 후 반영한다.

**ADR-E02 — Epic 2~6 가변 후속 계획**

- Epic 2~6은 전체 FR-3~23 추적성을 보존하기 위한 잠정 계획이다.
- 각 후속 에픽 구현 착수 전 언제든 분할·병합·재정렬·범위 및 스토리 수정이 가능하다.
- 변경 시 `FR → Epic → Story → Acceptance Criteria → Test` 추적성, 관련 NFR, 핵심 사용자 여정, 의존성 맵을 함께 갱신하여 요구사항과 사용자 가치 누락을 방지한다.
- 각 후속 에픽은 구현 착수 전에 해결할 사용자 Job, 검증할 가설, 성공 지표, 재설계 조건을 확정한다.
- 경미한 변경은 스토리 순서·표현 변경과 추적성 갱신으로 처리한다.
- 에픽 분할·병합, 공개 API·데이터 모델·사용자 여정 변경은 영향 분석과 명시적 승인이 필요한 중대 변경으로 처리한다.
- Epic 1 외부 계약 또는 완료 기준에 영향을 주는 변경은 ADR-E01 변경 절차를 따른다.
- 구현 완료된 공개 API나 데이터 모델 변경은 별도 ADR과 회귀 테스트를 요구한다.
- PRD와 Architecture 사이의 퀴즈 채점 및 이미지 처리 방식 충돌이 해결되기 전에는 관련 상세 스토리와 승인 기준을 확정하지 않는다. 결정 후 관련 ADR, 추적성, NFR, 의존성 및 테스트 전략을 갱신한다.
- AI 채점·이미지 생성은 계약이 확정될 때까지 인터페이스와 결정론적 Fake 뒤에 격리하여 후속 에픽의 변경 가능성을 유지한다.

### Epic 1: 안전한 계정 접근과 권한 관리

사용자는 안전하게 가입하고 로그인하여 보호 API를 이용할 수 있으며, 시스템은 `USER`와 `ADMIN` 권한을 구분하고 Refresh Token 기반 재발급과 로그아웃을 지원한다. 개발자는 Swagger UI에서 인증·인가 흐름을 시연하고 검증할 수 있다.

**계획 상태:** 확정 기준선, 첫 번째 구현 증분

**FRs covered:** FR-1, FR-2, FR-24, FR-25, FR-26, FR-27, FR-28

### Epic 2: 나만의 학습 캐릭터 만들기와 관리

사용자는 자신의 학습을 표현할 캐릭터를 생성·조회·수정·삭제하고 현재 성장시킬 활성 캐릭터를 지정할 수 있다. AI 이미지 생성 실패 시에도 Fallback 이미지로 캐릭터 생성 흐름을 완료할 수 있다.

**계획 상태:** 잠정, 구현 착수 전 수정 가능

**FRs covered:** FR-3, FR-4, FR-5, FR-6, FR-7, FR-16 일부

### Epic 3: 학습 기록을 AI 코칭과 캐릭터 성장으로 연결

사용자는 일일 학습 리포트를 기록하고 AI 분석·추천을 받아 그 결과가 활성 캐릭터의 능력치·전투력·감정·진화로 이어지는 핵심 성장 루프를 경험할 수 있다.

**계획 상태:** 잠정, 구현 착수 전 수정 가능

**FRs covered:** FR-8, FR-9, FR-10, FR-11, FR-12, FR-13, FR-16 일부, FR-21, FR-22, FR-23

### Epic 4: 추천 퀴즈로 실력을 점검하고 성장하기

사용자는 추천 퀴즈에 답안을 제출하고 AI 채점·피드백을 받아 자신의 약점을 확인하며 캐릭터를 성장시킬 수 있다.

**계획 상태:** 잠정, 구현 착수 전 수정 가능

**FRs covered:** FR-14, FR-15, FR-16 일부

### Epic 5: 학습 성장 현황과 순위 확인하기

사용자는 대시보드에서 활성 캐릭터와 최근 학습 성장을 한눈에 확인하고 전투력 랭킹에서 자신의 위치를 확인할 수 있다.

**계획 상태:** 잠정, 구현 착수 전 수정 가능

**FRs covered:** FR-17, FR-18

### Epic 6: 캐릭터를 공유하고 서로 피드백하기

사용자는 자신의 캐릭터를 게시판에 공유하고 다른 사용자의 캐릭터를 살펴보며 리뷰를 주고받을 수 있다.

**계획 상태:** 잠정, 구현 착수 전 수정 가능

**FRs covered:** FR-19, FR-20

## Epic 1: 안전한 계정 접근과 권한 관리

사용자는 안전하게 가입하고 로그인하여 보호 API를 이용할 수 있으며, 시스템은 `USER`와 `ADMIN` 권한을 구분하고 Refresh Token 기반 재발급과 로그아웃을 지원한다. 개발자는 Swagger UI에서 인증·인가 흐름을 시연하고 검증할 수 있다.

### Story 1.1: 안전한 회원가입과 API 문서 기반 구축

As a 신규 사용자,
I want 이메일과 비밀번호로 안전하게 가입하고,
So that `USER` 권한 계정을 생성하여 서비스 이용을 시작할 수 있다.

**목적:** 로그인이나 Refresh Token 없이 독립 검증 가능한 회원가입 흐름을 완성한다.

**범위:** 최소 의존성·설정, Flyway `users`, 공통 오류 기초, 회원가입 API, Swagger 프로필 설정 및 회원가입 문서화, 자동화 테스트

**Acceptance Criteria:**

**Given** 애플리케이션이 시작될 때
**When** Flyway 마이그레이션과 JPA 검증이 실행되면
**Then** `V1__create_users.sql`만 적용된다
**And** 정규화 이메일 unique/CHECK와 `USER|ADMIN` Role CHECK가 강제된다
**And** Hibernate는 `ddl-auto=validate`를 사용한다
**And** 인증 외 도메인 테이블과 `refresh_tokens`는 생성하지 않는다

**Given** 유효한 이메일과 정책을 충족하는 비밀번호가 주어졌을 때
**When** `POST /api/auth/signup`을 호출하면
**Then** 이메일은 trim·lowercase 정규화되어 저장된다
**And** 비밀번호는 BCrypt cost 12 해시로 저장된다
**And** Role은 항상 `USER`다
**And** `201 Created`와 사용자 식별 정보가 반환된다
**And** 비밀번호 및 해시는 응답에 포함되지 않는다

**Given** 동일 이메일의 대소문자 또는 공백 변형 계정이 존재할 때
**When** 회원가입을 요청하면
**Then** `409 USER_EMAIL_CONFLICT`가 반환된다

**Given** 요청값이 잘못되었거나 요청에 `role` 필드가 포함될 때
**When** 회원가입을 요청하면
**Then** `400 VALIDATION_FAILED`가 반환된다
**And** 공개 요청으로 `ADMIN` 계정이 생성되지 않는다

**Given** 애플리케이션이 `local`, `dev`, `test` 프로필로 실행될 때
**When** Swagger UI 또는 `/v3/api-docs`에 접근하면
**Then** 문서에 접근할 수 있다
**And** 회원가입 요청·성공 응답·검증 오류·중복 이메일 오류가 문서화되어 있다
**And** 회원가입과 Swagger 관련 경로는 접근 가능하다

**Given** 애플리케이션이 운영 프로필로 실행될 때
**When** Swagger UI 또는 `/v3/api-docs`에 접근하면
**Then** 접근할 수 없다

**Given** Story 1.1 테스트가 실행될 때
**When** 단위·Repository·API 통합 테스트가 완료되면
**Then** 이메일 정규화, BCrypt, DB 제약, 정상 가입, 중복, 검증 실패, Role 주입 거부, 프로필별 Swagger 접근 정책이 검증된다

**비범위:** 로그인, JWT 발급·검증, `/api/users/me`, 관리자 API, Refresh Token, 로그아웃

### Story 1.2: 로그인과 JWT Access Token 보호 API

As a 가입 사용자,
I want 로그인하여 Access Token으로 내 정보를 조회하고,
So that 보호된 서비스 기능을 안전하게 사용할 수 있다.

**목적:** 무상태 JWT 인증과 최소 보호 API를 종단 완성한다.

**범위:** Spring Security 무상태 설정, 로그인, HS256 Access Token, JWT 검증 필터, `/api/users/me`, Bearer OpenAPI 문서, 자동화 테스트

**Acceptance Criteria:**

**Given** 올바른 이메일과 비밀번호가 주어졌을 때
**When** `POST /api/auth/login`을 호출하면
**Then** HS256 Access Token이 반환된다
**And** 유효기간은 15분이다
**And** `sub`, `role`, `iat`, `exp`, `iss=commitgotchi-springboot`, `typ=access` Claim을 포함한다
**And** Secret은 `JWT_SECRET_BASE64`로 주입된다
**And** 로그인 성공 응답은 Story 1.4에서 Refresh Token 필드를 추가할 수 있는 구조다

**Given** 존재하지 않는 이메일 또는 잘못된 비밀번호가 주어졌을 때
**When** 로그인을 요청하면
**Then** 두 경우 모두 `401 AUTH_INVALID_CREDENTIALS`가 반환된다
**And** 계정 존재 여부와 내부 인증 상세를 노출하지 않는다

**Given** 유효한 Access Token을 Bearer 헤더에 포함했을 때
**When** `GET /api/users/me`를 호출하면
**Then** 현재 사용자의 `id`, `email`, `role`이 반환된다
**And** Controller는 인증된 사용자 ID와 Role 계약에만 의존한다

**Given** Access Token이 누락·변조·만료되었거나 형식·알고리즘·issuer·type·필수 Claim이 잘못되었을 때
**When** 보호 API를 호출하면
**Then** 각각 적절한 `401 AUTH_ACCESS_TOKEN_MISSING`, `AUTH_ACCESS_TOKEN_INVALID`, `AUTH_ACCESS_TOKEN_EXPIRED`가 공통 오류 형식으로 반환된다

**Given** 애플리케이션이 실행될 때
**When** Spring Security 설정을 검사하면
**Then** 세션은 stateless이며 CSRF가 비활성화된다
**And** JWT 필터는 요청당 한 번 실행된다
**And** 공개 경로의 토큰 누락은 허용되고 그 외 `/api/**`는 기본 인증이 필요하다

**Given** Swagger가 활성화된 환경일 때
**When** Swagger UI를 열면
**Then** Bearer JWT 인증 스키마와 Authorize 버튼이 제공된다
**And** 로그인, `/api/users/me`, JWT 인증 실패 응답이 문서화된다
**And** Swagger 관련 경로는 `permitAll`이다
**And** 예시에는 실제 비밀번호·JWT·Secret이 포함되지 않는다

**Given** Story 1.2 테스트가 실행될 때
**When** 단위·JWT 보안·Spring Security·API 통합 테스트가 완료되면
**Then** 로그인 성공·실패, Claim, 정상·누락·변조·만료·잘못된 JWT, `/api/users/me`, Swagger Bearer 구성이 검증된다

**비범위:** ADMIN 인가, Refresh Token 발급·재발급, 로그아웃

### Story 1.3: Role 기반 관리자 인가

As an 관리자,
I want 관리자 API에 접근하고 일반 사용자의 접근은 차단되기를 원하며,
So that 권한이 필요한 운영 기능을 안전하게 분리할 수 있다.

**목적:** 인증 성공과 권한 부족을 구분하는 Role 기반 인가를 완성한다.

**범위:** Authority 매핑, `/api/admin/ping`, EntryPoint·DeniedHandler, 관리자 테스트 fixture/profile, Swagger 문서, 자동화 테스트

**Acceptance Criteria:**

**Given** 인증된 `USER` 또는 `ADMIN` 사용자가 있을 때
**When** `GET /api/users/me`를 호출하면
**Then** 두 Role 모두 `200`으로 접근할 수 있다

**Given** 유효한 `ADMIN` Access Token이 있을 때
**When** `GET /api/admin/ping`을 호출하면
**Then** `200`과 `{ "status": "ok" }`가 반환된다

**Given** 유효한 `USER` Access Token이 있을 때
**When** `GET /api/admin/ping`을 호출하면
**Then** `403 AUTH_FORBIDDEN`이 공통 오류 형식으로 반환된다

**Given** 인증되지 않은 요청일 때
**When** 관리자 API를 호출하면
**Then** `401`이 반환된다
**And** 인증됐으나 권한이 부족한 `403`과 명확히 구분된다

**Given** Role이 JWT Claim에서 읽힐 때
**When** SecurityContext의 Authority가 구성되면
**Then** 서명과 필수 Claim 검증이 성공한 토큰만 `ROLE_USER` 또는 `ROLE_ADMIN`으로 매핑된다

**Given** 초기 ADMIN이 필요할 때
**When** 개발·테스트 환경을 준비하면
**Then** ADMIN은 테스트 fixture/profile에서만 생성된다
**And** 공개 API 또는 운영 Flyway에 관리자 자격 증명을 포함하지 않는다

**Given** Swagger가 활성화된 환경일 때
**When** `/api/admin/ping` 문서를 확인하면
**Then** ADMIN 전용 API임이 명시된다
**And** USER 접근의 `403 AUTH_FORBIDDEN` 응답이 문서화된다
**And** 실제 ADMIN 토큰이나 자격 증명은 예시에 포함되지 않는다

**Given** Story 1.3 테스트가 실행될 때
**When** Role 인가 통합 테스트가 완료되면
**Then** USER·ADMIN의 일반 보호 API 접근, ADMIN 관리자 API 성공, USER `403`, 미인증 `401` 구분이 검증된다

**비범위:** 운영 ADMIN 관리 API, Refresh Token, 로그아웃

### Story 1.4: Refresh Token 발급과 Rotation

As a 로그인 사용자,
I want 장기 Refresh Token으로 안전하게 Token Pair를 재발급받고,
So that Access Token 만료 후에도 자격 증명을 다시 입력하지 않고 서비스를 계속 사용할 수 있다.

**목적:** 원문 비저장, Rotation, 동시성 제어, 재사용 탐지를 갖춘 재발급 흐름을 완성한다.

**범위:** Flyway `refresh_tokens`, opaque Refresh Token, 로그인 Token Pair 확장, `/api/auth/refresh`, Rotation·재사용 대응, Swagger 문서, 자동화 테스트

**Acceptance Criteria:**

**Given** Story 1.4 마이그레이션이 적용될 때
**When** Flyway가 실행되면
**Then** `V2__create_refresh_tokens.sql`로 `refresh_tokens` 테이블만 추가된다
**And** 사용자 FK, unique `token_hash`, 만료·폐기 상태와 필요한 인덱스가 생성된다

**Given** 사용자가 로그인에 성공했을 때
**When** Token Pair가 발급되면
**Then** 15분 Access Token과 30일 Refresh Token이 함께 반환된다
**And** Refresh Token은 32바이트 CSPRNG opaque Base64url 값이다
**And** DB에는 SHA-256 lowercase hex 해시만 저장된다
**And** 원문은 DB와 로그에 저장되지 않는다

**Given** 유효한 Refresh Token이 있을 때
**When** `POST /api/auth/refresh`를 호출하면
**Then** 새로운 Access Token과 Refresh Token이 반환된다
**And** 기존 Refresh Token은 새 Token Pair 반환 전에 폐기된다
**And** 새 Refresh Token 해시가 저장된다

**Given** 동일 Refresh Token으로 동시 재발급 요청이 발생했을 때
**When** Rotation을 수행하면
**Then** `SELECT ... FOR UPDATE` 기반 잠금으로 하나의 요청만 정상 Rotation을 수행한다
**And** 검증·기존 토큰 폐기·새 토큰 저장이 일관된 트랜잭션으로 처리된다

**Given** 이미 폐기된 Refresh Token이 재사용되었을 때
**When** 재발급을 요청하면
**Then** 사용자의 활성 Refresh Token 전체가 폐기된다
**And** 폐기 처리는 인증 예외로 롤백되지 않는다
**And** `401 AUTH_REFRESH_TOKEN_REUSED`가 반환된다

**Given** 만료·변조·미존재 Refresh Token이 제출되었을 때
**When** 재발급을 요청하면
**Then** `401 AUTH_REFRESH_TOKEN_INVALID`가 반환된다
**And** 토큰 또는 계정 존재 여부를 추가로 노출하지 않는다

**Given** Swagger가 활성화된 환경일 때
**When** 로그인과 Refresh Token 재발급 문서를 확인하면
**Then** Token Pair 응답, Rotation, 오류 응답이 문서화된다
**And** 실제 JWT, Refresh Token, 비밀번호 및 Secret은 예시에 포함되지 않는다

**Given** Story 1.4 테스트가 실행될 때
**When** 단위·Repository·Rotation·동시성·API 통합 테스트가 완료되면
**Then** 해시 저장, 정상 재발급, 기존 토큰 폐기, 만료·변조·미존재·폐기·재사용, 전체 활성 토큰 폐기, 원문 비저장이 검증된다

**비범위:** 로그아웃, 기기별 token-family 관리, Refresh Token 쿠키 전환

### Story 1.5: 로그아웃과 인증 보안 마무리

As a 로그인 사용자,
I want 현재 Refresh Token 세션을 안전하게 종료하고,
So that 더 이상 해당 토큰으로 인증을 갱신할 수 없게 할 수 있다.

**목적:** 로그아웃과 횡단 보안 정책을 완성하고 인증 에픽 전체를 종단 검증한다.

**범위:** `/api/auth/logout`, 멱등 폐기, CORS allowlist, 민감정보 비노출, Swagger 로그아웃 문서, 전체 E2E 테스트, README

**Acceptance Criteria:**

**Given** 유효한 Refresh Token이 있을 때
**When** `POST /api/auth/logout`을 호출하면
**Then** 제출 토큰이 폐기된다
**And** `204 No Content`가 반환된다
**And** 이후 해당 토큰의 재발급은 `401 AUTH_REFRESH_TOKEN_INVALID`로 실패한다

**Given** 이미 폐기되었거나 존재하지 않거나 형식이 잘못된 Refresh Token일 때
**When** 로그아웃을 반복 호출하면
**Then** 계정·토큰 존재 여부를 노출하지 않고 멱등 `204 No Content`가 반환된다

**Given** 로그아웃이 완료되었을 때
**When** 기존 Access Token으로 보호 API를 호출하면
**Then** Access Token은 만료 전 최대 15분간 기술적으로 유효할 수 있다
**And** 이 계약과 Access Token 블랙리스트 비범위가 문서화된다

**Given** CORS 설정이 적용될 때
**When** 허용 출처를 평가하면
**Then** `CORS_ALLOWED_ORIGINS` 환경변수 allowlist만 허용된다
**And** 운영 환경에서 wildcard origin은 허용되지 않는다
**And** 허용 메서드와 헤더는 API 계약에 필요한 범위로 제한된다

**Given** 인증 API와 실패 시나리오가 실행될 때
**When** 응답과 로그를 검사하면
**Then** 비밀번호, `password_hash`, JWT, Refresh Token, Authorization 헤더, Secret, 내부 예외 상세와 stack trace가 노출되지 않는다

**Given** Swagger가 활성화된 환경일 때
**When** 로그아웃 API 문서를 확인하면
**Then** 멱등 `204`, 로그아웃 후 재발급 실패, Access Token 잔여 유효성 계약이 문서화된다
**And** 실제 JWT, Refresh Token, 비밀번호 및 Secret은 예시에 포함되지 않는다

**Given** 인증 에픽 전체 종단 테스트가 실행될 때
**When** 테스트가 완료되면
**Then** 회원가입 → 로그인 → `/api/users/me` 성공 흐름이 통과한다
**And** 이메일 변형 중복 가입, 공개 ADMIN 생성 거부, 정상·누락·변조·만료 JWT가 검증된다
**And** USER 관리자 API `403`, ADMIN `200`이 검증된다
**And** Rotation 후 이전 토큰 재사용과 활성 토큰 전체 폐기가 검증된다
**And** 로그아웃 후 재발급 실패와 민감정보 비노출이 검증된다

**Given** Swagger UI로 인증 흐름을 시연할 때
**When** 회원가입 → 로그인 → Access Token Authorize → `/api/users/me`를 호출하면
**Then** 전체 흐름이 성공한다
**And** USER 토큰으로 `/api/admin/ping` 호출 시 `403`을 확인할 수 있다
**And** 운영 프로필에서는 Swagger UI와 `/v3/api-docs`에 접근할 수 없다

**Given** 제출용 README를 확인할 때
**When** 인증 실행 안내를 따르면
**Then** 필요한 환경변수 형식, 실행 프로필, 인증 API, Swagger 사용법, 테스트 실행법, 초기 ADMIN 정책을 이해할 수 있다
**And** 실제 Secret이나 자격 증명은 포함되지 않는다

**비범위:** Access Token 블랙리스트, 기기별 로그아웃, 운영 ADMIN 관리 API

## Epic 2: 나만의 학습 캐릭터 만들기와 관리

사용자는 자신의 학습을 표현할 캐릭터를 생성·조회·수정·삭제하고 현재 성장시킬 활성 캐릭터를 지정할 수 있다. AI 이미지 생성 실패 시에도 Fallback 이미지로 캐릭터 생성 흐름을 완료할 수 있다.

**계획 상태:** 잠정. Story 2.1만 초기 초안으로 승인되었으며, 나머지 스토리는 Epic 1 구현 완료 후 작성한다.

### Story 2.1: 첫 캐릭터 생성 및 자동 활성화

As a 신규 사용자,
I want 나를 표현하는 첫 캐릭터를 만들고,
So that 학습 결과를 성장으로 확인할 준비를 할 수 있다.

**Acceptance Criteria:**

**Given** 사용자가 캐릭터를 보유하지 않았을 때
**When** 유효한 이름·디자인 키워드·성격으로 캐릭터를 생성하면
**Then** 캐릭터가 생성되고 자동으로 활성화된다
**And** 다섯 능력치는 0에서 시작한다

**Given** 사용자가 이미 캐릭터 3개를 보유했을 때
**When** 추가 생성을 요청하면
**Then** 생성이 거부되고 사유가 반환된다

**Given** 이미지 생성이 성공했을 때
**When** 결과가 반영되면
**Then** 현재 진화·감정 상태에 맞는 이미지가 제공된다

**Given** 이미지 생성이 실패하거나 지연될 때
**When** 캐릭터 생성을 요청하면
**Then** Fallback 이미지 또는 명확한 처리 상태가 제공된다
**And** 캐릭터 생성 자체는 성공한다

**Given** 다른 사용자가 생성된 캐릭터에 접근할 때
**When** 비공개 캐릭터 조회·수정을 시도하면
**Then** 접근이 허용되지 않는다

**잠정 조건:** AI 이미지 호출 방식·상태 모델·계약은 PRD와 Architecture 충돌 해결 후 확정한다.

## 후속 스토리 작성 보류

- Epic 2의 Story 2.2 이후와 Epic 3~6의 상세 스토리는 Epic 1 구현 완료 후 작성한다.
- 보류 기간에는 Epic 2~6의 에픽 목록과 FR Coverage Map을 잠정 계획으로만 사용한다.
- 후속 스토리 작성 재개 전 PRD와 Architecture의 AI 채점·이미지 처리 방식 충돌을 해결한다.
- 재개 시 `FR → Epic → Story → Acceptance Criteria → Test`, 관련 NFR, 핵심 사용자 여정 및 의존성 맵을 갱신한다.
