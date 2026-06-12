---
baseline_commit: b24cc6d2f41d6a48407d6c75e4ebce07682a991b
---

# Story 1.2: 로그인과 JWT Access Token 보호 API

Status: done

## Story

As a 가입 사용자,
I want 로그인하여 Access Token으로 내 정보를 조회하고,
so that 보호된 서비스 기능을 안전하게 사용할 수 있다.

## 목적과 범위

- 무상태 Bearer JWT 인증을 완성하고 `POST /api/auth/login`부터 `GET /api/users/me`까지 종단 흐름을 제공한다.
- HS256 Access Token 발급·검증, 정확한 JWT 실패 코드, Spring Security 보호 경로, Bearer OpenAPI 문서를 구현한다.
- Story 1.1의 회원가입, 공통 오류/traceId, Flyway V1, 프로필별 Swagger 정책, health endpoint를 그대로 보존하고 확장한다.
- 이 스토리에서는 Access Token만 발급한다. Refresh Token, `refresh_tokens` 테이블, 재발급, 로그아웃, ADMIN 전용 API는 구현하지 않는다.

## Acceptance Criteria

1. **정상 로그인과 Access Token 발급**
   - **Given** 정규화 이메일과 일치하는 사용자 및 올바른 비밀번호가 있을 때
   - **When** `POST /api/auth/login`을 호출하면
   - **Then** `200 OK`와 `tokenType`, `accessToken`, UTC ISO-8601 `accessTokenExpiresAt`이 반환된다.
   - **And** Access Token은 HS256으로 서명되고 발급 시점부터 15분간 유효하다.
   - **And** `sub`는 사용자 ID 문자열이며 정규화된 `email`, `role`, `iat`, `exp`, `iss=commitgotchi-springboot`, `typ=access` Claim을 포함한다.
   - **And** 로그인 이메일은 회원가입과 동일하게 `trim()` 후 `Locale.ROOT` lowercase로 정규화된다.
   - **And** 응답 구조는 Story 1.4에서 `refreshToken`, `refreshTokenExpiresAt`을 같은 성공 DTO에 추가할 수 있다.

2. **로그인 자격 증명 실패의 비식별 응답**
   - **Given** 존재하지 않는 이메일 또는 잘못된 비밀번호가 주어졌을 때
   - **When** 로그인을 요청하면
   - **Then** 두 경우 모두 동일한 공통 오류 형식의 `401 AUTH_INVALID_CREDENTIALS`가 반환된다.
   - **And** 응답과 로그에서 계정 존재 여부, 비밀번호, 해시, 내부 인증 예외 상세를 노출하지 않는다.
   - **And** 누락 필드·잘못된 JSON·unknown field 같은 요청 형식 오류는 `400 VALIDATION_FAILED`로 구분한다.

3. **유효한 Access Token으로 현재 사용자 조회**
   - **Given** 유효한 Access Token을 `Authorization: Bearer <token>` 헤더에 포함했을 때
   - **When** `GET /api/users/me`를 호출하면
   - **Then** `200 OK`와 현재 인증 사용자의 `id`, `email`, `role`이 반환된다.
   - **And** Controller는 `@AuthenticationPrincipal AuthPrincipal`의 사용자 ID·이메일·Role 계약에만 의존한다.
   - **And** Controller 또는 Service가 JWT를 다시 파싱하거나 이메일로 사용자를 재식별하지 않는다.

4. **Access Token 누락·변조·만료·계약 위반 처리**
   - **Given** 보호 API 요청에 Access Token이 없을 때
   - **When** 요청을 처리하면
   - **Then** 공통 오류 형식의 `401 AUTH_ACCESS_TOKEN_MISSING`이 반환된다.
   - **Given** Access Token의 서명·형식·알고리즘·issuer·type·필수 Claim 중 하나가 잘못되었을 때
   - **When** 보호 API를 호출하면
   - **Then** 공통 오류 형식의 `401 AUTH_ACCESS_TOKEN_INVALID`가 반환된다.
   - **Given** Access Token이 만료되었을 때
   - **When** 보호 API를 호출하면
   - **Then** 공통 오류 형식의 `401 AUTH_ACCESS_TOKEN_EXPIRED`가 반환된다.
   - **And** 모든 실패 응답은 `status`, `code`, 안전한 `message`, `timestamp`, `traceId`를 포함한다.
   - **And** 오류 응답과 로그에 JWT 원문, Authorization 헤더, Secret, 내부 예외 상세, stack trace가 포함되지 않는다.

5. **무상태 SecurityFilterChain과 경로 정책**
   - **Given** 애플리케이션이 실행될 때
   - **When** Spring Security 설정을 검사하면
   - **Then** 세션은 `STATELESS`이며 CSRF는 비활성화된다.
   - **And** `JwtAuthenticationFilter`는 요청당 한 번 실행되고 `UsernamePasswordAuthenticationFilter` 앞에 배치된다.
   - **And** `POST /api/auth/signup`, `POST /api/auth/login`, health, 활성 프로필의 Swagger 경로는 토큰 없이 접근할 수 있다.
   - **And** 토큰이 없는 공개 경로 요청은 JWT 필터가 그대로 통과시킨다.
   - **And** `GET /api/users/me` 및 그 외 `/api/**`는 기본 인증이 필요하다.
   - **And** 유효한 JWT 검증 성공 시 최소 `userId`, `email`, `role`을 가진 불변 `AuthPrincipal`이 `SecurityContext`에 설정된다.

6. **JWT Secret과 암호 구현 안전성**
   - **Given** JWT 컴포넌트가 초기화될 때
   - **When** `JWT_SECRET_BASE64`를 읽으면
   - **Then** Base64 디코딩 결과가 최소 32바이트인지 검증하고 부족하거나 잘못된 값이면 애플리케이션 시작이 실패한다.
   - **And** issuer는 `JWT_ISSUER` 환경변수로 주입하되 기본 계약값은 `commitgotchi-springboot`다.
   - **And** JWT 서명·파싱·검증은 Spring Security OAuth2 JOSE의 `JwtEncoder`/`JwtDecoder`를 사용하고 암호 로직을 직접 구현하지 않는다.
   - **And** 실제 Secret은 소스코드, Git, Swagger 예시, 로그에 포함되지 않는다.

7. **Bearer OpenAPI 문서와 기존 Swagger 정책 보존**
   - **Given** 애플리케이션이 `local`, `dev`, `test` 프로필로 실행될 때
   - **When** Swagger UI 또는 `/v3/api-docs`를 확인하면
   - **Then** `bearerAuth` HTTP Bearer JWT 보안 스키마와 Swagger UI Authorize 버튼이 제공된다.
   - **And** 로그인 요청·성공·`AUTH_INVALID_CREDENTIALS`, `/api/users/me` 성공, Access Token 누락·유효하지 않음·만료 응답이 문서화된다.
   - **And** `/api/users/me`에 `bearerAuth` 보안 요구가 표시되고 회원가입·로그인은 공개 API로 표시된다.
   - **And** 예시에 실제 비밀번호, JWT, Refresh Token, Secret이 포함되지 않는다.
   - **Given** 기본 또는 운영 프로필로 실행될 때
   - **When** Swagger UI 또는 `/v3/api-docs`에 접근하면
   - **Then** Story 1.1과 동일하게 접근할 수 없다.

8. **Story 1.2 자동화 검증과 회귀 보존**
   - **Given** Story 1.2 테스트가 실행될 때
   - **When** 단위·JWT 보안·Spring Security·API 통합·Swagger 계약 테스트가 완료되면
   - **Then** 로그인 성공·실패, 이메일 정규화, Access Token Claim/만료, 정상·누락·변조·만료·잘못된 JWT, `/api/users/me`, Bearer OpenAPI 구성이 검증된다.
   - **And** Story 1.1의 회원가입, 중복/검증 오류, 프로필별 Swagger, `GET /api/health`, Flyway V1/JPA validate 테스트가 계속 통과한다.
   - **And** `refresh_tokens` 테이블 또는 V2 이상 마이그레이션이 생성되지 않는다.

## Tasks / Subtasks

- [x] **Task 1: JWT 의존성과 안전한 설정 계약을 추가한다** (AC: 1, 4, 6, 8)
  - [x] `springboot/build.gradle`에 버전을 직접 고정하지 않은 `org.springframework.security:spring-security-oauth2-jose`를 추가한다.
  - [x] `application.yml`에 `JWT_SECRET_BASE64`와 `JWT_ISSUER` 기반 설정을 추가한다. 실제 Secret이나 운영용 고정값은 커밋하지 않는다.
  - [x] `.env.example`에는 실제 값 대신 안전한 Secret 생성 방법과 변수 형식만 문서화하고, `docker-compose.yml`은 JWT 환경변수를 Spring Boot 컨테이너에 전달한다.
  - [x] Base64 디코딩 실패 또는 256-bit 미만 Secret을 시작 시점에 거부한다.
  - [x] 기존 Spring context 테스트에는 테스트 전용 256-bit 이상 Secret을 테스트 설정으로 주입해 Story 1.1 테스트가 계속 시작되게 한다.
  - [x] Access Token 만료시간은 15분으로 고정하고 테스트에서 시간 경계를 결정론적으로 검증할 수 있도록 `Clock` 주입을 고려한다.
  - [x] Story 1.1의 datasource, Flyway, `ddl-auto=validate`, springdoc 프로필 활성화 설정을 보존한다.

- [x] **Task 2: 로그인 인증 경로를 구현한다** (AC: 1, 2, 8)
  - [x] `UserRepository`에 정규화 이메일 조회를 추가한다.
  - [x] `AuthUserDetailsService`와 `DaoAuthenticationProvider`/`AuthenticationManager`를 구성해 기존 BCrypt cost 12 해시를 검증한다.
  - [x] 로그인 이메일 정규화는 회원가입과 동일한 `trim()` + `toLowerCase(Locale.ROOT)` 규칙을 사용한다.
  - [x] 존재하지 않는 이메일과 비밀번호 불일치를 모두 `AUTH_INVALID_CREDENTIALS`로 매핑하고 차이를 응답/로그에 남기지 않는다.
  - [x] 로그인 요청/응답 DTO를 추가하고 `AuthController`와 `AuthService`를 기존 `API -> application -> domain/repository` 방향으로 확장한다.
  - [x] 로그인 성공 응답은 현재 Access Token 필드만 포함하며 Refresh Token 필드는 Story 1.4까지 추가하지 않는다.

- [x] **Task 3: Access Token 발급·검증 컴포넌트를 구현한다** (AC: 1, 4, 6, 8)
  - [x] `JwtTokenProvider`에서 Spring Security `JwtEncoder`/`JwtDecoder`를 사용해 HS256 발급·검증을 캡슐화한다.
  - [x] 발급 토큰에 `sub`, 정규화된 `email`, `role`, `iat`, `exp`, `iss`, `typ`를 넣고 `sub`가 양의 사용자 ID 문자열인지 검증한다.
  - [x] 검증 시 HS256만 허용하고 `alg=none` 및 예상 외 알고리즘을 거부한다.
  - [x] issuer, `typ=access`, 모든 필수 Claim, Role 허용값, 만료를 검증한다.
  - [x] JWT 예외를 만료와 그 외 유효하지 않은 토큰으로 안전하게 분류하되 라이브러리 예외 메시지를 외부에 노출하지 않는다.

- [x] **Task 4: JWT 필터와 공통 보안 오류 응답을 연결한다** (AC: 3, 4, 5, 8)
  - [x] 불변 `AuthPrincipal(userId, email, role)`과 `JwtAuthenticationFilter extends OncePerRequestFilter`를 추가한다.
  - [x] 필터는 Bearer Token이 없으면 다음 필터로 진행하고, 토큰이 있으면 검증 성공 시에만 `SecurityContext`를 설정한다.
  - [x] 필터는 DB 조회, 토큰 발급, 비즈니스 로직을 수행하지 않는다.
  - [x] `RestAuthenticationEntryPoint` 또는 동등한 보안 오류 writer를 추가해 필터 체인에서도 기존 `ErrorResponse` 형식을 정확히 직렬화한다.
  - [x] Security 필터 예외가 `GlobalExceptionHandler`를 자동 통과한다고 가정하지 않는다. 보안 오류 writer와 MVC 오류 응답이 같은 `ErrorCode`/`ErrorResponse` 계약을 공유하게 한다.
  - [x] 누락·만료·기타 invalid JWT를 각각 `AUTH_ACCESS_TOKEN_MISSING`, `AUTH_ACCESS_TOKEN_EXPIRED`, `AUTH_ACCESS_TOKEN_INVALID`로 구분한다.
  - [x] 오류 응답 생성 시 Story 1.1의 `TraceIdFilter`가 만든 MDC `traceId`를 재사용한다.

- [x] **Task 5: SecurityFilterChain과 현재 사용자 API를 완성한다** (AC: 3, 5, 8)
  - [x] 기존 `SecurityConfig`의 stateless/CSRF 설정과 공개 health/Swagger 경로를 보존한다.
  - [x] `POST /api/auth/login`을 공개 경로에 추가하고 JWT 필터를 `UsernamePasswordAuthenticationFilter` 앞에 배치한다.
  - [x] `GET /api/users/me`는 인증을 요구하고, 그 외 `/api/**`도 기본 `authenticated()` 정책을 유지한다.
  - [x] `user/api/UserController`와 응답 DTO를 추가해 `@AuthenticationPrincipal AuthPrincipal`에서 `id`, `email`, `role`을 직접 반환한다.
  - [x] `/api/admin/**`와 `RestAccessDeniedHandler`의 최종 ADMIN 인가 구현은 Story 1.3에 남긴다.

- [x] **Task 6: 로그인·보호 API OpenAPI 문서를 추가한다** (AC: 7, 8)
  - [x] springdoc `OpenAPI` Bean 또는 동등한 구성으로 `bearerAuth` HTTP Bearer JWT 스키마를 등록한다.
  - [x] 로그인 및 `/api/users/me`의 성공/오류 계약을 문서화하고 보호 API에 `@SecurityRequirement(name = "bearerAuth")`를 연결한다.
  - [x] 회원가입과 로그인은 전역 보안 요구에 의해 잠기지 않게 공개 API로 유지한다.
  - [x] `/v3/api-docs` 계약 테스트로 Authorize 스키마, 보호 API 보안 요구, 안전한 예시를 검증한다.
  - [x] 기본/운영 및 `prod+dev` 혼합 프로필의 Swagger 비활성화 회귀를 보존한다.

- [x] **Task 7: Story 1.2 테스트를 구현하고 전체 회귀를 실행한다** (AC: 1~8)
  - [x] 단위 테스트: 로그인 이메일 정규화, 동일 실패 매핑, Access Token 필수 Claim/15분 만료, Secret 유효성 검증.
  - [x] JWT 보안 테스트: 변조 서명, malformed, 만료, 누락 Claim, 잘못된 issuer/type/email/role/sub, 예상 외 알고리즘.
  - [x] Spring Security 통합 테스트: 공개 경로 무토큰 허용, `/api/users/me` 정상/누락/invalid/expired, 그 외 `/api/**` 기본 보호.
  - [x] API 통합 테스트: 회원가입 → 로그인 → `/api/users/me` 종단 성공과 존재하지 않는 이메일/잘못된 비밀번호의 동일 `401`.
  - [x] 민감정보 테스트: 로그인/JWT 실패 응답과 캡처 로그에 비밀번호, 해시, JWT, Authorization, Secret, stack trace가 없는지 검증한다.
  - [x] Swagger 계약 테스트와 Story 1.1 전체 테스트를 함께 실행한다.

### Review Findings

- [x] [Review][Patch] 로그인에서 72바이트 초과 비밀번호를 거부해 BCrypt 접미사 인증 우회를 막는다 [springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java:50]
- [x] [Review][Patch] 미래 `nbf`가 있는 토큰을 거부하도록 JWT 시간 검증을 복원한다 [springboot/src/main/java/com/commitgotchi/security/JwtConfiguration.java:55]
- [x] [Review][Patch] 검증 시 `exp - iat`가 정확한 15분 계약을 벗어난 토큰을 거부한다 [springboot/src/main/java/com/commitgotchi/security/JwtTokenProvider.java:73]
- [x] [Review][Dismiss] 공개 인증 경로의 메서드 제한 — Story 1.1의 잘못된 메서드 `405 METHOD_NOT_ALLOWED` 보존 계약과 충돌하여 기존 동작 유지 [springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java:50]
- [x] [Review][Patch] 인증 인프라 장애까지 잘못된 자격 증명 401로 변환하지 않도록 예외 매핑 범위를 좁힌다 [springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java:63]
- [x] [Review][Patch] HTTP 인증 스킴의 대소문자 비구분 규칙에 맞게 소문자 `bearer`도 허용한다 [springboot/src/main/java/com/commitgotchi/security/JwtAuthenticationFilter.java:39]
- [x] [Review][Patch] JWT 검증 예외 처리 범위에서 하위 필터 체인 실행을 분리한다 [springboot/src/main/java/com/commitgotchi/security/JwtAuthenticationFilter.java:44]
- [x] [Review][Patch] 누락 Claim·필터 상태·OpenAPI 오류 계약과 확실한 서명 변조 사례를 자동화 테스트로 보강한다 [springboot/src/test/java/com/commitgotchi/security/JwtTokenProviderTest.java:85]

## Dev Notes

### Developer Context

- Story 1.1 구현은 완료되어 현재 작업 트리에 존재한다. 새 인증 구조를 별도 재구축하지 말고 기존 회원가입, BCrypt Bean, 사용자 엔티티/Repository, 공통 오류, traceId, SecurityFilterChain, springdoc 설정을 확장한다.
- 가장 중요한 종단 흐름은 `회원가입 -> 로그인 -> Bearer Access Token -> /api/users/me`다. 각 단위 기능만 통과하고 이 흐름이 깨지는 구현은 완료가 아니다.
- Story 1.2는 Access Token 전용 증분이다. 아키텍처 §12.6의 최종 로그인 예시는 Refresh Token을 포함하지만, 에픽의 확정 순서에 따라 Refresh Token 생성·저장·응답 확장은 Story 1.4에서 수행한다.
- Security 필터 체인에서 발생한 예외는 MVC `@RestControllerAdvice`가 자동 처리하지 않는다. 기존 공통 오류 JSON을 보안 계층에서도 직접 재사용할 수 있는 작은 writer/serializer가 필요하다.
- JWT 검증 성공 후 Controller가 DB를 재조회하지 않아도 `/api/users/me`가 동작하도록 `AuthPrincipal`에 `userId`, `email`, `role`을 포함한다. 향후 최신 DB Role 재확인이 필요한 관리자성 변경 작업만 Service에서 `userId`로 재조회한다.
- 아키텍처의 최소 JWT Claim 목록에는 email이 없지만, 같은 문서가 JWT 필터의 DB 조회를 금지하면서 `AuthPrincipal`에 email을 요구하고 `/api/users/me`가 principal 계약만 사용하도록 정한다. 따라서 이 스토리는 정규화된 email Claim을 추가해 종단 계약을 일관되게 만든다.

### Current Files To Update

- `springboot/build.gradle`
  - 현재: Story 1.1의 Security, Validation, Flyway, springdoc, Security Test, Testcontainers가 있다.
  - 변경: Boot dependency management를 따르는 `spring-security-oauth2-jose`만 추가한다.
  - 보존: Spring Boot `3.3.5`, Java 17, springdoc `2.6.0`, 기존 의존성 버전 정책.
- `springboot/src/main/resources/application.yml`
  - 현재: datasource, Flyway, `ddl-auto=validate`, springdoc 기본 비활성 및 `!prod & (local | dev | test)` 활성 정책이 있다.
  - 변경: JWT Secret/issuer 설정을 추가한다.
  - 보존: 운영·기본 및 `prod+dev` 혼합 프로필에서 Swagger가 비활성인 정책.
- `.env.example`
  - 현재: DB/포트 변수와 더 이상 사용되지 않는 `JPA_DDL_AUTO=update` 안내가 있다.
  - 변경: `JWT_SECRET_BASE64`, `JWT_ISSUER`의 형식과 안전한 생성 방법을 안내하고 stale `JPA_DDL_AUTO` 안내를 제거한다.
  - 보존: 실제 Secret은 포함하지 않고 `.env`를 커밋하지 않는 원칙.
- `docker-compose.yml`
  - 현재: Spring Boot 컨테이너에 DB 변수만 전달한다.
  - 변경: `JWT_SECRET_BASE64`, `JWT_ISSUER`를 Spring Boot 컨테이너에 전달하고 필수 Secret 누락 시 명확히 실패하게 한다.
  - 보존: `/api/health` 기반 healthcheck와 기존 서비스/네트워크 계약.
- `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`
  - 현재: 회원가입 operation과 `POST /api/auth/signup`만 제공한다.
  - 변경: 로그인 operation/API를 추가한다.
  - 보존: 회원가입 응답 상태·문서·검증 계약.
- `springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java`
  - 현재: 가입 이메일 정규화, 비밀번호 정책, 중복 검사, BCrypt 저장을 수행한다.
  - 변경: AuthenticationManager와 JWT 발급을 조정하는 로그인 유스케이스를 추가한다.
  - 보존: 가입 트랜잭션, DB unique 경합 변환, 공개 가입 Role 강제.
- `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`
  - 현재: BCrypt cost 12, stateless, CSRF 비활성, signup/health/Swagger 공개, 나머지 인증 요구를 설정한다.
  - 변경: AuthenticationManager/provider, login 공개, JWT 필터, 보안 EntryPoint를 연결한다.
  - 보존: Story 1.1 공개 경로와 기본 보호 정책.
- `springboot/src/main/java/com/commitgotchi/user/domain/UserRepository.java`
  - 현재: `existsByEmail`만 제공한다.
  - 변경: 로그인용 정규화 이메일 조회를 추가한다.
  - 보존: Repository가 인증 정책이나 JWT 로직을 갖지 않는 경계.
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`
  - 현재: 회원가입 및 일반 HTTP 오류 코드를 제공한다.
  - 변경: `AUTH_INVALID_CREDENTIALS`, `AUTH_ACCESS_TOKEN_MISSING`, `AUTH_ACCESS_TOKEN_INVALID`, `AUTH_ACCESS_TOKEN_EXPIRED`를 추가한다.
  - 보존: 기존 오류 코드와 안전한 메시지.
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorResponse.java`, `GlobalExceptionHandler.java`, `common/web/TraceIdFilter.java`
  - 현재: 공통 오류 JSON과 서버 생성 traceId를 제공한다.
  - 변경: 원칙적으로 계약은 변경하지 않고 보안 오류 writer가 재사용하도록 필요한 최소 추출만 한다.
  - 보존: 필드명, traceId 생성/정리, 민감정보 비노출.
- Story 1.1 기존 테스트
  - 변경: 새 기본 인증 정책 때문에 기대 상태가 달라지는 테스트가 있으면 계약에 맞게 조정한다.
  - 보존: 회원가입, DB V1, Swagger 프로필, health 회귀 증거.

### Expected New Files

```text
springboot/src/main/java/com/commitgotchi/
├── auth/api/dto/
│   ├── LoginRequest.java
│   └── LoginResponse.java
├── common/error/
│   └── InvalidCredentialsException.java              # 또는 동등한 안전한 인증 예외
├── security/
│   ├── AuthPrincipal.java
│   ├── AuthUserDetailsService.java
│   ├── JwtAuthenticationFilter.java
│   ├── JwtTokenProvider.java
│   ├── RestAuthenticationEntryPoint.java
│   └── SecurityErrorResponseWriter.java              # 이름은 조정 가능
└── user/api/
    ├── UserController.java
    └── dto/CurrentUserResponse.java

springboot/src/test/java/com/commitgotchi/
├── auth/
├── security/
├── user/
└── swagger/
```

- 실제 구현에서 책임이 명확하다면 파일명과 작은 클래스 분리는 조정할 수 있다.
- `RefreshToken*`, `V2__create_refresh_tokens.sql`, `AdminController`, 다른 도메인 파일은 이 스토리에 추가하지 않는다.

### Technical Requirements

- **로그인 API:** `POST /api/auth/login`

```json
{
  "email": "user@example.com",
  "password": "safe-placeholder-password"
}
```

```json
{
  "tokenType": "Bearer",
  "accessToken": "<jwt-placeholder>",
  "accessTokenExpiresAt": "2026-06-12T10:15:00Z"
}
```

- **현재 사용자 API:** `GET /api/users/me` + `Authorization: Bearer <access-token>`

```json
{
  "id": 1,
  "email": "user@example.com",
  "role": "USER"
}
```

- **JWT 계약:** HS256, 15분, `sub=userId 문자열`, 정규화된 `email`, `role=USER|ADMIN`, `iat`, `exp`, `iss=JWT_ISSUER`, `typ=access`.
- **Secret 계약:** `JWT_SECRET_BASE64`를 Base64 디코딩한 raw key가 최소 32바이트여야 한다. Secret의 기본 개발값을 소스에 두지 않는다.
- **컨테이너 계약:** Compose 실행도 동일 Secret/issuer를 Spring Boot에 전달해야 한다. `.env.example`에는 복사 가능한 고정 Secret 대신 생성 명령과 빈 형식만 제공한다.
- **검증 계약:** 서명, compact 형식, 알고리즘, issuer, type, `sub/email/role/iat/exp`, 만료를 모두 검증한다. Claim 존재만 확인하지 말고 허용 값과 자료형도 검증한다.
- **로그인 실패:** `UsernameNotFoundException`, `BadCredentialsException` 등 내부 원인과 무관하게 외부에는 동일한 `AUTH_INVALID_CREDENTIALS`만 반환한다. 단, DTO 형식·필수 필드·unknown field 오류는 `VALIDATION_FAILED`다.
- **보안 실패:** 누락과 malformed Bearer 헤더를 구분한다. 헤더 자체가 없으면 `MISSING`, 헤더가 있으나 Bearer 형식/토큰/검증이 잘못되면 `INVALID`, 검증된 만료 사유는 `EXPIRED`다.
- **공개 경로:** 토큰이 없으면 통과한다. 요청이 Bearer Token을 명시적으로 보냈다면 유효성 검증 실패를 조용히 무시하지 않는다.
- **응답 형식:** 성공 DTO는 wrapper 없이 직접 반환하고 JSON은 camelCase, 시각은 UTC ISO-8601을 사용한다.
- **민감정보:** 비밀번호, `passwordHash`, JWT 원문, Authorization 헤더, Base64 Secret, 디코딩 key, JWT 라이브러리 예외 메시지를 응답이나 로그에 남기지 않는다.

### Architecture Compliance

- 인증 증분 SSOT는 아키텍처 §12다. JWT 내부 구현 선택보다 외부 API·Claim·오류·경로 정책·보안 계약이 우선한다.
- 의존 방향은 `API -> application -> domain/repository`다. Controller가 Repository, `JwtDecoder`, `SecurityContextHolder`에 직접 의존하지 않는다.
- `JwtAuthenticationFilter`는 검증과 SecurityContext 설정만 담당한다. 토큰 발급, 사용자 DB 조회, 로그인 인증을 수행하지 않는다.
- `AuthService`는 로그인 유스케이스를 조정하되 SecurityContext를 직접 조작하지 않는다.
- `AuthUserDetailsService`는 정규화 이메일로 사용자 읽기와 authority 변환만 수행하며 도메인을 수정하지 않는다.
- `AuthPrincipal`은 불변이며 최소 `userId`, `email`, `role`만 제공한다.
- DB 변경은 없다. Flyway V1만 유지하고 `refresh_tokens`를 미리 만들지 않는다.
- `SecurityConfig`는 비즈니스 로직을 포함하지 않는다.

### Library / Framework Requirements

- 기준선: Java 17, Spring Boot `3.3.5`, Spring Security `6.3.x`(Boot 관리), springdoc-openapi `2.6.0`, PostgreSQL 16.
- 추가 의존성: `org.springframework.security:spring-security-oauth2-jose`이며 버전은 Spring Boot dependency management에 맡긴다.
- Spring Security의 `JwtEncoder`/`JwtDecoder`와 Nimbus 기반 구현을 사용한다. HMAC/JWT compact serialization을 직접 작성하지 않는다.
- `DaoAuthenticationProvider`는 `UserDetailsService`와 기존 `PasswordEncoder`를 사용해 로그인 자격 증명을 검증한다.
- springdoc Bearer 구성은 HTTP security scheme `bearer` + `bearerFormat("JWT")`와 보호 operation의 security requirement를 사용한다.
- 최신 Spring Security 문서는 Resource Server가 JWT 서명과 `exp`/`nbf`/`iss`를 검증하고 JWT를 Authentication으로 변환하는 표준 컴포넌트를 제공함을 명시한다. 이 프로젝트는 추가 `typ`, Role, 오류 코드 계약을 별도로 검증해야 한다.

### Testing Requirements

- JWT 시간 테스트는 실제 15분 대기를 사용하지 않는다. 고정 `Clock` 또는 명시적 과거/미래 Claim으로 경계값을 검증한다.
- JWT 테스트는 단순 decode 성공만 확인하지 말고 서명 변조, 예상 외 알고리즘, 누락/잘못된 Claim, issuer/type/email/role/sub 오류를 각각 검증한다.
- 누락·invalid·expired의 HTTP 상태가 모두 401이어도 애플리케이션 `code`가 정확히 다른지 검증한다.
- 로그인 실패 테스트는 존재하지 않는 이메일과 틀린 비밀번호의 상태·오류 JSON이 동일함을 검증하고, malformed/unknown-field 요청은 `400 VALIDATION_FAILED`임을 별도로 검증한다.
- 필터 테스트는 요청당 한 번 실행, 공개 경로 무토큰 통과, 성공 시 principal/authority 설정, 실패 시 SecurityContext 미설정을 검증한다.
- API 종단 테스트는 실제 PostgreSQL Testcontainers의 가입 사용자와 BCrypt 해시를 사용한다.
- `/api/users/me` 테스트는 응답이 토큰 principal 계약에서 생성되며 비밀번호/해시가 없는지 검증한다.
- `/v3/api-docs`에서 `components.securitySchemes.bearerAuth`, 로그인 경로, `/api/users/me` 보안 요구와 오류 응답을 검증한다.
- 전체 테스트에서 V1 외 Flyway 마이그레이션이 없고 Story 1.1의 25개 기존 테스트가 계속 통과해야 한다.

### Previous Story Intelligence

- Story 1.1에서 인증 패키지 경계, PostgreSQL Testcontainers, Flyway V1/JPA validate, BCrypt cost 12, 공통 오류 JSON, 서버 생성 traceId, 프로필 제한 springdoc가 확립됐다. 모두 재사용한다.
- 이전 리뷰에서 실제로 발견·수정된 회귀 위험을 반복하지 않는다:
  - 이메일 정규화 후 길이와 구조 검증을 우회하지 않는다.
  - Unicode 비밀번호를 DTO와 서비스가 서로 다른 길이 단위로 판단하지 않는다.
  - DB/라이브러리 예외 메시지 문자열에 의존해 오류를 분류하지 않는다.
  - `prod+dev` 혼합 프로필에서 Swagger가 노출되지 않게 한다.
  - 405/415 등 기존 HTTP 의미를 catch-all 500으로 바꾸지 않는다.
- Story 1.1 최종 검증은 Docker Gradle `clean build`, PostgreSQL Testcontainers 포함 25 tests / 0 failures / 0 skipped였다. Story 1.2는 이 기준선을 깨지 않아야 한다.
- 로컬 Gradle wrapper가 없으므로 현재 프로젝트의 검증 경로는 Docker Gradle 실행일 수 있다.

### Git Intelligence

- 최근 커밋은 문서 정렬 및 초기 골격 커밋이며, Story 1.1 구현은 아직 작업 트리에 있다.
- Story 1.2 구현자는 현재 작업 트리의 Story 1.1 파일과 사용자 문서 변경을 기준선으로 취급하고 되돌리지 않는다.
- 기존 Spring Boot 코드는 작고 패키지 경계가 명확하다. JWT 구현을 다른 서비스나 Vue/FastAPI로 확산하지 않는다.

### Latest Technical Information

- Spring Security 공식 문서 기준으로 `DaoAuthenticationProvider`는 `UserDetailsService`와 `PasswordEncoder`를 사용해 username/password를 인증한다. 프로젝트 로그인 경로도 이 표준 컴포넌트를 재사용한다.
- Spring Security 공식 JWT 문서는 JWT 처리 시 서명과 시간/issuer 검증 및 Authentication 변환을 제공한다. 프로젝트 고유 `typ=access`, Role, `sub` 자료형, 오류 코드 구분은 추가 validator/filter 계약으로 보강한다.
- springdoc 공식 FAQ는 보호 API에 security requirement를 연결하고 OpenAPI components에 HTTP Bearer JWT security scheme을 등록하는 방식을 안내한다.
- 프로젝트는 Spring Boot `3.3.5`와 springdoc `2.6.0` 호환선을 이미 고정했다. Story 1.2에서 최신 메이저로 임의 업그레이드하지 않는다.

### Project Structure Notes

- 실제 Spring Boot 루트는 `springboot/`다. 과거 문서의 `backend-spring/` 예시는 사용하지 않는다.
- 기존 `web/HealthController`를 이동하거나 응답 계약을 변경하지 않는다.
- Vue 로그인 화면, 토큰 보관 방식, CORS 최종 allowlist는 이 스토리 범위 밖이다.
- FastAPI, AI 흐름, 다른 도메인 테이블은 수정하지 않는다.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Epic 1 / Story 1.2]
- [Source: `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md` - §4.1 FR-2, FR-27, FR-28 및 인증·인가 통합 승인 기준]
- [Source: `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/addendum.md` - 첫 번째 구현 증분 인증·인가 기술 제약]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md` - §12.1, §12.3~12.8, §12.9, §12.11]
- [Source: `_bmad-output/implementation-artifacts/1-1-safe-signup-and-api-docs-foundation.md`]
- [Source: `springboot/build.gradle`]
- [Source: `springboot/src/main/resources/application.yml`]
- [Source: `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`]
- [Spring Security JWT resource server reference](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security DaoAuthenticationProvider reference](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html)
- [springdoc Bearer authorization FAQ](https://springdoc.org/#how-do-i-add-authorization-header-in-requests)

## Dev Agent Record

### Agent Model Used

GPT-5.4

### Debug Log References

- 2026-06-12: Story 1.2 fresh implementation started from existing Story 1.1 working tree.
- 2026-06-12: RED confirmed missing JWT configuration and login/JWT/security API contracts before implementation.
- 2026-06-12: Fixed test resource shadowing of main `application.yml` and moved the test-only JWT secret to the Gradle test process.
- 2026-06-12: Final Docker Gradle `clean build` passed with PostgreSQL Testcontainers: 42 tests, 0 failures, 0 errors, 0 skipped.
- 2026-06-12: Code review patches verified with Docker Gradle `clean test` and PostgreSQL Testcontainers: 49 tests, 0 failures.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created
- Story 1.1 구현과 리뷰 학습 내용을 Story 1.2 가드레일에 반영함
- `sprint-status.yaml`이 없어 스프린트 상태 항목은 갱신하지 않음
- HS256 Access Token 설정·발급·검증을 Spring Security OAuth2 JOSE 기반으로 구현하고 Base64 256-bit Secret 및 고정 15분 TTL을 검증함
- 정규화 로그인, 동일 자격 증명 실패 응답, 무상태 JWT 필터, 공통 보안 오류 JSON, `@AuthenticationPrincipal AuthPrincipal` 기반 `/api/users/me`를 구현함
- `bearerAuth` OpenAPI 계약과 로그인/보호 API 문서를 추가하고 기본·운영·혼합 프로필 Swagger 비활성 정책을 보존함
- 회원가입 → 로그인 → Bearer Access Token → `/api/users/me` 종단 흐름, JWT 변조·만료·계약 위반, 민감정보 비노출, Story 1.1 회귀를 포함한 42개 테스트가 통과함

### File List

- `_bmad-output/implementation-artifacts/1-2-login-and-jwt-access-token-protected-api.md`
- `.env.example`
- `docker-compose.yml`
- `springboot/build.gradle`
- `springboot/src/main/resources/application.yml`
- `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/dto/LoginRequest.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/dto/LoginResponse.java`
- `springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java`
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`
- `springboot/src/main/java/com/commitgotchi/common/error/GlobalExceptionHandler.java`
- `springboot/src/main/java/com/commitgotchi/common/error/InvalidCredentialsException.java`
- `springboot/src/main/java/com/commitgotchi/security/AuthPrincipal.java`
- `springboot/src/main/java/com/commitgotchi/security/AuthUserDetails.java`
- `springboot/src/main/java/com/commitgotchi/security/AuthUserDetailsService.java`
- `springboot/src/main/java/com/commitgotchi/security/ExpiredAccessTokenException.java`
- `springboot/src/main/java/com/commitgotchi/security/InvalidAccessTokenException.java`
- `springboot/src/main/java/com/commitgotchi/security/IssuedAccessToken.java`
- `springboot/src/main/java/com/commitgotchi/security/JwtAuthenticationFilter.java`
- `springboot/src/main/java/com/commitgotchi/security/JwtConfiguration.java`
- `springboot/src/main/java/com/commitgotchi/security/JwtTokenProvider.java`
- `springboot/src/main/java/com/commitgotchi/security/OpenApiSecurityConfig.java`
- `springboot/src/main/java/com/commitgotchi/security/RestAuthenticationEntryPoint.java`
- `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`
- `springboot/src/main/java/com/commitgotchi/security/SecurityErrorResponseWriter.java`
- `springboot/src/main/java/com/commitgotchi/user/api/UserController.java`
- `springboot/src/main/java/com/commitgotchi/user/api/dto/CurrentUserResponse.java`
- `springboot/src/main/java/com/commitgotchi/user/domain/UserRepository.java`
- `springboot/src/test/java/com/commitgotchi/auth/LoginAndCurrentUserIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/auth/application/AuthServiceTest.java`
- `springboot/src/test/java/com/commitgotchi/security/JwtConfigurationTest.java`
- `springboot/src/test/java/com/commitgotchi/security/JwtAuthenticationFilterTest.java`
- `springboot/src/test/java/com/commitgotchi/security/JwtTokenProviderTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/JwtOpenApiContractIntegrationTest.java`

## Change Log

- 2026-06-12: Story 1.2 로그인, HS256 Access Token, 보호 API, 공통 보안 오류, Bearer OpenAPI 및 자동화 검증 구현 완료.
- 2026-06-12: 코드 리뷰 후 BCrypt 72-byte 로그인 경계, JWT `nbf`/15분 수명, 인증 예외 범위, Bearer 대소문자, 필터 체인 예외 범위 및 계약 테스트를 보강함.
