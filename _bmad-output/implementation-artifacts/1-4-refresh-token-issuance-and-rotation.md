---
baseline: Story 1.3 완료 작업 트리 (회원가입, 로그인, JWT Access Token, Role 인가, 관리자 API)
baseline_commit: NO_VCS
---

# Story 1.4: Refresh Token 발급과 Rotation

Status: done

## Story

As a 로그인 사용자,
I want 장기 Refresh Token으로 안전하게 Token Pair를 재발급받고,
so that Access Token 만료 후에도 자격 증명을 다시 입력하지 않고 서비스를 계속 사용할 수 있다.

## 목적과 범위

- 로그인 성공 응답을 15분 Access Token과 30일 Refresh Token을 함께 반환하는 Token Pair로 확장한다.
- Refresh Token은 32바이트 CSPRNG opaque Base64url no-padding 값으로 발급하고, DB에는 SHA-256 lowercase hex 해시만 저장한다.
- `POST /api/auth/refresh`에서 행 잠금 기반 Rotation, 동시 요청 직렬화, 폐기 토큰 재사용 탐지와 사용자 활성 Refresh Token 전체 폐기를 완성한다.
- `V2__create_refresh_tokens.sql`, JPA 도메인/Repository, Refresh Token 서비스, 공통 오류, OpenAPI 문서, 자동화 테스트를 추가한다.
- Story 1.1~1.3의 회원가입, JWT Access Token, `/api/users/me`, Role 인가, 공통 오류/traceId, 프로필별 Swagger 정책을 보존한다.
- 이 스토리는 재발급만 다룬다. 로그아웃, CORS 최종 allowlist, Access Token blacklist, 기기별 token-family, 토큰 정리 배치는 Story 1.5 또는 후속 범위다.

## Acceptance Criteria

1. **Flyway V2와 Refresh Token 스키마**
   - **Given** Story 1.4 마이그레이션이 적용될 때
   - **When** Flyway와 JPA 검증이 실행되면
   - **Then** 기존 V1 이후 `V2__create_refresh_tokens.sql`만 추가 적용된다.
   - **And** `refresh_tokens`는 UUID PK, `user_id` FK, unique `token_hash CHAR(64)`, `expires_at`, nullable `revoked_at`, `created_at`을 가진다.
   - **And** 사용자 삭제 시 토큰이 함께 삭제되는 `ON DELETE CASCADE`, 만료/폐기 시각 CHECK, 활성 토큰 조회 및 정리용 인덱스가 생성된다.
   - **And** Hibernate `ddl-auto=validate`가 성공하고 인증 외 도메인 테이블과 V3 이상 마이그레이션은 생성되지 않는다.

2. **로그인 성공 시 Token Pair 발급과 원문 비저장**
   - **Given** 사용자가 올바른 이메일과 비밀번호로 로그인할 때
   - **When** `POST /api/auth/login`이 성공하면
   - **Then** `tokenType`, 15분 `accessToken`, `accessTokenExpiresAt`, 30일 `refreshToken`, `refreshTokenExpiresAt`이 함께 반환된다.
   - **And** Refresh Token은 `SecureRandom`으로 생성한 정확히 32바이트를 Base64 URL-safe no-padding으로 인코딩한 opaque 값이다.
   - **And** DB에는 `SHA-256(rawRefreshToken UTF-8)`의 lowercase hex 64자만 저장되고 원문은 저장되지 않는다.
   - **And** 저장 레코드는 인증된 사용자 ID, 만료 시각, 생성 시각과 연결된다.
   - **And** 응답과 로그에는 비밀번호, 해시, JWT/Refresh Token 원문, Authorization 헤더, Secret, 내부 예외 상세가 노출되지 않는다.

3. **유효한 Refresh Token Rotation**
   - **Given** 활성 상태이며 만료되지 않은 Refresh Token이 있을 때
   - **When** `POST /api/auth/refresh`에 해당 원문 토큰을 제출하면
   - **Then** 로그인과 동일한 Token Pair 형식으로 새 Access Token과 새 Refresh Token이 반환된다.
   - **And** 제출 토큰 행은 token hash로 조회하면서 비관적 쓰기 잠금(`SELECT ... FOR UPDATE`)된다.
   - **And** 기존 Refresh Token은 새 Token Pair 응답 전에 폐기되고 새 Refresh Token 해시는 저장된다.
   - **And** 잠금 조회, 활성/만료 검증, 기존 토큰 폐기, 새 토큰 저장은 하나의 일관된 Rotation 트랜잭션에서 처리된다.
   - **And** 새 Access Token의 사용자 ID·정규화 이메일·현재 Role은 DB의 토큰 소유 사용자에서 파생하며 클라이언트 입력이나 만료 Access Token을 신뢰하지 않는다.

4. **동일 Refresh Token 동시 재발급 직렬화**
   - **Given** 동일한 활성 Refresh Token으로 두 재발급 요청이 동시에 시작될 때
   - **When** 두 요청이 Rotation을 수행하면
   - **Then** 행 잠금으로 정확히 하나의 요청만 정상 Rotation을 수행한다.
   - **And** 다른 요청은 첫 요청이 폐기한 토큰의 재사용으로 판정되어 `401 AUTH_REFRESH_TOKEN_REUSED`를 반환한다.
   - **And** 재사용 대응에 따라 사용자 활성 Refresh Token 전체가 폐기되므로 첫 요청에서 반환된 새 Refresh Token도 이후 재발급에 사용할 수 없다.
   - **And** 테스트는 단순 mock이 아니라 PostgreSQL Testcontainers에서 실제 동시 요청으로 이 결과를 검증한다.

5. **폐기 Refresh Token 재사용 탐지와 폐기 커밋 보장**
   - **Given** Rotation으로 이미 폐기된 Refresh Token이 있을 때
   - **When** 해당 원문 토큰으로 재발급을 요청하면
   - **Then** 같은 사용자의 모든 활성 Refresh Token이 폐기된다.
   - **And** 전체 폐기 처리는 이후 `AUTH_REFRESH_TOKEN_REUSED` 예외가 발생해도 롤백되지 않고 커밋된다.
   - **And** 공통 오류 형식의 `401 AUTH_REFRESH_TOKEN_REUSED`가 반환된다.
   - **And** 재사용 대응 트랜잭션은 Spring 프록시 self-invocation에 의존하지 않도록 별도 Spring bean의 `REQUIRES_NEW` 또는 동등하게 검증 가능한 트랜잭션 경계로 구현한다.

6. **만료·변조·미존재 Refresh Token 실패**
   - **Given** 만료됐거나 형식이 잘못됐거나 DB에 존재하지 않는 Refresh Token이 제출될 때
   - **When** 재발급을 요청하면
   - **Then** 공통 오류 형식의 `401 AUTH_REFRESH_TOKEN_INVALID`가 반환된다.
   - **And** opaque 토큰의 일부 문자를 변경한 경우도 존재하지 않는 해시로 안전하게 실패한다.
   - **And** 응답과 로그는 토큰 원문, 토큰/계정 존재 여부, 내부 파싱·DB 예외 상세를 추가로 노출하지 않는다.
   - **And** 잘못된 JSON, missing/null `refreshToken`, unknown field는 기존 계약대로 `400 VALIDATION_FAILED`지만, 문자열로 제출된 잘못된 opaque 토큰은 `401 AUTH_REFRESH_TOKEN_INVALID`로 분류한다.

7. **공개 재발급 경로와 OpenAPI 문서**
   - **Given** Access Token이 없거나 만료된 클라이언트가 유효한 Refresh Token을 보유할 때
   - **When** `POST /api/auth/refresh`를 호출하면
   - **Then** 경로는 `permitAll`이며 Refresh Token 자체를 자격 증명으로 사용해 재발급할 수 있다.
   - **Given** Swagger가 활성화된 환경일 때
   - **When** 로그인과 재발급 문서를 확인하면
   - **Then** 공통 Token Pair 성공 응답, Rotation 의미, `AUTH_REFRESH_TOKEN_INVALID`, `AUTH_REFRESH_TOKEN_REUSED`가 문서화된다.
   - **And** 로그인과 재발급 operation에는 Bearer 보안 요구가 붙지 않는다.
   - **And** 예시는 `<access-token>`, `<refresh-token>` 같은 안전한 placeholder만 사용하고 실제 JWT, Refresh Token, 비밀번호, Secret을 포함하지 않는다.

8. **Story 1.4 자동화 검증과 전체 회귀 보존**
   - **Given** Story 1.4 테스트가 실행될 때
   - **When** 단위·Repository/DB·Rotation·동시성·API 통합·OpenAPI 테스트가 완료되면
   - **Then** 32바이트 생성, Base64url no-padding, 30일 만료, SHA-256 lowercase hex, 원문 비저장이 검증된다.
   - **And** 로그인 Token Pair, 정상 Rotation, 기존 토큰 폐기, 만료·변조·미존재·폐기 토큰, 재사용 시 전체 활성 토큰 폐기, 동시 요청 결과가 검증된다.
   - **And** Refresh Token 실패 응답과 캡처 로그에 토큰 원문·JWT·Authorization·Secret·stack trace가 없음을 검증한다.
   - **And** Story 1.1~1.3의 회원가입, 로그인 실패, JWT 보안, `/api/users/me`, USER/ADMIN 인가, Swagger 프로필 정책이 계속 통과한다.

## Tasks / Subtasks

- [x] **Task 1: Flyway V2와 Refresh Token 도메인을 추가한다** (AC: 1, 8)
  - [x] 아키텍처 §12.2.2와 동일한 `springboot/src/main/resources/db/migration/V2__create_refresh_tokens.sql`을 추가한다.
  - [x] `auth/domain/RefreshToken.java`에 UUID ID, 사용자 식별 관계, token hash, expires/revoked/created 시각과 상태 전이 메서드를 구현한다.
  - [x] 엔티티가 raw Refresh Token 필드를 갖지 않게 하고 `toString`/로그로 민감정보가 노출될 여지를 만들지 않는다.
  - [x] `RefreshTokenRepository`에 token hash 비관적 잠금 조회, 사용자별 활성 토큰 폐기, 상태 검증에 필요한 최소 쿼리를 추가한다.
  - [x] 잠금 조회는 `token_hash`만으로 폐기·만료 행까지 반환해야 한다. 조회 단계에서 active 조건을 걸어 폐기 토큰 재사용을 미존재 토큰으로 오분류하지 않는다.
  - [x] `@Lock(PESSIMISTIC_WRITE)` 또는 동등한 명시적 잠금 쿼리가 실제 PostgreSQL 행 잠금을 생성하도록 트랜잭션 내에서 사용한다.
  - [x] 기존 `DatabaseMigrationIntegrationTest`를 V1-only 기대에서 V1+V2 및 V2 제약/인덱스 검증으로 갱신한다.

- [x] **Task 2: Refresh Token 생성·해시·발급 컴포넌트를 구현한다** (AC: 2, 8)
  - [x] `SecureRandom.nextBytes(new byte[32])`와 `Base64.getUrlEncoder().withoutPadding()`으로 opaque 토큰을 생성한다.
  - [x] SHA-256은 JDK `MessageDigest`를 사용하고 raw token의 UTF-8 bytes를 lowercase hex 64자로 변환한다. JWT/암호 라이브러리를 추가하지 않는다.
  - [x] 기존 UTC `Clock` bean을 재사용해 `createdAt`, `expiresAt=createdAt+30일`, `revokedAt`을 결정론적으로 계산한다.
  - [x] raw token과 만료 시각만 호출자에게 일회 반환하는 불변 발급 결과 DTO를 만들고 DB 도메인에는 raw 값을 보관하지 않는다.
  - [x] 생성 길이·문자 집합·padding 부재·해시 결정성·원문 비저장 단위 테스트를 추가한다.

- [x] **Task 3: 로그인 응답을 공통 Token Pair로 확장한다** (AC: 2, 7, 8)
  - [x] `AuthService.login`이 인증 성공 후 Access Token과 Refresh Token 레코드를 함께 발급하도록 확장한다.
  - [x] 기존 `LoginResponse`를 공통 `TokenPairResponse`로 명확히 리팩터링하거나 동등한 단일 DTO를 사용해 로그인과 재발급 응답 형식이 갈라지지 않게 한다.
  - [x] 기존 Access Token 발급, 이메일 정규화, `AUTH_INVALID_CREDENTIALS`, 인프라 예외 비은폐 계약을 보존한다.
  - [x] 로그인 중 Refresh Token 저장에 실패하면 성공 Token Pair를 반환하지 않는다.
  - [x] 기존 로그인 통합/OpenAPI 테스트의 `refreshToken doesNotExist` 기대를 Token Pair 계약으로 갱신한다.

- [x] **Task 4: `/api/auth/refresh` Rotation 유스케이스를 구현한다** (AC: 3, 6, 7)
  - [x] unknown field를 거부하는 `RefreshTokenRequest`와 공통 `TokenPairResponse`를 사용해 `POST /api/auth/refresh`를 추가한다.
  - [x] 요청 필드 missing/null은 `400 VALIDATION_FAILED`, 값이 존재하지만 Base64url/길이가 잘못된 문자열은 `401 AUTH_REFRESH_TOKEN_INVALID`로 구분한다.
  - [x] raw token 형식 검증 → SHA-256 → token hash 잠금 조회 → 상태 검증 → 기존 폐기 → 새 토큰 저장 순서를 지킨다.
  - [x] 정상 Rotation 전체를 하나의 트랜잭션으로 처리하고 기존 토큰 폐기/새 토큰 저장 중 하나만 커밋되는 상태를 허용하지 않는다.
  - [x] 토큰 소유 사용자를 사용자 ID로 조회해 현재 email/Role로 새 Access Token을 발급한다. 사용자가 없으면 안전한 `AUTH_REFRESH_TOKEN_INVALID`로 처리한다.
  - [x] 형식 오류·만료·미존재를 `AUTH_REFRESH_TOKEN_INVALID`로, 이미 폐기된 토큰 제출을 `AUTH_REFRESH_TOKEN_REUSED`로 분류한다.
  - [x] `SecurityConfig`에 `/api/auth/refresh`를 공개 경로로 추가하고 기존 인가 정책을 보존한다.

- [x] **Task 5: 재사용 대응의 독립 커밋 경계를 구현한다** (AC: 4, 5, 8)
  - [x] 사용자 활성 Refresh Token 전체 폐기 작업을 별도 Spring bean의 `REQUIRES_NEW` 또는 동등한 명시적 트랜잭션 경계로 분리한다.
  - [x] 같은 클래스 내부 `this.method()` 호출에 `@Transactional(REQUIRES_NEW)`만 붙이는 self-invocation 구현을 금지한다.
  - [x] 폐기 커밋 후 `AUTH_REFRESH_TOKEN_REUSED`를 반환하도록 구성하고, 예외 발생 뒤 DB를 새 트랜잭션에서 조회해 폐기가 유지됐는지 검증한다.
  - [x] `REQUIRES_NEW` 사용 시 외부 트랜잭션과 별도 DB connection을 사용함을 고려해 동시성 테스트가 교착/풀 고갈 없이 완료되는지 확인한다.

- [x] **Task 6: Refresh Token 공통 오류와 OpenAPI 문서를 추가한다** (AC: 5, 6, 7)
  - [x] `ErrorCode`에 안전한 메시지의 `AUTH_REFRESH_TOKEN_INVALID`, `AUTH_REFRESH_TOKEN_REUSED`를 추가한다.
  - [x] 내부 Refresh Token 예외를 `GlobalExceptionHandler`에서 공통 `ErrorResponse`로 변환하되 raw token이나 내부 원인을 전달하지 않는다.
  - [x] 로그인과 재발급 operation의 Token Pair, `400`, 두 종류의 `401` 응답을 문서화한다.
  - [x] `/v3/api-docs` 계약 테스트에서 두 공개 operation, 공통 Token Pair schema, 안전한 placeholder, 오류 코드를 검증한다.

- [x] **Task 7: Repository·Rotation·동시성·보안 회귀 테스트를 완성한다** (AC: 1~8)
  - [x] Repository/DB: V2 적용, FK/cascade, token hash unique, CHECK, 활성/정리 인덱스, 잠금 조회를 검증한다.
  - [x] API 통합: 가입 → 로그인 Token Pair → refresh → 새 Access Token으로 `/api/users/me` 성공을 검증한다.
  - [x] Rotation 후 기존 토큰의 `revokedAt` 설정과 새 해시 저장을 검증하고 DB 어디에도 raw token이 없는지 검사한다.
  - [x] 만료·변조·미존재·폐기 토큰 오류 코드와 재사용 시 전체 활성 토큰 폐기를 검증한다.
  - [x] 동일 토큰 동시 요청은 executor/barrier로 실제 겹치게 실행하고 하나의 성공, 하나의 `AUTH_REFRESH_TOKEN_REUSED`, 최종 활성 토큰 0개를 검증한다.
  - [x] `OutputCaptureExtension`과 응답 본문 검사로 Access/Refresh Token 원문, Authorization, Secret, stack trace 비노출을 검증한다.
  - [x] `./gradlew clean test`로 기존 55개 테스트와 신규 Story 1.4 테스트를 모두 실행한다.

## Dev Notes

### Developer Context

- 현재 작업 트리에는 Story 1.1~1.3 구현이 완료되어 있고, 2026-06-12 기준 `./gradlew clean test`로 **55 tests / 0 failures**가 확인됐다. 이 상태를 회귀 기준선으로 사용한다.
- 이 스토리의 핵심은 단순히 Refresh Token 문자열을 반환하는 것이 아니라 **원문 비저장 + Rotation 원자성 + 실제 행 잠금 + 재사용 대응 커밋 보장**이다.
- Refresh Token은 JWT가 아니다. Claim, 서명, decoder를 만들지 말고 고엔트로피 opaque credential로 취급한다.
- Refresh Token 자체가 재발급 자격 증명이므로 `/api/auth/refresh`는 Access Token 없이 호출 가능해야 한다. 보호 Controller principal이나 SecurityContext에 의존하지 않는다.
- Rotation 시 새 Access Token은 refresh token 소유 사용자의 현재 DB email/Role로 발급한다. 제출 요청에 userId/email/role을 받거나 만료 Access Token을 파싱해 재식별하지 않는다.
- 동일 토큰 동시 재발급의 두 번째 요청은 첫 Rotation 후 폐기 토큰 재사용으로 분류된다. 현재 token-family가 없으므로 보수적 정책에 따라 새로 발급된 토큰까지 모두 폐기되는 결과가 의도된 계약이다.
- 재사용 대응에서 가장 흔한 실패는 전체 폐기 후 `AUTH_REFRESH_TOKEN_REUSED` 런타임 예외를 던져 같은 트랜잭션이 롤백되는 것이다. 테스트는 HTTP 401만 보지 말고 예외 이후 DB 상태를 별도 트랜잭션에서 확인해야 한다.

### Current Files To Update

- `springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java`
  - 현재: 로그인 인증 후 `JwtTokenProvider.issue(...)`로 Access Token만 발급한다.
  - 변경: Refresh Token 발급을 조정하고 공통 Token Pair 응답을 반환한다.
  - 보존: 로그인 정규화, BCrypt 72-byte 경계, `BadCredentialsException`만 안전한 401로 변환하고 인프라 장애는 숨기지 않는 계약.
- `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`
  - 현재: signup/login 공개 API와 OpenAPI 문서를 제공한다.
  - 변경: refresh operation과 Token Pair/Refresh 오류 문서를 추가한다.
  - 보존: signup/login 상태·검증·공개 API 계약.
- `springboot/src/main/java/com/commitgotchi/auth/api/dto/LoginResponse.java`
  - 현재: `tokenType`, `accessToken`, `accessTokenExpiresAt`만 제공한다.
  - 변경: 로그인과 refresh가 공유하는 Token Pair DTO로 확장/리팩터링한다.
  - 보존: 기존 세 필드명과 UTC ISO-8601 직렬화.
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`
  - 현재: Access Token과 Role 인가 오류까지 정의한다.
  - 변경: `AUTH_REFRESH_TOKEN_INVALID`, `AUTH_REFRESH_TOKEN_REUSED`를 추가한다.
  - 보존: 기존 오류 코드, HTTP 상태, 안전한 메시지.
- `springboot/src/main/java/com/commitgotchi/common/error/GlobalExceptionHandler.java`
  - 현재: MVC 예외를 공통 오류 형식으로 변환한다.
  - 변경: Refresh Token 예외 두 종류를 안전하게 매핑한다.
  - 보존: traceId, catch-all HTTP 의미, 민감정보 비노출.
- `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`
  - 현재: signup/login/health/Swagger 공개, `/api/admin/**` ADMIN, `/api/users/me` USER·ADMIN, 나머지 인증 요구.
  - 변경: `/api/auth/refresh`를 `permitAll`에 추가한다.
  - 보존: stateless/CSRF, 필터 순서, EntryPoint/DeniedHandler, 모든 기존 경로 정책.
- `springboot/src/test/java/com/commitgotchi/user/DatabaseMigrationIntegrationTest.java`
  - 현재: Flyway V1만 적용됨을 검증한다.
  - 변경: V1+V2와 Refresh Token 제약/인덱스/FK/cascade를 검증한다.
  - 보존: users 정규화 email unique 및 Role CHECK 검증.
- `springboot/src/test/java/com/commitgotchi/auth/LoginAndCurrentUserIntegrationTest.java`
  - 현재: 로그인 응답에 Refresh Token이 없음을 명시적으로 검증한다.
  - 변경: Token Pair와 원문 비저장, refresh 종단 흐름을 검증한다.
  - 보존: 로그인 실패, JWT 실패, `/api/users/me`, 민감정보 비노출 회귀.
- `springboot/src/test/java/com/commitgotchi/swagger/JwtOpenApiContractIntegrationTest.java`
  - 현재: `LoginResponse`에 `refreshToken`이 없음을 검증한다.
  - 변경: 로그인/refresh의 공통 Token Pair와 Refresh 오류 문서를 검증한다.
  - 보존: Bearer schema와 `/api/users/me` 보호 계약.
- `springboot/src/main/resources/application.yml`, `springboot/build.gradle`, `.env.example`, `docker-compose.yml`
  - 원칙적으로 새 런타임 의존성이나 Secret 환경변수는 필요 없다. 기존 Java 17/Boot 3.3.5/JPA/PostgreSQL 설정을 보존한다.
  - Refresh Token 30일 TTL을 설정으로 노출한다면 고정 계약을 벗어난 값은 시작 시 거부하고 실제 Secret/토큰 값을 추가하지 않는다.

### Expected New Files

```text
springboot/src/main/java/com/commitgotchi/
├── auth/
│   ├── api/dto/
│   │   ├── RefreshTokenRequest.java
│   │   └── TokenPairResponse.java                  # LoginResponse 대체/공유 DTO 권장
│   ├── application/
│   │   ├── RefreshTokenService.java
│   │   └── RefreshTokenReuseRevocationService.java # 독립 커밋 경계
│   └── domain/
│       ├── RefreshToken.java
│       └── RefreshTokenRepository.java
└── common/error/
    ├── InvalidRefreshTokenException.java
    └── ReusedRefreshTokenException.java

springboot/src/main/resources/db/migration/
└── V2__create_refresh_tokens.sql

springboot/src/test/java/com/commitgotchi/
├── auth/
│   ├── RefreshTokenApiIntegrationTest.java
│   └── application/RefreshTokenServiceTest.java
└── user/DatabaseMigrationIntegrationTest.java       # 기존 파일 갱신
```

- 클래스명과 작은 결과 DTO 분리는 조정할 수 있지만, API → application → domain/repository 의존 방향과 독립 재사용 폐기 트랜잭션 경계는 유지한다.
- `Logout*`, CORS 설정, token-family 컬럼, cleanup scheduler, Vue/FastAPI 변경은 이 스토리에 추가하지 않는다.

### Technical Requirements

- **Token Pair 성공 응답**

```json
{
  "tokenType": "Bearer",
  "accessToken": "<access-token>",
  "accessTokenExpiresAt": "2026-06-12T10:15:00Z",
  "refreshToken": "<refresh-token>",
  "refreshTokenExpiresAt": "2026-07-12T10:00:00Z"
}
```

- **재발급 요청**

```http
POST /api/auth/refresh
Content-Type: application/json
```

```json
{ "refreshToken": "<refresh-token>" }
```

- **Refresh Token 형식:** 32 random bytes → Base64 URL-safe no-padding. 정상 인코딩 문자열은 43자다. raw token은 응답 시 한 번만 반환한다.
- **해시:** `SHA-256(rawToken.getBytes(UTF_8))` → lowercase hex 64자. 고엔트로피 opaque token 조회용이므로 BCrypt를 사용하지 않는다.
- **유효기간:** 발급 시각부터 정확히 30일. 시각은 UTC `Clock`을 사용하고 API는 ISO-8601로 직렬화한다.
- **Rotation 상태 전이:** `ACTIVE -> REVOKED`. 제출 토큰 행 잠금 후 만료/폐기 상태를 검사하며 기존 토큰 폐기와 새 레코드 저장은 동일 트랜잭션이다.
- **잠금 조회 규칙:** hash 일치 행을 폐기/만료 상태와 무관하게 잠근 뒤 상태를 판정한다. `revoked_at IS NULL` 조건을 잠금 조회에 넣으면 재사용 탐지를 할 수 없다.
- **재사용 대응:** 폐기 토큰 제출 → 사용자 활성 토큰 전체 폐기 독립 커밋 → `401 AUTH_REFRESH_TOKEN_REUSED`.
- **오류 분류:** malformed/expired/not-found → `AUTH_REFRESH_TOKEN_INVALID`; revoked token submitted to refresh → `AUTH_REFRESH_TOKEN_REUSED`.
- **민감정보:** raw Access/Refresh Token, Authorization, token hash, JWT secret, 내부 예외 메시지를 응답이나 로그에 남기지 않는다.

### Architecture Compliance

- 인증 증분 SSOT는 아키텍처 §12이며, V2 SQL은 §12.2.2 스키마를 그대로 따른다.
- `AuthController`는 Repository, `EntityManager`, `SecurityContextHolder`, `JwtDecoder`에 직접 의존하지 않는다.
- `RefreshTokenService`는 생성·해시·저장·Rotation을 캡슐화하고, Controller는 raw token 처리 로직을 갖지 않는다.
- `RefreshTokenRepository` API에는 raw token을 전달하거나 반환하지 않는다. Repository 경계는 hash/ID/userId만 사용한다.
- Access Token 발급은 기존 `JwtTokenProvider`를 재사용하고 JWT 로직을 복제하지 않는다.
- Refresh Token은 기존 `JwtAuthenticationFilter`가 처리하지 않는다. refresh endpoint body credential은 application service에서 검증한다.
- DB 변경은 V2 하나뿐이다. 기존 V1을 수정하지 않고 V3 이상 또는 다른 도메인 테이블을 만들지 않는다.
- 로그인/refresh 성공 DTO는 하나의 Token Pair 계약을 공유해 필드 드리프트를 방지한다.

### Library / Framework Requirements

- 기준선은 Java 17, Spring Boot `3.3.5`, Spring Data JPA/Spring Framework Boot 관리 버전, PostgreSQL 16, Flyway, Testcontainers다. 이 스토리에서 메이저 업그레이드나 새 암호 라이브러리를 추가하지 않는다.
- JDK 표준 `SecureRandom`, `Base64.getUrlEncoder().withoutPadding()`, `MessageDigest.getInstance("SHA-256")`를 사용한다.
- Spring Data JPA Repository query method에 `@Lock(LockModeType.PESSIMISTIC_WRITE)`를 적용해 비관적 잠금을 선언할 수 있다.
- `PROPAGATION_REQUIRES_NEW`는 외부 트랜잭션과 독립적인 물리 트랜잭션/connection을 사용한다. 재사용 폐기 보존에는 적합하지만 connection pool 여유와 프록시 호출 경계를 고려해야 한다.
- `@Transactional` self-invocation으로 새 전파 수준이 적용된다고 가정하지 않는다. 별도 bean 호출 또는 명시적 `TransactionTemplate`을 사용한다.

### Testing Requirements

- Refresh Token 생성/시간 테스트는 실제 30일 대기를 사용하지 않고 기존 UTC `Clock`을 주입하거나 고정 Clock으로 검증한다.
- 단위 테스트는 32바이트 엔트로피, 43자 Base64url no-padding, SHA-256 64자 lowercase hex, 30일 만료를 검증한다.
- Repository 테스트는 H2가 아니라 실제 PostgreSQL Testcontainers를 사용한다. Flyway V2, partial index, FK/cascade, unique/CHECK를 확인한다.
- Rotation 통합 테스트는 응답의 새 raw token과 DB 모든 문자열 컬럼을 비교해 raw token이 저장되지 않았음을 검증한다.
- 동시성 테스트는 두 요청이 실제로 겹치도록 barrier/latch를 사용하고 timeout을 둬 무한 대기를 방지한다.
- 재사용 테스트는 401 응답 후 새로운 트랜잭션/조회로 사용자 활성 토큰이 0개인지 확인한다. 같은 테스트 트랜잭션 캐시로 잘못 통과하지 않게 한다.
- 로그인 종단 회귀는 `가입 -> 로그인 Token Pair -> accessToken으로 /me -> refresh -> 새 accessToken으로 /me`를 검증한다.
- OpenAPI 계약은 로그인/refresh가 공개 operation이고 공통 Token Pair와 두 Refresh 오류가 문서화되는지 확인한다.
- 전체 회귀 명령은 `cd springboot && ./gradlew clean test`다. Docker Desktop이 실행 중이어야 Testcontainers PostgreSQL 16을 사용할 수 있다.

### Previous Story Intelligence

- Story 1.3 완료 시 Gradle wrapper 8.10.2, Java toolchain 17, JUnit Platform launcher가 확립됐고 55 tests / 0 failures가 통과했다. 전역 Gradle 대신 `springboot/gradlew`를 사용한다.
- Story 1.2의 로그인 응답은 Story 1.4 확장을 고려해 Access Token 필드가 이미 분리되어 있다. 기존 필드명을 보존하고 Refresh 필드를 추가한다.
- `JwtTokenProvider`와 `JwtConfiguration`은 UTC `Clock`, 15분 TTL, 안전한 JWT 발급/검증을 이미 제공한다. Refresh Token용 JWT 구현이나 별도 Access Token 발급 로직을 만들지 않는다.
- 이전 리뷰에서 인증 인프라 장애를 `AUTH_INVALID_CREDENTIALS`로 숨기는 문제가 수정됐다. Refresh Token에서도 DB/트랜잭션 인프라 장애를 무조건 `AUTH_REFRESH_TOKEN_INVALID`로 숨기지 말고, 오직 계약상 invalid/reused 상태만 안전한 401로 매핑한다.
- 이전 리뷰에서 보안 필터 예외와 MVC 예외 경계가 확인됐다. `/api/auth/refresh`는 Controller/Application 예외이므로 `GlobalExceptionHandler`를 사용하며 `RestAuthenticationEntryPoint`에 Refresh 오류를 억지로 추가하지 않는다.
- 현재 통합 테스트의 `@BeforeEach userRepository.deleteAll()`은 V2의 `ON DELETE CASCADE` 후에도 동작해야 한다. 필요하면 refresh token 정리를 먼저 하되 FK/cascade 계약 자체를 약화하지 않는다.
- 기존 Swagger 계약 테스트는 `LoginResponse.refreshToken doesNotExist`를 명시적으로 검증하므로 Story 1.4에서 반드시 갱신해야 한다.

### Git Intelligence

- 최근 커밋은 문서 정렬과 초기 골격이며 Story 1.1~1.3 코드는 아직 작업 트리 기준선이다. 기존 사용자 변경과 인증 구현을 되돌리지 않는다.
- 실제 구현 패턴은 `auth`, `security`, `user`, `common/error`, `admin` 패키지 경계를 따른다. Refresh Token 구현을 Vue/FastAPI/다른 도메인으로 확산하지 않는다.
- `springboot/build.gradle`, `settings.gradle`, `application.yml`에는 Story 1.3 테스트 실행을 위해 추가된 wrapper/toolchain/JUnit 설정이 있으므로 유지한다.

### Latest Technical Information

- Spring Data JPA 공식 문서는 Repository query method에 `@Lock`을 선언해 JPA lock mode를 적용할 수 있음을 명시한다. Story 1.4의 hash 조회에는 `PESSIMISTIC_WRITE`를 사용한다.
- Spring Framework 공식 문서상 `PROPAGATION_REQUIRES_NEW`는 외부 범위와 독립적인 물리 트랜잭션으로 commit/rollback되며 별도 connection을 사용할 수 있다. 재사용 폐기를 보존하되 connection pool과 호출 경계를 테스트한다.
- Java 17 공식 API의 `SecureRandom.nextBytes`는 요청한 수의 random bytes를 채우며, `Base64.Encoder.withoutPadding()`은 padding 문자를 추가하지 않는 encoder를 반환한다.
- 최신 문서 버전이 프로젝트 기준선보다 높더라도 이 스토리는 Spring Boot `3.3.5` 호환선을 유지하며 메이저 업그레이드를 수행하지 않는다.

### Project Structure Notes

- 실제 Spring Boot 루트는 `springboot/`다. 과거 문서의 `backend-spring/` 예시는 사용하지 않는다.
- UX 문서는 로그인 화면만 다루며 Refresh Token을 사용자에게 직접 노출하는 UX 요구는 없다. 이 스토리는 백엔드 API/Swagger 범위다.
- Refresh Token은 현재 JSON body로 전달한다. HttpOnly cookie 전환과 그에 따른 CSRF 재검토는 비범위다.
- `refresh_tokens` 정리 배치와 7일 보존 정책은 아키텍처의 `[ASSUMPTION]`이며 Story 1.4 완료 조건이 아니다.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Epic 1 / Story 1.4]
- [Source: `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md` - FR-2, FR-24, FR-28 및 인증 승인 기준]
- [Source: `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/addendum.md` - 인증 증분 REFRESH_TOKENS 모델]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md` - §12.2.2, §12.3~12.7, §12.8.4, §12.8.6, §12.9, §12.11]
- [Source: `_bmad-output/implementation-artifacts/1-2-login-and-jwt-access-token-protected-api.md`]
- [Source: `_bmad-output/implementation-artifacts/1-3-role-based-admin-authorization.md`]
- [Source: `_bmad-output/implementation-artifacts/troubleshooting-story-1-3-test-execution.md`]
- [Source: `springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/auth/api/dto/LoginResponse.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/security/JwtTokenProvider.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`]
- [Source: `springboot/src/main/java/com/commitgotchi/common/error/GlobalExceptionHandler.java`]
- [Source: `springboot/src/test/java/com/commitgotchi/auth/LoginAndCurrentUserIntegrationTest.java`]
- [Source: `springboot/src/test/java/com/commitgotchi/swagger/JwtOpenApiContractIntegrationTest.java`]
- [Spring Data JPA Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [Spring Framework Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [Java 17 SecureRandom](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/SecureRandom.html)
- [Java 17 Base64 Encoder](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Base64.Encoder.html)

## Dev Agent Record

### Agent Model Used

GPT-5

### Debug Log References

- 2026-06-12: Story 1.4 create-story workflow completed from Story 1.3 done working tree.
- 2026-06-12: Story 1.4 implementation started; no sprint-status.yaml exists, so progress is tracked in this story file.
- 2026-06-12: RED 단계에서 신규 Refresh Token 서비스/DTO 부재로 테스트 실패를 확인한 후 구현함.
- 2026-06-12: PostgreSQL Testcontainers 동시 Rotation 테스트에서 1 success / 1 reused / active token 0 결과를 확인함.
- 2026-06-12: `./gradlew clean test` 최종 실행 결과 62 tests / 0 failures / 0 errors.

### Implementation Plan

- Flyway V2와 JPA 도메인/잠금 Repository를 먼저 추가한다.
- JDK 표준 암호 API와 UTC Clock으로 opaque 토큰 발급·해시를 구현한다.
- 로그인과 Rotation을 공통 Token Pair 응답으로 연결하고 재사용 폐기를 별도 `REQUIRES_NEW` bean으로 격리한다.
- PostgreSQL Testcontainers 기반 DB/Rotation/동시성/API/OpenAPI 회귀 테스트로 전체 계약을 검증한다.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created
- Story 1.4 상태를 `ready-for-dev`로 설정함
- `sprint-status.yaml`이 없어 스프린트 상태 항목은 갱신하지 않음
- Story 1.1~1.3 구현, 이전 리뷰 학습, 실제 수정 대상 파일, PostgreSQL Rotation 동시성, 재사용 폐기 트랜잭션 위험을 분석해 가드레일에 반영함
- Flyway V2, RefreshToken 도메인/Repository, 32-byte opaque 토큰 발급과 SHA-256 해시 저장을 구현함.
- 로그인과 `/api/auth/refresh`가 공통 Token Pair를 반환하도록 확장하고 정상 Rotation을 단일 트랜잭션으로 구현함.
- 폐기 토큰 재사용 시 별도 `REQUIRES_NEW` bean에서 사용자 활성 토큰 전체 폐기를 커밋하도록 구현함.
- invalid/reused 공통 오류, 공개 refresh 경로, 안전한 OpenAPI Token Pair 문서를 추가함.
- PostgreSQL 실제 행 잠금 동시 요청, 재사용 폐기 커밋, 원문 비저장/비노출, 전체 인증 회귀를 검증함.
- 최종 `./gradlew clean test`: 62 tests / 0 failures / 0 errors.

### File List

- `_bmad-output/implementation-artifacts/1-4-refresh-token-issuance-and-rotation.md`
- `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/dto/LoginResponse.java` (deleted)
- `springboot/src/main/java/com/commitgotchi/auth/api/dto/RefreshTokenRequest.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/dto/TokenPairResponse.java`
- `springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java`
- `springboot/src/main/java/com/commitgotchi/auth/application/IssuedRefreshToken.java`
- `springboot/src/main/java/com/commitgotchi/auth/application/RefreshTokenReuseRevocationService.java`
- `springboot/src/main/java/com/commitgotchi/auth/application/RefreshTokenService.java`
- `springboot/src/main/java/com/commitgotchi/auth/domain/RefreshToken.java`
- `springboot/src/main/java/com/commitgotchi/auth/domain/RefreshTokenRepository.java`
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`
- `springboot/src/main/java/com/commitgotchi/common/error/GlobalExceptionHandler.java`
- `springboot/src/main/java/com/commitgotchi/common/error/InvalidRefreshTokenException.java`
- `springboot/src/main/java/com/commitgotchi/common/error/ReusedRefreshTokenException.java`
- `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`
- `springboot/src/main/java/com/commitgotchi/security/JwtAuthenticationFilter.java`
- `springboot/src/main/resources/db/migration/V2__create_refresh_tokens.sql`
- `springboot/src/test/java/com/commitgotchi/auth/LoginAndCurrentUserIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/auth/RefreshTokenApiIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/auth/application/AuthServiceTest.java`
- `springboot/src/test/java/com/commitgotchi/auth/application/RefreshTokenServiceTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/JwtOpenApiContractIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/user/DatabaseMigrationIntegrationTest.java`

## Change Log

- 2026-06-12: Story 1.4 Refresh Token 발급과 Rotation 구현 컨텍스트 작성, 상태 `ready-for-dev`.
- 2026-06-12: Refresh Token 발급·해시 저장, 로그인 Token Pair, 행 잠금 Rotation, 재사용 전체 폐기, 공통 오류/OpenAPI 및 자동화 테스트 구현; 상태 `review`.
- 2026-06-12: 코드 리뷰 반영 — (M1) `JwtAuthenticationFilter.shouldNotFilter`로 공개 인증 경로(`/api/auth/refresh` 등) 필터 우회: 만료/무효 Access Token이 헤더에 실려도 Rotation 동작(AC7 보강), (M2) `RefreshToken implements Persistable<UUID>`로 `save()`의 merge→persist 전환(발급마다 불필요한 SELECT 제거), 만료 Access Token 헤더 동반 refresh 통합 테스트 추가. `./gradlew clean test` 통과(63 tests / 0 failures).
- 2026-06-12: 코드 리뷰 반영과 전체 회귀 통과를 완료 근거로 상태 `review` → `done`.
