---
baseline: Story 1.2 완료 작업 트리 (login, JWT Access Token, /api/users/me, Bearer OpenAPI)
baseline_commit: NO_VCS
---

# Story 1.3: Role 기반 관리자 인가

Status: done

## Story

As an 관리자,
I want 관리자 API에 접근하고 일반 사용자의 접근은 차단되기를 원하며,
so that 권한이 필요한 운영 기능을 안전하게 분리할 수 있다.

## 목적과 범위

- 인증 성공(401)과 권한 부족(403)을 명확히 구분하는 Role 기반 인가를 완성한다.
- `ROLE_USER`/`ROLE_ADMIN` authority 매핑, `GET /api/admin/ping` 최소 관리자 검증 API, `AccessDeniedHandler`, 경로별 인가 정책을 구현한다.
- Story 1.1~1.2의 회원가입, 로그인, HS256 Access Token, JWT 필터, `/api/users/me`, 공통 오류/traceId, Bearer OpenAPI, 프로필별 Swagger 정책을 그대로 보존하고 확장한다.
- 이 스토리는 Access Token 기반 인가만 다룬다. Refresh Token, `refresh_tokens` 테이블, 재발급, 로그아웃, 운영 ADMIN 관리 API는 구현하지 않는다.
- 초기 ADMIN은 공개 API나 운영 Flyway로 만들지 않는다. 테스트 fixture/프로필에서만 ADMIN을 준비한다.

## Acceptance Criteria

1. **USER·ADMIN 모두 일반 보호 API 접근**
   - **Given** 유효한 Access Token을 가진 `USER` 또는 `ADMIN` 사용자가 있을 때
   - **When** `GET /api/users/me`를 호출하면
   - **Then** 두 Role 모두 `200 OK`로 접근할 수 있다.
   - **And** 응답은 각 사용자의 `id`, `email`, `role`을 반환하며 Story 1.2의 principal 계약을 그대로 사용한다.
   - **And** `/api/users/me`는 `hasAnyRole("USER", "ADMIN")` 정책으로 보호된다.

2. **ADMIN의 관리자 API 성공**
   - **Given** 유효한 `ADMIN` Access Token이 `Authorization: Bearer <token>` 헤더에 포함됐을 때
   - **When** `GET /api/admin/ping`을 호출하면
   - **Then** `200 OK`와 `{ "status": "ok" }`가 반환된다.
   - **And** 응답 JSON은 camelCase이며 wrapper 없이 DTO를 직접 반환한다.

3. **USER의 관리자 API 권한 부족(403)**
   - **Given** 유효한 `USER` Access Token이 있을 때
   - **When** `GET /api/admin/ping`을 호출하면
   - **Then** 공통 오류 형식의 `403 AUTH_FORBIDDEN`이 반환된다.
   - **And** 응답은 `status=403`, `code=AUTH_FORBIDDEN`, 안전한 `message`, `timestamp`, `traceId`를 포함한다.
   - **And** 응답과 로그에 JWT 원문, Authorization 헤더, 내부 예외 상세, stack trace가 포함되지 않는다.

4. **미인증(401)과 권한 부족(403)의 명확한 구분**
   - **Given** Access Token이 없거나 누락·변조·만료된 요청일 때
   - **When** `GET /api/admin/ping`을 호출하면
   - **Then** Story 1.2 계약대로 각각 `401 AUTH_ACCESS_TOKEN_MISSING` / `AUTH_ACCESS_TOKEN_INVALID` / `AUTH_ACCESS_TOKEN_EXPIRED`가 반환된다.
   - **And** 인증은 됐으나 Role이 부족한 경우의 `403 AUTH_FORBIDDEN`과 명확히 구분된다.
   - **And** 미인증은 `AuthenticationEntryPoint`(401), 권한 부족은 `AccessDeniedHandler`(403)가 처리하며 두 경로가 혼용되지 않는다.

5. **JWT Claim 기반 Authority 매핑**
   - **Given** Access Token의 서명·형식·알고리즘·issuer·type·필수 Claim 검증이 성공할 때
   - **When** `SecurityContext`의 Authentication이 구성되면
   - **Then** `role` Claim 값에 따라 `ROLE_USER` 또는 `ROLE_ADMIN` authority만 부여된다.
   - **And** 서명·필수 Claim 검증에 실패한 토큰은 어떤 authority도 부여받지 못하고 SecurityContext가 설정되지 않는다.
   - **And** authority 접두사 `ROLE_`와 `hasRole`/`hasAnyRole` 정책이 일관되게 맞물린다.

6. **테스트 전용 ADMIN 프로비저닝**
   - **Given** 개발·테스트 환경에서 ADMIN 사용자가 필요할 때
   - **When** 환경을 준비하면
   - **Then** ADMIN은 테스트 fixture/프로필에서만 생성된다.
   - **And** 공개 가입 API로 ADMIN을 만들 수 없다(회원가입 Role은 항상 `USER`).
   - **And** 운영 Flyway 마이그레이션, 소스코드, Swagger 예시에 관리자 이메일·비밀번호·해시·자격 증명을 포함하지 않는다.

7. **관리자 API OpenAPI 문서와 기존 Swagger 정책 보존**
   - **Given** 애플리케이션이 `local`, `dev`, `test` 프로필로 실행될 때
   - **When** Swagger UI 또는 `/v3/api-docs`에서 `/api/admin/ping`을 확인하면
   - **Then** ADMIN 전용 API임이 명시되고 `bearerAuth` 보안 요구가 표시된다.
   - **And** ADMIN 성공(`200`)과 USER 접근의 `403 AUTH_FORBIDDEN` 응답이 문서화된다.
   - **And** 예시에 실제 ADMIN 토큰, JWT, 비밀번호, Secret이 포함되지 않는다.
   - **Given** 기본 또는 운영 프로필로 실행될 때
   - **When** Swagger UI 또는 `/v3/api-docs`에 접근하면
   - **Then** Story 1.1~1.2와 동일하게 접근할 수 없다.

8. **Story 1.3 자동화 검증과 회귀 보존**
   - **Given** Story 1.3 테스트가 실행될 때
   - **When** Role 인가 통합 테스트가 완료되면
   - **Then** USER·ADMIN의 `/api/users/me` `200`, ADMIN의 `/api/admin/ping` `200`, USER의 `/api/admin/ping` `403 AUTH_FORBIDDEN`, 미인증의 `401`이 각각 검증된다.
   - **And** Story 1.1~1.2의 회원가입, 로그인, JWT 변조·만료·계약 위반, `/api/users/me`, Bearer OpenAPI, 프로필별 Swagger, Flyway V1/JPA validate 테스트가 계속 통과한다.
   - **And** `refresh_tokens` 테이블 또는 V2 이상 마이그레이션이 생성되지 않는다.

## Tasks / Subtasks

- [x] **Task 1: `AUTH_FORBIDDEN` 오류 코드와 `RestAccessDeniedHandler`를 추가한다** (AC: 3, 4)
  - [x] `ErrorCode`에 `AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, <안전한 메시지>)`를 추가한다.
  - [x] `org.springframework.security.web.access.AccessDeniedHandler`를 구현하는 `RestAccessDeniedHandler`를 `security` 패키지에 추가한다.
  - [x] 기존 `SecurityErrorResponseWriter`를 재사용해 `AUTH_FORBIDDEN`을 공통 `ErrorResponse` 형식으로 직렬화한다(새 직렬화 경로를 만들지 않는다).
  - [x] `TraceIdFilter`가 만든 MDC `traceId`를 재사용한다(writer가 MDC `traceId`를 재사용).
  - [x] 핸들러는 예외 메시지·stack trace·Authorization 헤더를 응답이나 로그에 노출하지 않는다.

- [x] **Task 2: `GET /api/admin/ping` 관리자 API를 구현한다** (AC: 2, 7)
  - [x] `admin/api/AdminController.java`를 추가하고 `GET /api/admin/ping`에서 `{ "status": "ok" }` DTO를 직접 반환한다.
  - [x] springdoc `@Operation`/`@ApiResponses`로 ADMIN 전용임과 `200`/`403` 응답을 문서화하고 `@SecurityRequirement(name = "bearerAuth")`를 연결한다.
  - [x] 컨트롤러는 Repository, `JwtDecoder`, `SecurityContextHolder`에 직접 의존하지 않는다.
  - [x] 응답 예시에 실제 토큰·Secret을 넣지 않는다.

- [x] **Task 3: SecurityFilterChain 인가 정책을 완성한다** (AC: 1, 2, 3, 4, 5)
  - [x] `SecurityConfig`의 `authorizeHttpRequests`에 `/api/admin/**` → `hasRole("ADMIN")`, `/api/users/me` → `hasAnyRole("USER", "ADMIN")`를 추가한다.
  - [x] Story 1.1~1.2의 공개 경로(signup, login, health, actuator health, Swagger)와 `.anyRequest().authenticated()` 정책을 보존한다.
  - [x] `.exceptionHandling`에 기존 `RestAuthenticationEntryPoint`와 새 `RestAccessDeniedHandler`를 함께 연결한다.
  - [x] JWT 필터의 authority 부여 로직(`ROLE_ + role.name()`)이 `hasRole`/`hasAnyRole`과 일관됨을 확인한다(변경 불필요, 일관성 확인 완료).
  - [x] stateless/CSRF 비활성, 필터 순서를 보존한다.

- [x] **Task 4: 테스트 전용 ADMIN fixture를 마련한다** (AC: 6, 8)
  - [x] `User.create()`가 항상 `USER`를 강제하므로, `support/AdminTestFixture`가 가입 후 네이티브 SQL로 `role='ADMIN'`을 갱신한다.
  - [x] fixture는 운영 코드 경로(공개 가입 API)로 ADMIN을 만들지 않으며, 실제 자격 증명을 소스에 하드코딩하지 않는다.
  - [x] 생성한 ADMIN으로 `JwtTokenProvider.issue(...)`를 통해 유효한 `ADMIN` Access Token을 얻는다.
  - [x] fixture가 V1 스키마와 정규화 이메일·BCrypt 제약을 위반하지 않게 한다(정규화 + `passwordEncoder.encode`).

- [x] **Task 5: Role 인가 통합 테스트와 전체 회귀를 실행한다** (AC: 1~8)
  - [x] Spring Security 통합 테스트: USER·ADMIN의 `/api/users/me` `200`, ADMIN의 `/api/admin/ping` `200`, USER의 `/api/admin/ping` `403 AUTH_FORBIDDEN`.
  - [x] 미인증 `/api/admin/ping`의 `401 AUTH_ACCESS_TOKEN_MISSING`과 변조·만료 토큰의 `401`이 `403`과 구분됨을 검증한다.
  - [x] 오류 응답 JSON이 `status/code/message/timestamp/traceId` 공통 형식임을 검증하고, `403` 본문·로그에 JWT·Authorization·stack trace가 없는지 검증한다(`CapturedOutput`).
  - [x] OpenAPI 계약 테스트: `/v3/api-docs`에 `/api/admin/ping`의 `bearerAuth` 보안 요구와 `200`/`403` 응답이 노출되고 안전한 예시인지 검증한다.
  - [x] Story 1.1~1.2 회귀를 보존하는 코드 변경(추가 only, V2 마이그레이션 없음). **빌드/테스트 실행은 환경 제약으로 미실행 — 아래 Completion Notes 참고.**

### Review Findings (Code Review 2026-06-12)

- [x] [Review][Patch] 만료 토큰 테스트가 TTL=900초/issuer/subject 매직넘버에 강결합 [springboot/src/test/java/com/commitgotchi/admin/AdminAuthorizationIntegrationTest.java:115-148] — **수정 완료(2026-06-12).** `JwtConfiguration`을 주입해 `issuer()`·`accessTokenTtl()`을 파생하고, `expiresAt = now-1s`, `issuedAt = expiresAt - ttl`로 계산하며 subject·email은 프로비저닝된 admin 값을 사용하도록 변경. 매직넘버(901/issuer 문자열/subject "42") 제거.
- [x] [Review][Defer] prod/default 프로필에서 `/api/admin/ping` Swagger 비노출 전용 검증 부재 [springboot/src/test/java/com/commitgotchi/admin/AdminAuthorizationIntegrationTest.java] — deferred, AC7 후반 요건. Swagger 자체가 prod/default에서 비활성(기존 `ProdSwaggerDisabledIntegrationTest` 등이 커버)이므로 admin 경로도 전이적으로 비노출. 신규 admin 경로 전용 단언은 없으나 회귀 위험 낮음.

**✅ 검증 게이트 충족 (AC8) — 2026-06-12:** 사용자 로컬에서 `gradle clean test` 실행, **55 tests / 0 failures 통과**. 단, 통과까지 환경 이슈 3건을 해결해야 했음(빌드 설정 변경, 코드 결함 아님):
1. Gradle 9.5.1에서 JUnit Platform launcher 미자동등록 → `build.gradle`에 `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'` 추가.
2. 호스트 기본 JDK가 최신이라 Mockito inline mock 생성 실패(`InlineBytecodeGenerator`/`OpenedClassReader` IllegalArgumentException) → Gradle Java toolchain을 17로 고정(`build.gradle`) + `settings.gradle`에 foojay-resolver-convention 추가.
3. (상세는 `_bmad-output/implementation-artifacts/troubleshooting-story-1-3-test-execution.md` 참고)

**리뷰 노트 (이상 없음 확인):** 401↔403 분기(EntryPoint vs AccessDeniedHandler)·`ROLE_` 접두사 일관성·공통 `ErrorResponse` 재사용·민감정보 비노출·DB 무변경(Flyway V1 유지)·`User.create()` USER 강제 + fixture 네이티브 승격 모두 스펙과 일치하며 정상.

## Dev Notes

### Developer Context

- Story 1.1·1.2 구현은 완료되어 현재 작업 트리에 존재한다. 인증 인프라(JWT 필터, `AuthPrincipal`, `SecurityErrorResponseWriter`, `RestAuthenticationEntryPoint`, `SecurityConfig`, springdoc Bearer)를 재구축하지 말고 확장한다.
- 이 스토리의 핵심 가치는 **401과 403의 정확한 구분**이다. 단일 기능이 통과해도 미인증(401)과 권한 부족(403)이 뒤섞이면 완료가 아니다.
- 핵심 종단 흐름: `ADMIN 로그인 → Bearer Access Token → GET /api/admin/ping = 200`, 그리고 `USER Access Token → GET /api/admin/ping = 403 AUTH_FORBIDDEN`.
- Spring Security 기본 동작상, **미인증 요청이 보호 경로에서 거부되면 `AuthenticationEntryPoint`(401)** 가, **인증됐으나 Role이 부족하면 `AccessDeniedHandler`(403)** 가 호출된다. JWT 필터가 토큰 없는 요청에 SecurityContext를 설정하지 않으므로 이 구분이 자연스럽게 성립한다. 이 동작을 임의로 바꾸지 않는다.
- 보안 필터/인가 단계에서 발생하는 예외는 MVC `@RestControllerAdvice`가 자동 처리하지 않는다. 따라서 `403`도 `GlobalExceptionHandler`가 아니라 `AccessDeniedHandler` + 기존 `SecurityErrorResponseWriter`로 처리해야 같은 오류 계약을 보장한다.

### Current Files To Update

- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`
  - 현재: 회원가입·로그인·Access Token·일반 HTTP 오류 코드를 제공한다(`AUTH_FORBIDDEN` 없음).
  - 변경: `AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, ...)`를 추가한다.
  - 보존: 기존 오류 코드, 상태, 안전한 메시지.
- `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`
  - 현재: stateless/CSRF 비활성, 공개 경로 permitAll, 그 외 `authenticated()`, JWT 필터, `RestAuthenticationEntryPoint`를 연결한다.
  - 변경: `/api/admin/**` → `hasRole("ADMIN")`, `/api/users/me` → `hasAnyRole("USER","ADMIN")` 추가, `.exceptionHandling`에 `RestAccessDeniedHandler` 연결.
  - 보존: Story 1.1~1.2 공개 경로, 필터 순서, stateless/CSRF, 기본 보호 정책.
- `springboot/src/main/java/com/commitgotchi/security/JwtAuthenticationFilter.java`
  - 현재: 검증 성공 시 `ROLE_ + principal.role().name()` authority로 `UsernamePasswordAuthenticationToken`을 설정한다(이미 인가에 적합).
  - 변경: 원칙적으로 변경 없음. authority 매핑을 명확히 유지하고, 필요 시 `hasRole`/`hasAnyRole`과의 일관성만 확인한다.
  - 보존: 요청당 1회 실행, 토큰 없는 공개 경로 통과, 실패 시 SecurityContext 미설정, 누락·만료·invalid 구분.
- `springboot/src/main/java/com/commitgotchi/security/SecurityErrorResponseWriter.java`
  - 현재: `ErrorCode`를 받아 traceId와 함께 공통 `ErrorResponse`를 직렬화한다.
  - 변경: 변경 없이 `AccessDeniedHandler`에서 그대로 재사용한다.
  - 보존: 필드명, traceId 생성/재사용, 민감정보 비노출.
- `springboot/src/main/resources/application.yml`, `springboot/build.gradle`
  - 변경: 새 런타임 의존성 불필요. Spring Security만으로 가능하다. JWT/datasource/Flyway/springdoc 프로필 설정을 그대로 보존한다.
- Story 1.1~1.2 기존 테스트
  - 변경: `/api/users/me`가 `authenticated()`에서 `hasAnyRole`로 바뀌어도 USER 토큰의 `200`은 유지된다. 기대값이 달라지는 테스트가 있으면 계약에 맞게 조정하되 회귀 증거는 보존한다.

### Expected New Files

```text
springboot/src/main/java/com/commitgotchi/
├── admin/api/
│   └── AdminController.java                    # GET /api/admin/ping -> {"status":"ok"}
│   └── dto/AdminPingResponse.java              # 또는 inline record (책임이 명확하면 조정 가능)
└── security/
    └── RestAccessDeniedHandler.java            # 인증됨+권한부족 -> 403 AUTH_FORBIDDEN

springboot/src/test/java/com/commitgotchi/
├── admin/                                       # 또는 security/ 하위 Role 인가 통합 테스트
│   └── AdminAuthorizationIntegrationTest.java
└── support/
    └── (ADMIN fixture 헬퍼: 가입 후 role='ADMIN' 갱신 또는 동등 수단)
```

- 실제 구현에서 책임이 명확하다면 파일명과 작은 클래스 분리는 조정할 수 있다.
- `RefreshToken*`, `V2__create_refresh_tokens.sql`, `AuthController` 신규 엔드포인트, 다른 도메인 파일은 이 스토리에 추가하지 않는다.

### Technical Requirements

- **관리자 검증 API:** `GET /api/admin/ping` + `Authorization: Bearer <admin-access-token>`

```json
{ "status": "ok" }
```

- **인가 정책(아키텍처 §12.4):**

| 경로 | 정책 |
|---|---|
| `GET /api/users/me` | `hasAnyRole("USER", "ADMIN")` |
| `/api/admin/**` | `hasRole("ADMIN")` (최소 검증 API `GET /api/admin/ping`) |
| 그 외 `/api/**` | 기본 `authenticated()` |
| 공개 경로 | signup, login, health, actuator health, 활성 프로필 Swagger |

- **오류 구분(아키텍처 §12.7):** 인증됐으나 Role 부족 → `403 AUTH_FORBIDDEN`. 미인증/누락/변조/만료 → `401` 계열(Story 1.2 계약 유지).
- **공통 오류 형식:** `status`, `code`, 안전한 `message`, `timestamp`, `traceId`. `AuthenticationEntryPoint`·`AccessDeniedHandler`·`@RestControllerAdvice`가 동일 `ErrorResponse` 직렬화 컴포넌트를 사용한다.
- **Authority 매핑:** 검증된 토큰의 `role` Claim만 `ROLE_USER`/`ROLE_ADMIN`으로 매핑한다. Role 값은 `USER|ADMIN` 외 거부(Story 1.2 `JwtTokenProvider.validate`가 이미 `UserRole.valueOf`로 검증).
- **초기 ADMIN(아키텍처 §12.10):** 운영은 제한된 DB 접속/CLI로 기존 사용자의 `role='ADMIN'`을 갱신한다. 테스트는 fixture/프로필에서만 ADMIN을 만든다. Flyway·소스·Swagger에 자격 증명을 넣지 않는다.
- **응답 형식:** 성공 DTO는 wrapper 없이 직접 반환, JSON은 camelCase, 시각은 UTC ISO-8601.
- **민감정보:** JWT 원문, Authorization 헤더, Secret, 라이브러리 예외 메시지, stack trace를 응답·로그에 남기지 않는다.

### Architecture Compliance

- 인증·인가 증분 SSOT는 아키텍처 §12다. 내부 구현보다 경로 정책·오류·Claim·보안 계약이 우선한다.
- 의존 방향은 `API → application → domain/repository`. `AdminController`는 Repository/`SecurityContextHolder`/`JwtDecoder`에 직접 의존하지 않는다.
- `AccessDeniedHandler`는 인증됐으나 Role이 부족한 경우의 `403`만 담당하고 `401`과 혼용하지 않는다(§12 컴포넌트 표).
- `AuthUserDetailsService`/`JwtAuthenticationFilter`는 Role을 `ROLE_USER`/`ROLE_ADMIN` authority로만 변환하고 도메인을 수정하지 않는다.
- DB 변경 없음. Flyway V1만 유지하고 `refresh_tokens`를 미리 만들지 않는다.
- `SecurityConfig`는 비즈니스 로직을 포함하지 않는다.

### Library / Framework Requirements

- 기준선: Java 17, Spring Boot `3.3.5`, Spring Security `6.3.x`(Boot 관리), springdoc-openapi `2.6.0`, PostgreSQL 16.
- 신규 런타임 의존성 불필요. Role 인가는 Spring Security의 `authorizeHttpRequests` + `hasRole`/`hasAnyRole`로 구현한다.
- `hasRole("ADMIN")`은 내부적으로 `ROLE_ADMIN` authority를 요구하므로, 필터가 부여하는 `ROLE_` 접두사 authority와 일치한다. URL 기반 인가를 기본으로 사용하고, `@PreAuthorize` 같은 메서드 보안은 이 스토리에서 필수가 아니다(도입 시 `@EnableMethodSecurity` 필요).
- springdoc 문서화는 기존 `@Operation`/`@ApiResponses`/`@SecurityRequirement(name="bearerAuth")` 패턴을 재사용한다(Story 1.2 `UserController` 참고).

### Testing Requirements

- ADMIN fixture는 공개 가입 API로 만들지 말고, 가입 후 `role='ADMIN'`을 테스트 전용 경로(EntityManager/네이티브 SQL/헬퍼)로 갱신한다. 운영 코드 경로를 우회하는 fixture임을 명시한다.
- Spring Security 통합 테스트는 실제 PostgreSQL Testcontainers와 BCrypt 해시를 사용한다(`PostgresIntegrationTest` 상속, `@ActiveProfiles("test")`, `@AutoConfigureMockMvc`).
- `401`과 `403`의 HTTP 상태뿐 아니라 애플리케이션 `code`(`AUTH_ACCESS_TOKEN_MISSING`/`AUTH_ACCESS_TOKEN_INVALID`/`AUTH_ACCESS_TOKEN_EXPIRED` vs `AUTH_FORBIDDEN`)가 정확히 다른지 검증한다.
- USER·ADMIN 두 Role 모두 `/api/users/me` `200`을 검증해 Story 1.2 회귀를 보존한다.
- `403` 응답 본문과 캡처 로그(`OutputCaptureExtension`)에 JWT 원문, Authorization 헤더, stack trace, Secret이 없는지 검증한다.
- `/v3/api-docs` 계약 테스트로 `/api/admin/ping`의 `bearerAuth` 보안 요구와 `200`/`403` 문서, 안전한 예시를 검증한다.
- 전체 테스트에서 V1 외 Flyway 마이그레이션이 없고 Story 1.1~1.2의 기존 테스트가 계속 통과해야 한다(Story 1.2 최종: 49 tests / 0 failures 기준선).

### Previous Story Intelligence

- Story 1.2에서 무상태 JWT 필터, `AuthPrincipal(userId,email,role)`, `SecurityErrorResponseWriter`, `RestAuthenticationEntryPoint`, springdoc Bearer, 공통 오류/traceId가 확립됐다. 모두 재사용한다.
- JWT 필터는 이미 `ROLE_ + role.name()` authority를 부여하므로 인가에 필요한 토대가 갖춰져 있다. 새 authority 변환 로직을 중복 구현하지 않는다.
- Story 1.2 리뷰에서 확인된 위험을 반복하지 않는다: 보안 필터 예외를 MVC advice가 처리한다고 가정하지 않기, JWT 검증 예외 처리 범위와 하위 필터 체인 실행 분리, `prod+dev` 혼합 프로필 Swagger 비노출, 405/415 등 기존 HTTP 의미 보존.
- 로컬 Gradle wrapper가 없을 수 있으므로 검증 경로는 Docker Gradle 실행(`clean build`/`clean test`)일 수 있다. Story 1.2도 PostgreSQL Testcontainers로 검증했다.
- 보안 변경 시 `/api/users/me`의 기존 통과 테스트가 깨지지 않는지 우선 확인한다(정책이 `authenticated()` → `hasAnyRole`로 좁아짐).

### Git Intelligence

- 최근 작업은 Story 1.1·1.2 인증 구현이며 현재 작업 트리에 존재한다. 이를 기준선으로 취급하고 되돌리지 않는다.
- 기존 Spring Boot 코드는 패키지 경계가 명확하다(`auth`, `user`, `security`, `common/error`). Role 인가 구현을 다른 도메인이나 Vue/FastAPI로 확산하지 않는다.
- 새 `admin` 패키지는 아키텍처 §12.9 소스 트리(`admin/api/AdminController.java`)와 `security/RestAccessDeniedHandler.java` 위치를 따른다.

### Latest Technical Information

- Spring Security 공식 문서 기준, URL 기반 인가에서 `hasRole("ADMIN")`은 `ROLE_ADMIN` authority를, `hasAnyRole("USER","ADMIN")`은 `ROLE_USER`/`ROLE_ADMIN`을 요구한다. 접두사 `ROLE_`는 프레임워크가 자동 부가하므로 정책 문자열에는 접두사를 쓰지 않는다.
- 표준 동작상 인증되지 않은 주체의 접근 거부는 `AuthenticationEntryPoint`(401), 인증된 주체의 권한 부족은 `AccessDeniedHandler`(403)로 분기된다. `.exceptionHandling()`에 두 핸들러를 함께 등록한다.
- 프로젝트는 Spring Boot `3.3.5`/springdoc `2.6.0` 호환선을 이미 고정했다. 이 스토리에서 메이저 업그레이드를 하지 않는다.

### Project Structure Notes

- 실제 Spring Boot 루트는 `springboot/`다. 과거 문서의 `backend-spring/` 예시는 사용하지 않는다.
- 기존 `web/HealthController`, `user/api/UserController`의 응답 계약을 변경하지 않는다.
- Vue 관리자 화면, CORS 최종 allowlist, 운영 ADMIN 관리 API는 이 스토리 범위 밖이다(CORS는 Story 1.5).
- FastAPI, AI 흐름, 다른 도메인 테이블은 수정하지 않는다.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Epic 1 / Story 1.3]
- [Source: `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md` - FR-26, FR-27, FR-28]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md` - §12.3 컴포넌트 표, §12.4 경로 정책, §12.6 API 계약, §12.7 오류 계약, §12.8 인가 시퀀스, §12.9 소스 트리, §12.10 초기 ADMIN, §12.11 테스트 아키텍처]
- [Source: `_bmad-output/implementation-artifacts/1-2-login-and-jwt-access-token-protected-api.md` - JWT 필터/principal/보안 오류 writer/Bearer OpenAPI 계약]
- [Source: `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/security/JwtAuthenticationFilter.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/security/SecurityErrorResponseWriter.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/security/RestAuthenticationEntryPoint.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/common/error/GlobalExceptionHandler.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/user/api/UserController.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/user/domain/User.java` - `create()`가 항상 USER 강제]
- [Spring Security authorize-http-requests reference](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
- [Spring Security AccessDeniedHandler reference](https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-exceptiontranslationfilter)

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (BMad dev-story 워크플로)

### Debug Log References

- 격리 Linux 샌드박스가 디스크 공간 부족으로 부팅 실패 → `git`/Gradle/Java 실행 불가. 코드는 파일 도구로 구현했고 정적 검토로 검증함. `baseline_commit`은 VCS 미사용으로 `NO_VCS` 기록.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created
- `sprint-status.yaml`이 없어 스프린트 상태 항목은 갱신하지 않음
- **구현 요약**
  - `ErrorCode.AUTH_FORBIDDEN(403)` 추가, 안전한 메시지(자격/내부정보 비포함).
  - `RestAccessDeniedHandler`(security): 인증됨+권한부족 → 기존 `SecurityErrorResponseWriter` 재사용으로 `403 AUTH_FORBIDDEN` 공통 오류 직렬화. 예외 메시지/stack trace/Authorization 비노출.
  - `AdminController` + `AdminPingResponse`(admin/api): `GET /api/admin/ping` → `{"status":"ok"}` DTO 직접 반환, springdoc `bearerAuth`·`200`/`401`/`403` 문서화. Repository/JwtDecoder/SecurityContextHolder 비의존.
  - `SecurityConfig`: `/api/admin/**` → `hasRole("ADMIN")`, `/api/users/me` → `hasAnyRole("USER","ADMIN")` 추가, `.exceptionHandling`에 `accessDeniedHandler` 연결. 공개 경로·필터 순서·stateless/CSRF 보존.
  - JWT 필터 authority(`ROLE_ + role.name()`)는 `hasRole`/`hasAnyRole`과 이미 일관 → 변경 없음.
  - 테스트 fixture `support/AdminTestFixture`: 가입 후 네이티브 SQL로 `role='ADMIN'` 승격, `JwtTokenProvider.issue`로 유효 토큰 발급. 운영 가입 경로로 ADMIN 생성하지 않음.
  - `admin/AdminAuthorizationIntegrationTest`: AC1~8 커버(USER·ADMIN `/me` 200, ADMIN ping 200, USER ping 403 AUTH_FORBIDDEN + 안전 계약/로그, 미인증 401, 변조·만료 401, OpenAPI 계약).
  - DB 변경 없음 — Flyway V1 유지, `refresh_tokens`/V2 미생성.
- **⚠️ 검증 미완 (사용자 조치 필요):** 본 세션 샌드박스에서 Gradle 테스트를 실행할 수 없었습니다. 완료 게이트(전체 회귀 통과)를 닫으려면 다음을 직접 실행해 주세요.
  - 로컬 wrapper 있을 시: `cd springboot && ./gradlew clean test`
  - 또는 Docker Gradle: `docker run --rm -v "$PWD/springboot":/app -w /app gradle:8-jdk17 gradle clean test`
  - 기대: Story 1.2 기준선(49 tests) + Story 1.3 신규 테스트가 모두 통과.

### File List

**신규 (main)**
- `springboot/src/main/java/com/commitgotchi/security/RestAccessDeniedHandler.java`
- `springboot/src/main/java/com/commitgotchi/admin/api/AdminController.java`
- `springboot/src/main/java/com/commitgotchi/admin/api/dto/AdminPingResponse.java`

**수정 (main)**
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`
- `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`

**신규 (test)**
- `springboot/src/test/java/com/commitgotchi/support/AdminTestFixture.java`
- `springboot/src/test/java/com/commitgotchi/admin/AdminAuthorizationIntegrationTest.java`

## Change Log

| 날짜 | 변경 | 비고 |
|---|---|---|
| 2026-06-12 | Story 1.3 Role 기반 관리자 인가 구현 (AUTH_FORBIDDEN, RestAccessDeniedHandler, AdminController, SecurityConfig 인가 정책, ADMIN fixture, 통합 테스트) | 샌드박스 제약으로 Gradle 테스트 미실행 — 사용자 실행 필요 |
| 2026-06-12 | 코드리뷰: 만료 토큰 테스트 매직넘버/TTL 강결합 patch 적용, defer 1건 기록 | 검증 게이트(AC8) 미충족으로 Status review → in-progress |
| 2026-06-12 | 테스트 실행 환경 수정: `build.gradle`에 junit-platform-launcher 추가, Java toolchain 17 고정, `settings.gradle`에 foojay-resolver 추가 | 55 tests / 0 failures 통과, Status in-progress → done |
