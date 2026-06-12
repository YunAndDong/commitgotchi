---
baseline: Story 1.4 review 작업 트리 (회원가입, 로그인, JWT Access Token, Role 인가, Refresh Token Rotation)
baseline_commit: NO_VCS
---

# Story 1.5: 로그아웃과 인증 보안 마무리

Status: done

## Story

As a 로그인 사용자,
I want 현재 Refresh Token 세션을 안전하게 종료하고,
so that 더 이상 해당 토큰으로 인증을 갱신할 수 없게 할 수 있다.

## 목적과 범위

- `POST /api/auth/logout`에서 제출된 Refresh Token 세션을 멱등 종료하고 항상 `204 No Content`를 반환한다.
- 로그아웃한 Refresh Token은 이후 `/api/auth/refresh`에서 `401 AUTH_REFRESH_TOKEN_INVALID`로 실패하게 한다.
- `CORS_ALLOWED_ORIGINS` 기반의 정확한 origin allowlist를 Spring Security에 연결하고 운영 환경에서 wildcard·비 HTTPS origin·빈 allowlist를 거부한다.
- 인증 API·오류·로그의 민감정보 비노출 계약을 전체 인증 에픽 수준에서 검증한다.
- Swagger 로그아웃 계약과 제출용 `README.md`의 인증 실행·시연·테스트·초기 ADMIN 안내를 완성한다.
- Story 1.1~1.4의 회원가입, JWT 보호 API, Role 인가, Refresh Token Rotation·재사용 탐지를 보존하고 인증 에픽 전체 승인 시나리오를 자동화한다.
- 비범위: Access Token 블랙리스트, 기기별/전체 기기 로그아웃, Refresh Token cookie 전환, token-family, 운영 ADMIN 관리 API, 새 도메인 테이블.

## Acceptance Criteria

1. **유효한 Refresh Token 로그아웃과 이후 재발급 차단**
   - **Given** 활성 상태이며 만료되지 않은 Refresh Token이 있을 때
   - **When** `POST /api/auth/logout`에 해당 원문 토큰을 제출하면
   - **Then** 해당 Refresh Token 세션이 종료되고 `204 No Content`가 빈 본문으로 반환된다.
   - **And** 같은 토큰으로 `/api/auth/refresh`를 호출하면 공통 오류 형식의 `401 AUTH_REFRESH_TOKEN_INVALID`가 반환된다.
   - **And** 다른 활성 Refresh Token 세션은 로그아웃되지 않으며 계속 Rotation할 수 있다.
   - **And** 로그아웃 경로는 Access Token 없이 호출 가능한 `permitAll` 경로다.

2. **존재 여부를 노출하지 않는 멱등 로그아웃**
   - **Given** 이미 로그아웃됐거나, DB에 존재하지 않거나, 43자 Base64url 형식이 아닌 Refresh Token 문자열이 있을 때
   - **When** 동일 요청을 한 번 이상 `POST /api/auth/logout`에 제출하면
   - **Then** 모든 호출은 토큰·계정 존재 여부를 노출하지 않고 `204 No Content` 빈 본문을 반환한다.
   - **And** raw token을 응답·로그·DB에 새로 저장하지 않는다.
   - **And** missing/null `refreshToken`, 잘못된 JSON, unknown field는 기존 DTO 계약대로 `400 VALIDATION_FAILED`지만, 값이 존재하는 malformed token 문자열은 멱등 `204`로 처리한다.
   - **And** DB/트랜잭션 인프라 장애는 성공으로 가장하지 않고 기존 안전한 `500 INTERNAL_SERVER_ERROR` 계약으로 처리한다.

3. **로그아웃 후 Access Token 잔여 유효성 계약**
   - **Given** Token Pair를 발급받은 사용자가 Refresh Token으로 로그아웃했을 때
   - **When** 로그아웃 전에 발급된 Access Token으로 만료 전 보호 API를 호출하면
   - **Then** 무상태 JWT Access Token은 최대 15분 동안 기술적으로 유효하여 보호 API 호출이 성공할 수 있다.
   - **And** Access Token 블랙리스트와 즉시 무효화는 비범위다.
   - **And** 이 동작은 OpenAPI와 `README.md`에 명시되고 자동화 테스트로 고정된다.

4. **환경변수 기반 최소 CORS allowlist**
   - **Given** `CORS_ALLOWED_ORIGINS`에 쉼표로 구분된 정확한 origin 목록이 설정됐을 때
   - **When** 허용 origin에서 `/api/**` preflight 또는 실제 요청을 보내면
   - **Then** 해당 origin만 `Access-Control-Allow-Origin`으로 반환된다.
   - **And** 허용 메서드는 현재 인증 증분에 필요한 `GET`, `POST`, `OPTIONS`로 제한된다.
   - **And** 허용 요청 헤더는 `Authorization`, `Content-Type`으로 제한되고 credentials/cookie CORS는 활성화하지 않는다.
   - **And** 허용되지 않은 origin은 CORS 응답 헤더를 받지 못하고 preflight가 거부된다.
   - **And** CORS preflight는 인증되지 않았다는 이유로 JWT EntryPoint에서 먼저 거부되지 않는다.

5. **운영 CORS fail-fast와 설정 전달**
   - **Given** `prod` 프로필로 애플리케이션을 시작할 때
   - **When** `CORS_ALLOWED_ORIGINS`가 비어 있거나 `*`, origin pattern, 비 HTTPS origin, path/query/fragment가 포함된 값을 사용하면
   - **Then** 애플리케이션은 안전하지 않은 CORS 설정을 허용하지 않고 시작에 실패한다.
   - **And** 정확한 HTTPS origin만 운영 allowlist로 허용한다.
   - **And** `local`, `dev`, `test`에서는 명시적인 localhost HTTP origin을 사용할 수 있으나 wildcard는 허용하지 않는다.
   - **And** `.env.example`, `docker-compose.yml`, `application.yml`은 실제 origin이나 Secret을 하드코딩하지 않고 설정 형식과 전달 경로를 제공한다.

6. **인증 횡단 민감정보 비노출**
   - **Given** 회원가입·로그인·JWT 검증·인가·재발급·로그아웃의 성공 및 실패 시나리오가 실행될 때
   - **When** HTTP 응답, 캡처 로그, OpenAPI 문서를 검사하면
   - **Then** 비밀번호, `password_hash`, Access/Refresh Token 원문, token hash, Authorization 헤더, JWT Secret, DB 자격 증명이 노출되지 않는다.
   - **And** 내부 예외 상세, 라이브러리 예외 메시지, stack trace가 클라이언트 응답이나 기본 인증 로그에 노출되지 않는다.
   - **And** 안전한 placeholder와 공통 오류 코드·traceId만 외부 계약에 나타난다.

7. **로그아웃 OpenAPI와 Swagger 시연 계약**
   - **Given** Swagger가 활성화된 `local`, `dev`, `test` 환경일 때
   - **When** `/api/auth/logout` 문서를 확인하면
   - **Then** 로그아웃 operation은 Bearer 보안 요구가 없는 공개 operation으로 표시된다.
   - **And** 요청은 안전한 `<refresh-token>` placeholder를 사용하고 멱등 `204` 빈 응답이 문서화된다.
   - **And** 로그아웃 후 Refresh Token 재발급 실패와 Access Token 잔여 유효성/블랙리스트 비범위가 설명된다.
   - **And** 회원가입 → 로그인 → Access Token Authorize → `/api/users/me` 성공과 USER의 `/api/admin/ping` `403`을 Swagger UI에서 시연할 수 있다.
   - **And** 기본·`prod` 및 `prod+dev` 프로필에서는 Swagger UI와 `/v3/api-docs`가 계속 비활성화된다.

8. **인증 에픽 전체 종단 승인 테스트**
   - **Given** 인증 에픽 전체 테스트가 실행될 때
   - **When** 단위·Repository/DB·Spring Security·API 통합·OpenAPI·CORS·종단 테스트가 완료되면
   - **Then** 회원가입 → 로그인 → `/api/users/me` → Rotation → 로그아웃 → 로그아웃 토큰 재발급 실패 흐름이 통과한다.
   - **And** 이메일 공백/대소문자 변형 중복 가입, 공개 ADMIN 생성 거부, 정상·누락·변조·만료 JWT가 검증된다.
   - **And** USER 관리자 API `403`, ADMIN `200`, Rotation 후 이전 토큰 재사용 및 활성 토큰 전체 폐기가 검증된다.
   - **And** 로그아웃 멱등성, 세션 간 격리, Access Token 잔여 유효성, CORS allowlist/fail-fast, 민감정보 비노출이 검증된다.
   - **And** Story 1.1~1.4의 기존 테스트가 회귀 없이 모두 통과한다.

9. **제출용 README 인증 안내**
   - **Given** 새 개발자 또는 평가자가 저장소의 `README.md`를 확인할 때
   - **When** 인증 실행 안내를 따르면
   - **Then** 필수 환경변수 형식, Docker/로컬 실행 프로필, 인증 API 목록, Swagger 사용법, 테스트 실행법을 이해할 수 있다.
   - **And** Access Token 15분·Refresh Token 30일·Rotation·로그아웃 후 Access Token 잔여 유효성 계약을 이해할 수 있다.
   - **And** 초기 ADMIN은 공개 API/Flyway가 아니라 제한된 운영 DB/CLI 절차로 프로비저닝하며 테스트에서는 fixture만 사용함을 이해할 수 있다.
   - **And** README의 오래된 “구현 미착수”, 과거 경로, 과거 인증/AI 계약 설명을 현재 상태와 충돌하지 않게 정리한다.
   - **And** 실제 Secret, 토큰, 비밀번호, 운영 자격 증명은 포함되지 않는다.

## Tasks / Subtasks

- [x] **Task 1: 멱등 로그아웃 유스케이스를 구현한다** (AC: 1, 2, 3)
  - [x] 기존 `RefreshTokenRequest`를 재사용해 `POST /api/auth/logout`을 `AuthController`에 추가하고 `204 No Content` 빈 응답을 반환한다.
  - [x] `RefreshTokenService`에 `revokeIfPresent` 또는 의미가 동일한 로그아웃 전용 메서드를 추가한다. 유효 형식 문자열이면 SHA-256 hash만 Repository에 전달하고 malformed 문자열이면 조용히 반환한다.
  - [x] 로그아웃은 `rotate()`를 호출하거나 `InvalidRefreshTokenException`/`ReusedRefreshTokenException`으로 토큰 상태를 노출하지 않는다.
  - [x] **현재 스키마에는 Rotation 폐기와 로그아웃 폐기를 구분하는 reason 컬럼이 없다.** Rotation 폐기 행은 재사용 탐지를 위해 보존하되, 로그아웃 대상 활성 행만 `token_hash` + `revoked_at IS NULL` 조건으로 멱등 삭제하여 이후 refresh가 `AUTH_REFRESH_TOKEN_INVALID`가 되게 한다.
  - [x] 이미 Rotation으로 폐기된 행을 logout 요청으로 삭제하지 않는다. 해당 요청은 `204`지만 재사용 탐지 증거는 보존되어 이후 refresh가 계속 `AUTH_REFRESH_TOKEN_REUSED`로 처리돼야 한다.
  - [x] hash 기준 삭제는 token 소유 사용자나 상태를 반환하지 않고, 다른 사용자/다른 세션의 Refresh Token을 건드리지 않는다.
  - [x] 로그아웃 DB 작업은 트랜잭션으로 처리하되 delete count가 0인 경우도 성공으로 취급한다. DB 장애는 삼키지 않는다.
  - [x] 새 Flyway 마이그레이션이나 `revocation_reason` 컬럼은 추가하지 않는다. 필요해지면 별도 ADR/스토리로 다룬다.

- [x] **Task 2: 로그아웃을 공개 인증 경로로 안전하게 연결한다** (AC: 1, 2, 3)
  - [x] `SecurityConfig`의 공개 인증 경로에 `/api/auth/logout`을 추가한다.
  - [x] `JwtAuthenticationFilter.PUBLIC_AUTH_PATHS`에도 `/api/auth/logout`을 추가해 만료·무효 Access Token 헤더가 실려 있어도 Refresh Token 로그아웃을 막지 않게 한다.
  - [x] `/api/auth/logout`만 필터 우회하며 `/api/users/me`, `/api/admin/**`, 그 외 `/api/**`의 기존 인증/인가 정책은 보존한다.
  - [x] 로그아웃 후 기존 Access Token으로 `/api/users/me`가 만료 전 성공하는 계약 테스트를 추가한다.

- [x] **Task 3: 환경변수 기반 CORS 구성과 운영 검증을 추가한다** (AC: 4, 5)
  - [x] `security` 패키지에 CORS 설정 컴포넌트/프로퍼티를 추가하고 `CORS_ALLOWED_ORIGINS` CSV를 trim·빈 값 제거 후 exact origin 목록으로 파싱한다.
  - [x] origin은 URI 구조로 검증해 scheme+host(+명시 port)만 허용하고 path/query/fragment/user-info/패턴을 거부한다.
  - [x] 모든 프로필에서 `*`와 wildcard/pattern origin을 거부한다. `prod`에서는 비어 있는 목록과 HTTPS가 아닌 origin도 시작 시 거부한다.
  - [x] `CorsConfigurationSource`를 `/api/**`에 등록하고 `SecurityFilterChain`에 `.cors(...)`를 연결한다. Controller별 `@CrossOrigin`을 흩뿌리지 않는다.
  - [x] allowed methods=`GET,POST,OPTIONS`, allowed headers=`Authorization,Content-Type`, allowCredentials=`false`로 고정한다.
  - [x] 허용/비허용 origin, 실제 요청, preflight, Authorization header preflight, prod fail-fast를 자동화 테스트로 검증한다.
  - [x] `.env.example`에 안전한 형식 예시를 추가하고 `docker-compose.yml`이 `CORS_ALLOWED_ORIGINS`를 Spring Boot 컨테이너에 전달하게 한다.

- [x] **Task 4: 로그아웃 OpenAPI 계약을 완성한다** (AC: 3, 7)
  - [x] `AuthController`에 로그아웃 목적, 멱등 `204`, 로그아웃 후 Refresh Token 재발급 실패, Access Token 최대 15분 잔여 유효성을 설명한다.
  - [x] 로그아웃 operation에는 Bearer security requirement를 붙이지 않고 요청 예시는 기존 `<refresh-token>` placeholder만 사용한다.
  - [x] OpenAPI 계약 테스트에 `/api/auth/logout`, 공개 operation, `204`, 빈 성공 content, 안전한 placeholder와 잔여 Access Token 설명을 추가한다.
  - [x] 기존 signup/login/refresh/me/admin 문서와 프로필별 Swagger 비활성화 테스트를 보존한다.

- [x] **Task 5: 로그아웃·CORS·민감정보 테스트를 추가한다** (AC: 1~8)
  - [x] 로그아웃 API 통합 테스트: 활성 토큰 종료, 이후 refresh `AUTH_REFRESH_TOKEN_INVALID`, 반복/미존재/malformed 문자열 `204`, missing/null/unknown field `400`을 검증한다.
  - [x] 세션 격리 테스트: 같은 사용자에게 발급된 두 Refresh Token 중 하나만 로그아웃하고 다른 토큰은 정상 Rotation됨을 검증한다.
  - [x] Rotation으로 폐기된 토큰에 logout을 호출해도 폐기 행이 삭제되지 않고 이후 refresh 재사용 탐지와 활성 토큰 전체 폐기가 유지되는지 검증한다.
  - [x] 만료/무효 Access Token Authorization 헤더를 로그아웃 요청에 함께 보내도 `204`가 반환되는지 검증한다.
  - [x] 응답 `204`의 본문이 비어 있고 raw Refresh Token·hash·계정 존재 여부가 응답/로그에 나타나지 않는지 검증한다.
  - [x] CORS 통합 테스트는 `Origin` + `Access-Control-Request-Method/Headers`를 사용해 허용 origin preflight와 비허용 origin 거부를 실제 SecurityFilterChain에서 검증한다.
  - [x] 운영 CORS 설정 오류는 작은 구성 단위 테스트 또는 별도 `ApplicationContextRunner`/프로필 시작 테스트로 fail-fast를 검증한다.
  - [x] 인증 횡단 민감정보 비노출 검증을 보강하되 테스트 실패 메시지 자체에 실제 token/secret을 출력하지 않게 주의한다.

- [x] **Task 6: 인증 에픽 종단 승인 시나리오를 고정한다** (AC: 8)
  - [x] `AuthenticationEpicEndToEndIntegrationTest` 또는 동등한 단일 종단 테스트에서 가입 → 로그인 → `/me` → refresh → logout → logout token refresh 실패를 검증한다.
  - [x] 기존 테스트가 이미 상세히 검증하는 중복 가입, ADMIN 주입 거부, JWT 오류, Role 인가, Rotation 재사용을 삭제하거나 약화하지 않는다.
  - [x] 종단 테스트는 PostgreSQL Testcontainers와 실제 SecurityFilterChain/Flyway를 사용하고 H2·mock Repository로 대체하지 않는다.
  - [x] `./gradlew clean test`로 전체 인증 회귀를 실행한다.

- [x] **Task 7: 제출용 README를 현재 구현에 맞게 갱신한다** (AC: 3, 7, 9)
  - [x] 오래된 “구현 미착수”, `backend-spring/` 권장 경로, 인증 API/계약 누락과 현재 아키텍처에 충돌하는 설명을 정리한다.
  - [x] `JWT_SECRET_BASE64`, `JWT_ISSUER`, `CORS_ALLOWED_ORIGINS`, DB 환경변수의 형식과 안전한 생성법만 설명한다.
  - [x] Docker Compose 및 `springboot/gradlew` 실행, `local|dev|test` Swagger 활성화, 기본/prod Swagger 비활성화, 테스트 실행법을 설명한다.
  - [x] 인증 API 6개와 성공/실패 의미, Token Pair/Rotation/로그아웃, Access Token 잔여 유효성을 설명한다.
  - [x] 초기 ADMIN은 공개 가입/Flyway가 아닌 제한된 운영 DB/CLI 절차이며 테스트 fixture만 자동 프로비저닝함을 명시한다.
  - [x] cURL/JSON 예시는 placeholder만 사용하고 실제 Secret·토큰·비밀번호를 넣지 않는다.

## Review Findings

코드 리뷰 (2026-06-12, Blind Hunter / Edge Case Hunter / Acceptance Auditor 인라인 수행). 진단 대상: Story File List 전체 내용 (baseline_commit=NO_VCS이므로 git diff 불가). 9개 AC 모두 코드·테스트로 충족됨. HIGH/MEDIUM 결함 없음.

- [x] [Review][Patch] 비-prod 빈 CORS allowlist가 조용히 적용됨 [springboot/src/main/java/com/commitgotchi/security/CommitgotchiCorsConfiguration.java] — `local/dev/test`에서 `CORS_ALLOWED_ORIGINS`가 비면 allowlist가 빈 채로 부팅되어 모든 브라우저 요청이 CORS 헤더 없이 거부된다. prod는 fail-fast하지만 비-prod는 무경고라 개발자가 원인 파악에 시간을 쓸 수 있다. **수정(2026-06-12):** 생성자에서 비-prod & 빈 allowlist면 시작 시 `log.warn` 1줄 출력하도록 추가(보안 동작은 의도대로 유지). RESOLVED.
- [x] [Review][Dismiss] http origin에서 IPv6 루프백 `[::1]` 미허용 — 스펙이 localhost/127.0.0.1만 요구하므로 의도된 범위. dismiss.
- [x] [Review][Dismiss] `uri.getPort() <= 65535`가 포트 0(`http://localhost:0`)을 통과시킴 — 실질적 위험 없음. dismiss.
- [x] [Review][Dismiss] `application.yml`의 `fail-on-unknown-properties:true`와 DTO `@JsonIgnoreProperties(ignoreUnknown=false)` 중복 — defense-in-depth로 무해. dismiss.

## Dev Notes

### Developer Context

- 현재 작업 트리는 Story 1.4 구현과 리뷰 보강까지 완료된 상태다. `POST /api/auth/signup|login|refresh`, `GET /api/users/me`, `GET /api/admin/ping`, Refresh Token Rotation·재사용 탐지, 프로필별 Swagger 정책이 이미 동작한다.
- 로그아웃은 “Refresh Token을 검증해 사용자에게 오류를 알려주는 API”가 아니라 “제출된 credential이 존재하면 조용히 사용할 수 없게 만드는 API”다. malformed/미존재/이미 종료 토큰 문자열은 모두 동일 `204`다.
- **가장 중요한 상태 모델 함정:** 현재 `refresh_tokens`는 `revoked_at`만 있어 폐기 이유를 구분하지 못한다. 기존 `rotate()`는 모든 revoked row를 재사용 공격으로 분류한다. 따라서 로그아웃에서 단순 `revoked_at` 업데이트를 하면 이후 refresh가 AC의 `AUTH_REFRESH_TOKEN_INVALID`가 아니라 `AUTH_REFRESH_TOKEN_REUSED`가 되고 사용자의 다른 활성 토큰까지 폐기한다.
- 이 스토리에서는 Rotation 폐기 행을 보존해 Story 1.4 재사용 방어를 유지하고, 로그아웃 대상 행만 hash 기준 삭제한다. 이는 현재 외부 계약과 스키마를 모두 보존하는 최소 변경이다.
- 로그아웃은 한 세션만 종료한다. 사용자 ID 기반 전체 활성 토큰 폐기 메서드인 `RefreshTokenReuseRevocationService.revokeAllActive(...)`를 재사용하면 안 된다.
- `/api/auth/logout`은 Access Token이 만료된 후에도 Refresh Token을 종료할 수 있어야 한다. `SecurityConfig.permitAll`만 추가하면 부족하며, 현재 `JwtAuthenticationFilter.shouldNotFilter` 공개 경로 집합에도 추가해야 한다.
- CORS는 현재 코드에 구성되어 있지 않다. Vue 브라우저 클라이언트가 다른 origin에서 Spring Boot를 호출하려면 CORS를 명시적으로 활성화해야 하며, preflight는 인증보다 먼저 처리돼야 한다.
- Refresh Token은 JSON body로 전달하고 cookie를 사용하지 않으므로 `allowCredentials=false`를 유지한다. 향후 HttpOnly cookie 전환 시 CORS와 CSRF 결정을 별도 재검토한다.
- README는 현재 구현 미착수와 과거 AI 계약을 설명해 제출용 안내로 사용할 수 없다. 이 스토리는 인증 실행·검증 섹션을 중심으로 최신화하며, 인증과 무관한 제품 설명을 과도하게 재작성하지 않는다.

### Current Files To Update

- `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`
  - 현재: signup/login/refresh 공개 API와 OpenAPI 문서를 제공한다.
  - 변경: logout operation과 `204` 응답을 추가한다.
  - 보존: 기존 세 operation의 요청/응답/오류/OpenAPI 계약.
- `springboot/src/main/java/com/commitgotchi/auth/application/RefreshTokenService.java`
  - 현재: 토큰 생성·해시·저장과 행 잠금 Rotation·재사용 탐지를 담당한다.
  - 변경: raw token을 노출하지 않는 멱등 로그아웃 메서드를 추가한다.
  - 보존: 32-byte 생성, SHA-256 hash, 정상 Rotation 원자성, revoked token 재사용 전체 폐기.
- `springboot/src/main/java/com/commitgotchi/auth/domain/RefreshTokenRepository.java`
  - 현재: hash 행 잠금 조회와 사용자별 활성 토큰 전체 폐기를 제공한다.
  - 변경: 로그아웃 전용 hash 기준 멱등 삭제 쿼리를 추가한다.
  - 보존: Rotation 잠금 조회는 폐기 행까지 반환해야 하며 active 조건을 추가하지 않는다.
- `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`
  - 현재: stateless/CSRF 비활성화, signup/login/refresh 공개, Role 경로 정책, JWT 필터 순서를 구성한다.
  - 변경: logout 공개 경로와 CORS source를 연결한다.
  - 보존: EntryPoint/DeniedHandler, Swagger/health 정책, admin/user/default 보호 정책.
- `springboot/src/main/java/com/commitgotchi/security/JwtAuthenticationFilter.java`
  - 현재: signup/login/refresh에서 JWT 필터를 우회해 body credential 흐름을 보호한다.
  - 변경: logout도 같은 공개 인증 경로 집합에 포함한다.
  - 보존: 보호 API의 누락/변조/만료 JWT 코드와 SecurityContext 설정.
- `springboot/src/main/resources/application.yml`
  - 현재: DB/Flyway/JPA, Swagger 프로필, JWT 설정을 제공한다.
  - 변경: CORS allowed origins 설정 바인딩을 추가한다.
  - 보존: `ddl-auto=validate`, Swagger의 prod 우선 비활성화, JWT 15분 계약.
- `.env.example`, `docker-compose.yml`
  - 현재: JWT/DB 설정 형식과 컨테이너 전달을 제공한다.
  - 변경: `CORS_ALLOWED_ORIGINS` 형식과 Spring Boot 전달을 추가한다.
  - 보존: 실제 Secret/운영 origin 비포함.
- `README.md`
  - 현재: 구현 미착수·과거 경로/계약을 설명한다.
  - 변경: 현재 인증 구현 실행·시연·테스트·보안 계약을 제출 가능한 수준으로 갱신한다.
  - 보존: 제품 비전과 팀 책임의 유효한 설명.
- `springboot/src/test/java/com/commitgotchi/auth/RefreshTokenApiIntegrationTest.java`
  - 현재: Rotation, 재사용, 동시성, 원문 비저장/비노출을 검증한다.
  - 변경: 필요 시 로그아웃 후 refresh 분류와 세션 격리 회귀를 연결한다.
  - 보존: 실제 PostgreSQL 동시 Rotation 테스트.
- `springboot/src/test/java/com/commitgotchi/swagger/JwtOpenApiContractIntegrationTest.java`
  - 현재: login/refresh/me Bearer와 Token Pair 계약을 검증한다.
  - 변경: logout 공개 `204` 계약을 추가한다.
  - 보존: 기존 Bearer/Token Pair/오류 문서 검증.

### Expected New Files

```text
springboot/src/main/java/com/commitgotchi/security/
└── CommitgotchiCorsConfiguration.java

springboot/src/test/java/com/commitgotchi/
├── auth/
│   ├── LogoutApiIntegrationTest.java
│   └── AuthenticationEpicEndToEndIntegrationTest.java
└── security/
    └── CorsConfigurationIntegrationTest.java 또는 동등한 CORS 테스트
```

- 클래스명과 테스트 분리는 기존 코드 스타일에 맞게 조정할 수 있다.
- `LogoutException`, 로그아웃 전용 오류 코드, Access Token blacklist, V3 마이그레이션은 만들지 않는다.

### Technical Requirements

- **로그아웃 요청**

```http
POST /api/auth/logout
Content-Type: application/json
```

```json
{ "refreshToken": "<refresh-token>" }
```

```http
HTTP/1.1 204 No Content
```

- **로그아웃 상태 전이:** DB에 일치하는 활성 hash row(`revoked_at IS NULL`)가 있으면 삭제, 없거나 이미 폐기됐으면 no-op. 성공 응답은 모든 경우 동일하다.
- **로그아웃 후 refresh:** 삭제된 hash는 잠금 조회에서 미존재이므로 기존 `InvalidRefreshTokenException` 경로를 통해 `401 AUTH_REFRESH_TOKEN_INVALID`가 된다.
- **Rotation 재사용:** Rotation으로 `revoked_at`이 설정된 행은 logout 요청이 와도 계속 보존되어 기존 `AUTH_REFRESH_TOKEN_REUSED` 및 사용자 활성 토큰 전체 폐기를 수행한다.
- **세션 격리:** 로그아웃은 userId 기준 전체 삭제가 아니라 제출 raw token의 SHA-256 hash 하나만 대상으로 한다.
- **유효성 분류:** request body missing/null/invalid JSON/unknown field=`400`; non-null malformed token string=`204`; 모든 존재/상태 경우=`204`; 인프라 실패=`500`.
- **CORS origin 형식:** exact origin은 `scheme://host[:port]`만 허용한다. trailing slash를 암묵적으로 정규화해 다른 origin으로 취급하지 말고 잘못된 설정으로 거부하는 쪽을 우선한다.
- **CORS 운영:** `prod`는 하나 이상의 정확한 HTTPS origin 필수. wildcard/pattern/HTTP/path/query/fragment 금지.
- **CORS 로컬:** 명시적인 `http://localhost:<port>` 또는 `http://127.0.0.1:<port>` 허용 가능. wildcard는 금지.
- **CORS 요청 범위:** `/api/**`, methods `GET|POST|OPTIONS`, headers `Authorization|Content-Type`, credentials false.
- **민감정보:** CORS 설정 오류 메시지에도 JWT/DB Secret 또는 요청 token을 포함하지 않는다.

### Architecture Compliance

- 인증 증분 SSOT는 아키텍처 §12이며, 로그아웃 API는 §12.4·§12.6·§12.8.5 계약을 따른다.
- `AuthController`는 Repository나 hash 로직에 직접 의존하지 않고 `RefreshTokenService` 유스케이스만 호출한다.
- Repository 경계에는 raw Refresh Token을 전달하지 않는다. Controller/Application에서 형식 확인·hash 후 Repository에는 hash만 전달한다.
- 로그아웃은 `RefreshTokenReuseRevocationService`를 호출하지 않고 한 세션만 종료한다.
- JWT Access Token 발급·검증·블랙리스트 로직을 변경하지 않는다.
- DB 변경 없이 기존 `users`, `refresh_tokens` 두 테이블만 유지한다.
- CORS는 전역 Security 설정 한 곳에서 관리하고 Controller별 허용 정책으로 분산하지 않는다.
- 운영 unsafe CORS 구성은 조용히 완화하지 않고 시작 시 실패시켜 배포 오류를 드러낸다.

### Library / Framework Requirements

- 기준선은 Java 17, Spring Boot `3.3.5`, Spring Security/Spring Framework Boot 관리 버전, PostgreSQL 16, Flyway, Testcontainers, springdoc `2.6.0`이다.
- 이 스토리에서 버전 업그레이드나 새 런타임 의존성을 추가하지 않는다.
- Spring Framework의 `CorsConfiguration`과 `UrlBasedCorsConfigurationSource`, Spring Security `.cors(...)`를 사용한다.
- origin 파싱은 JDK `URI` 등 구조화 API를 사용하고 문자열 prefix/contains 검사만으로 HTTPS·host를 판정하지 않는다.
- 로그아웃 hash는 기존 `RefreshTokenService.hash(...)`를 재사용하고 새 암호 라이브러리나 중복 hash 구현을 만들지 않는다.
- OpenAPI는 기존 springdoc annotation/계약 테스트 패턴을 유지한다.

### Testing Requirements

- 로그아웃 테스트는 응답 상태만 보지 말고 DB row 제거, 다른 세션 보존, 이후 refresh 오류 코드까지 검증한다.
- 멱등 테스트는 같은 token 로그아웃을 최소 두 번 호출하고 두 응답 모두 빈 `204`인지 확인한다.
- 로그아웃 후 Access Token 테스트는 동일 Token Pair의 Access Token으로 `/api/users/me`를 호출해 여전히 `200`임을 고정한다.
- 로그아웃 요청에 만료/무효 Access Token 헤더를 일부러 포함해 `JwtAuthenticationFilter.shouldNotFilter` 회귀를 검증한다.
- CORS 테스트는 단순 bean 속성 검사 외에 MockMvc preflight를 통해 SecurityFilterChain 순서와 응답 헤더를 검증한다.
- prod CORS fail-fast 테스트는 기존 Swagger prod 테스트 컨텍스트와 충돌하지 않게 명시적인 안전한 HTTPS origin을 주입하거나 설정 검증을 분리한다.
- 민감정보 테스트는 `OutputCaptureExtension`, 응답 본문, `/v3/api-docs`를 검사한다. raw token이나 secret을 assertion 실패 메시지에 출력하지 않는다.
- 전체 회귀 명령은 `cd springboot && ./gradlew clean test`다. Docker Desktop이 실행 중이어야 Testcontainers PostgreSQL 16을 사용할 수 있다.

### Previous Story Intelligence

- Story 1.4는 Refresh Token을 43자 Base64url 문자열로 발급하고 SHA-256 lowercase hex만 DB에 저장한다. 로그아웃은 이 생성·형식·hash 로직을 재사용해야 한다.
- `RefreshTokenRepository.findByTokenHashForUpdate`는 폐기 행까지 조회해 Rotation 재사용을 탐지한다. 로그아웃 구현 때문에 이 쿼리를 active-only로 바꾸면 안 된다.
- `RefreshTokenReuseRevocationService`의 `REQUIRES_NEW` 전체 폐기는 공격성 재사용 대응 전용이다. 일반 로그아웃에 사용하면 다른 세션을 파괴한다.
- Story 1.4 리뷰에서 `JwtAuthenticationFilter.shouldNotFilter`가 signup/login/refresh에 적용됐다. logout도 이 목록에 없으면 만료 Access Token 헤더 때문에 멱등 로그아웃이 실패할 수 있다.
- 현재 `RefreshTokenRequest`는 missing/null을 `400`, unknown field를 `400`으로 처리한다. 로그아웃도 이 JSON 계약을 재사용하고 값이 존재하는 malformed 문자열만 `204`로 흡수한다.
- Story 1.4 최종 기록은 `./gradlew clean test` 63 tests / 0 failures다. Story 1.5 구현 전 기준선으로 사용한다.
- 기존 테스트는 인증 인프라 장애를 안전한 인증 오류로 숨기지 않는 원칙을 보존한다. 로그아웃도 DB 장애를 멱등 성공으로 가장하지 않는다.

### Git Intelligence

- 최근 커밋은 문서 정렬과 초기 골격이며 Story 1.1~1.4 구현은 아직 작업 트리 기준선이다. 기존 사용자 변경과 인증 구현을 되돌리지 않는다.
- 실제 코드 패턴은 `auth/api → auth/application → auth/domain/repository`, `security`, `common/error`, `user`, `admin` 경계를 따른다.
- `README.md`, `.env.example`, `docker-compose.yml`, `springboot/application.yml`, `springboot/build.gradle`에는 기존 작업 트리 변경이 있으므로 전체 파일을 과거 커밋 상태로 교체하지 말고 현재 내용을 바탕으로 수정한다.
- Story 1.5는 인증 에픽 마무리이며 Vue/FastAPI 구현으로 범위를 확산하지 않는다.

### Latest Technical Information

- Spring Security 공식 문서는 CORS가 Security보다 먼저 처리되어야 하며, `UrlBasedCorsConfigurationSource`를 제공하고 Security chain에 CORS를 연결하는 방식을 안내한다.
- Spring Framework 공식 문서는 credentialed CORS가 신뢰/공격 표면을 늘리며 가능한 경우 wildcard보다 유한한 목록을 권장한다. 이 프로젝트는 body/Header token 계약이므로 credentials를 활성화하지 않는다.
- OWASP Logging Cheat Sheet는 access token, 인증 비밀번호, DB connection string, 암호화 키·주요 Secret을 로그에 직접 기록하지 않도록 권고한다.
- 최신 공식 문서의 Spring Security/Framework 버전이 프로젝트 기준선보다 높더라도 이 스토리는 Spring Boot `3.3.5` 호환 API만 사용하고 버전 업그레이드를 수행하지 않는다.

### Project Structure Notes

- 실제 Spring Boot 루트는 `springboot/`다. README와 구현에서 과거 `backend-spring/` 경로를 사용하지 않는다.
- UX 문서는 인증 화면의 상세 로그아웃 UX를 정의하지 않는다. 이 스토리는 Spring Boot API, Swagger, README, 자동화 테스트 범위다.
- Vue의 브라우저 origin은 환경마다 다르므로 Spring Boot 소스에 `http://localhost:5173` 또는 운영 domain을 하드코딩하지 않는다.
- `sprint-status.yaml`은 현재 존재하지 않으므로 Story 상태는 이 파일에서만 `ready-for-dev`로 관리한다.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Epic 1 / Story 1.5]
- [Source: `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md` - FR-25, FR-28, 인증·인가 통합 승인 기준]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md` - §12.4, §12.5, §12.6 로그아웃, §12.7, §12.8.5, §12.10, §12.11]
- [Source: `_bmad-output/implementation-artifacts/1-4-refresh-token-issuance-and-rotation.md`]
- [Source: `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/auth/application/RefreshTokenService.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/auth/domain/RefreshTokenRepository.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/security/JwtAuthenticationFilter.java`]
- [Source: `springboot/src/test/java/com/commitgotchi/auth/RefreshTokenApiIntegrationTest.java`]
- [Source: `springboot/src/test/java/com/commitgotchi/swagger/JwtOpenApiContractIntegrationTest.java`]
- [Source: `README.md`, `.env.example`, `docker-compose.yml`]
- [Spring Security CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)
- [Spring Framework Web MVC CORS](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html)
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)

## Dev Agent Record

### Agent Model Used

GPT-5

### Debug Log References

- 2026-06-12: Story 1.5 create-story workflow completed from Story 1.4 review working tree.
- 2026-06-12: 구현 전 기준선 `./gradlew clean test` 재검증 결과 63 tests / 0 failures / 0 errors.
- 2026-06-12: create-story checklist 재검토에서 Rotation 폐기 행을 logout이 삭제하면 재사용 탐지 증거가 사라지는 엣지를 발견해 active-row-only 삭제 가드레일과 회귀 테스트를 보강함.
- 2026-06-12: 로그아웃/CORS 테스트를 RED로 추가한 뒤 active hash delete, 공개 필터 우회, exact-origin CORS를 구현해 GREEN 전환함.
- 2026-06-12: 최종 `./gradlew clean test` 78 tests / 0 failures / 0 errors 및 `git diff --check` 통과.

### Implementation Plan

- 기존 Refresh Token hash/형식 검증을 재사용하고 로그아웃은 활성 hash 행 하나만 멱등 삭제한다.
- `/api/auth/logout`을 SecurityConfig와 JWT 필터 양쪽의 공개 인증 경로로 연결한다.
- JDK URI 기반 exact-origin 검증과 전역 Security CORS source를 추가한다.
- 로그아웃/OpenAPI/CORS/민감정보/인증 에픽 종단 테스트와 제출용 README를 완성한다.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created
- Story 1.5 상태를 `ready-for-dev`로 설정함
- `sprint-status.yaml`이 없어 스프린트 상태 항목은 갱신하지 않음
- 로그아웃 후 `AUTH_REFRESH_TOKEN_INVALID`와 Rotation 재사용 `AUTH_REFRESH_TOKEN_REUSED`를 동시에 보존하기 위한 현재 스키마 상태 모델 충돌을 식별하고, 로그아웃 hash 행 멱등 삭제를 구현 가드레일로 확정함
- CORS preflight/SecurityFilterChain 순서, 운영 exact HTTPS allowlist fail-fast, credentials 비활성화, 최소 method/header를 구현 가드레일로 반영함
- 실제 수정 대상 코드, Story 1.4 학습, 전체 인증 종단 승인, Swagger/README/민감정보 비노출 요구를 반영함
- create-story checklist 검증과 `git diff --check`를 통과함
- `POST /api/auth/logout` 멱등 `204`와 활성 Refresh Token hash 행 단일 삭제를 구현함
- Rotation 폐기 증거, 세션 격리, 로그아웃 후 Access Token 잔여 유효성, 공개 필터 우회를 통합 테스트로 고정함
- exact-origin CORS allowlist, 운영 HTTPS/비어 있음 fail-fast, 최소 method/header 정책을 구현함
- 로그아웃 OpenAPI 계약, 인증 에픽 종단 테스트, 제출용 README와 환경변수 전달을 완성함
- 최종 `./gradlew clean test` 78 tests / 0 failures / 0 errors로 전체 회귀가 통과함

### File List

- `_bmad-output/implementation-artifacts/1-5-logout-and-authentication-security-finalization.md`
- `.env.example`
- `README.md`
- `docker-compose.yml`
- `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/dto/RefreshTokenRequest.java`
- `springboot/src/main/java/com/commitgotchi/auth/application/RefreshTokenService.java`
- `springboot/src/main/java/com/commitgotchi/auth/domain/RefreshTokenRepository.java`
- `springboot/src/main/java/com/commitgotchi/security/CommitgotchiCorsConfiguration.java`
- `springboot/src/main/java/com/commitgotchi/security/JwtAuthenticationFilter.java`
- `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`
- `springboot/src/main/resources/application.yml`
- `springboot/src/test/java/com/commitgotchi/auth/AuthenticationEpicEndToEndIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/auth/LogoutApiIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/auth/application/RefreshTokenServiceTest.java`
- `springboot/src/test/java/com/commitgotchi/security/CommitgotchiCorsConfigurationTest.java`
- `springboot/src/test/java/com/commitgotchi/security/CorsConfigurationIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/JwtOpenApiContractIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/MixedProdDevSwaggerDisabledIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/ProdSwaggerDisabledIntegrationTest.java`

## Change Log

- 2026-06-12: Story 1.5 로그아웃과 인증 보안 마무리 구현 컨텍스트 작성, 상태 `ready-for-dev`.
- 2026-06-12: 멱등 로그아웃, exact-origin CORS, OpenAPI/종단 테스트, 제출용 README 구현 완료, 상태 `review`.
