---
baseline_commit: b24cc6d2f41d6a48407d6c75e4ebce07682a991b
---

# Story 1.1: 안전한 회원가입과 API 문서 기반 구축

Status: done

## Story

As a 신규 사용자,
I want 이메일과 비밀번호로 안전하게 가입하고,
so that `USER` 권한 계정을 생성하여 서비스 이용을 시작할 수 있다.

## 목적과 범위

- 로그인이나 Refresh Token 없이 독립 검증 가능한 회원가입 흐름을 완성한다.
- Spring Boot 인증 증분의 공통 기반인 Flyway, 입력 검증, 공통 오류 응답, 최소 SecurityFilterChain, 프로필 제한 Swagger를 구축한다.
- Story 1.1에서는 `users` 테이블과 회원가입 API만 구현한다.
- 로그인, JWT 발급/검증, `/api/users/me`, 관리자 API, `refresh_tokens`, 로그아웃은 구현하지 않는다.

## Acceptance Criteria

1. **Flyway/JPA 스키마 기준선**
   - **Given** 애플리케이션이 시작될 때
   - **When** Flyway 마이그레이션과 JPA 검증이 실행되면
   - **Then** `V1__create_users.sql`만 적용된다.
   - **And** 정규화 이메일 `UNIQUE`/`CHECK`와 `USER|ADMIN` Role `CHECK`가 강제된다.
   - **And** Hibernate는 모든 환경에서 `ddl-auto=validate`를 사용한다.
   - **And** 인증 외 도메인 테이블과 `refresh_tokens`는 생성하지 않는다.

2. **정상 회원가입**
   - **Given** 유효한 이메일과 정책을 충족하는 비밀번호가 주어졌을 때
   - **When** `POST /api/auth/signup`을 호출하면
   - **Then** 이메일은 `trim()` 후 `Locale.ROOT` lowercase로 정규화되어 저장된다.
   - **And** 비밀번호는 BCrypt cost 12 해시로 저장된다.
   - **And** Role은 항상 `USER`다.
   - **And** `201 Created`와 `id`, 정규화된 `email`, `role`, UTC ISO-8601 `createdAt`이 반환된다.
   - **And** 비밀번호와 `passwordHash`는 응답 및 로그에 포함되지 않는다.

3. **정규화 이메일 중복 거부**
   - **Given** 동일 이메일의 대소문자 또는 앞뒤 공백 변형 계정이 존재할 때
   - **When** 회원가입을 요청하면
   - **Then** 공통 오류 형식의 `409 USER_EMAIL_CONFLICT`가 반환된다.
   - **And** DB unique 제약 경합으로 중복이 감지된 경우에도 같은 계약으로 변환된다.

4. **검증 실패와 Role 주입 차단**
   - **Given** 이메일 형식 또는 비밀번호 정책이 잘못되었거나 요청에 `role`을 포함한 알 수 없는 필드가 있을 때
   - **When** 회원가입을 요청하면
   - **Then** 공통 오류 형식의 `400 VALIDATION_FAILED`가 반환된다.
   - **And** 공개 요청으로 `ADMIN` 계정이 생성되지 않는다.
   - **And** 오류 응답은 최소 `status`, `code`, 안전한 `message`, `timestamp`, `traceId`를 포함한다.

5. **개발/테스트 Swagger 접근 및 회원가입 문서**
   - **Given** 애플리케이션이 `local`, `dev`, `test` 프로필로 실행될 때
   - **When** Swagger UI 또는 `/v3/api-docs`에 접근하면
   - **Then** 문서에 접근할 수 있다.
   - **And** 회원가입 요청, `201` 성공 응답, `400 VALIDATION_FAILED`, `409 USER_EMAIL_CONFLICT`가 문서화되어 있다.
   - **And** 회원가입, Swagger, 기존 health 경로는 인증 없이 접근 가능하다.
   - **And** 문서 예시에 실제 비밀번호, 해시, JWT, Refresh Token, Secret을 포함하지 않는다.

6. **운영 Swagger 비활성화**
   - **Given** 애플리케이션이 운영 프로필 또는 명시적 개발 프로필 없이 실행될 때
   - **When** Swagger UI 또는 `/v3/api-docs`에 접근하면
   - **Then** 해당 엔드포인트가 생성되지 않거나 접근할 수 없다.

7. **Story 1.1 자동화 검증**
   - **Given** Story 1.1 테스트가 실행될 때
   - **When** 단위, Repository/DB, API 통합, 프로필별 Swagger 테스트가 완료되면
   - **Then** 이메일 정규화, BCrypt cost/match, DB 제약, 정상 가입, 중복, 검증 실패, Role 주입 거부, 공통 오류, Swagger 정책이 검증된다.
   - **And** 기존 `GET /api/health`와 Docker healthcheck 계약은 유지된다.

## Tasks / Subtasks

- [x] **Task 1: 의존성과 환경 설정을 인증 기반에 맞게 고정한다** (AC: 1, 5, 6, 7)
  - [x] `springboot/build.gradle`에 Security, Validation, Flyway core, PostgreSQL Flyway 모듈, springdoc WebMVC UI를 추가한다.
  - [x] 테스트에 Spring Security Test와 Testcontainers PostgreSQL/JUnit Jupiter를 추가한다.
  - [x] Spring Boot dependency management가 관리하는 의존성은 버전을 직접 고정하지 않는다.
  - [x] Spring Boot `3.3.5`와 호환되는 `springdoc-openapi 2.6.0`을 사용한다. 최신 `2.8.x`를 무조건 적용하지 않는다.
  - [x] `application.yml`의 `ddl-auto` 기본값 `update`를 제거하고 `validate`로 고정한다.
  - [x] Flyway를 활성화하고 JPA가 Flyway 이후 스키마를 검증하도록 구성한다.
  - [x] 알 수 없는 JSON 필드를 무시하는 기본 동작을 그대로 두지 말고, 회원가입 DTO의 `role` 및 기타 unknown field가 `400 VALIDATION_FAILED`가 되도록 구성한다.
  - [x] `local`, `dev`, `test`에서만 springdoc API/UI를 활성화하고 기본/운영 설정에서는 둘 다 비활성화한다.

- [x] **Task 2: `users` 스키마와 JPA 매핑을 구현한다** (AC: 1, 2, 3, 7)
  - [x] `V1__create_users.sql`만 추가하고 `users` 테이블, identity PK, email unique/CHECK, role CHECK, UTC timestamp 기본값을 생성한다.
  - [x] `User`, `UserRole`, `UserRepository`를 아키텍처의 `user/domain` 경계에 추가한다.
  - [x] JPA 매핑이 Flyway 스키마와 정확히 일치하도록 하고 Hibernate 스키마 생성 기능에 의존하지 않는다.
  - [x] `existsByEmail` 사전 검사와 DB unique 위반 변환을 모두 구현하여 동시 가입 경합에서도 `409` 계약을 지킨다.

- [x] **Task 3: 공통 오류 기반을 구현한다** (AC: 3, 4, 7)
  - [x] `ErrorCode`, `ErrorResponse`, `GlobalExceptionHandler`를 `common/error`에 추가한다.
  - [x] `USER_EMAIL_CONFLICT`, `VALIDATION_FAILED`를 정확한 HTTP 상태와 매핑한다.
  - [x] validation field 오류, unreadable/unknown JSON field, DB unique 충돌을 안전한 공통 응답으로 변환한다.
  - [x] 서버가 생성한 요청별 `traceId`를 MDC와 오류 응답에 포함하고 요청 종료 시 반드시 정리한다. 신뢰되지 않은 클라이언트 값을 그대로 traceId로 사용하지 않는다.
  - [x] 비밀번호, 해시, 요청 body, 내부 예외 상세 및 stack trace를 응답이나 로그에 노출하지 않는다.
  - [x] 이후 Story 1.2/1.3의 EntryPoint/DeniedHandler가 같은 직렬화 계약을 재사용할 수 있게 구성한다.

- [x] **Task 4: 회원가입 유스케이스와 API를 구현한다** (AC: 2, 3, 4)
  - [x] `SignupRequest`, `SignupResponse`, `AuthController`, `AuthService`를 아키텍처 경계에 맞게 추가한다.
  - [x] 서비스 경계에서 이메일을 `trim()` + `toLowerCase(Locale.ROOT)`로 정규화한다.
  - [x] 비밀번호 정책을 12~64자 및 UTF-8 72바이트 이하로 검증한다.
  - [x] `BCryptPasswordEncoder(12)` Bean을 사용하고 Role을 서비스 내부에서 `USER`로 지정한다.
  - [x] `POST /api/auth/signup` 성공 시 `201 Created`를 반환하고 성공 DTO에 비밀번호 관련 필드를 만들지 않는다.
  - [x] Controller가 Repository에 직접 접근하지 않도록 한다.

- [x] **Task 5: 최소 SecurityFilterChain과 Swagger 문서를 구축한다** (AC: 5, 6, 7)
  - [x] `POST /api/auth/signup`, `GET /api/health`, 필요한 Actuator health, 활성 프로필의 Swagger 경로만 `permitAll` 처리한다.
  - [x] 이후 인증 스토리에서 확장 가능한 `SecurityConfig`를 추가하되 Story 1.1에서 JWT 필터, 로그인, 관리자 인가를 구현하지 않는다.
  - [x] 회원가입 operation과 DTO에 요청/응답/오류 계약을 문서화한다.
  - [x] Swagger/OpenAPI 활성 여부는 프로필/프로퍼티로 제어하고 Controller에서 환경을 분기하지 않는다.

- [x] **Task 6: Story 1.1 테스트를 구현한다** (AC: 1~7)
  - [x] 단위 테스트: 이메일 정규화, 비밀번호 길이/UTF-8 72바이트 경계, BCrypt match/non-match, Role 강제.
  - [x] PostgreSQL Testcontainers + Flyway 테스트: 적용 마이그레이션이 V1뿐인지, email unique/CHECK, role CHECK, JPA validate가 성공하는지 검증.
  - [x] API 통합 테스트: 정상 `201`, 응답 민감정보 비노출, 대소문자/공백 중복 `409`, invalid email/password `400`, `role`/unknown field `400`.
  - [x] 동시성 또는 DB unique 경합 경로가 `409 USER_EMAIL_CONFLICT`로 변환되는지 검증.
  - [x] 프로필 테스트: `local`/`dev`/`test`의 Swagger/API docs 접근 가능, 기본/운영 프로필의 비활성화.
  - [x] 회귀 테스트: `GET /api/health`가 계속 응답하고 DB 연결 상태를 보고한다.

### Review Findings

- [x] [Review][Patch] 이메일 검증 정규식이 연속 점 등 구조적으로 잘못된 이메일을 허용한다. [springboot/src/main/java/com/commitgotchi/auth/api/validation/ValidSignupEmailValidator.java:10]
- [x] [Review][Patch] lowercase 정규화 후 이메일 길이가 254자를 초과하면 `VALIDATION_FAILED`가 아니라 DB 오류/500이 발생할 수 있다. [springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java:34]
- [x] [Review][Patch] DTO의 `@Size`는 UTF-16 단위를 세지만 서비스는 code point를 세어 유효한 Unicode 비밀번호를 API에서 거부한다. [springboot/src/main/java/com/commitgotchi/auth/api/dto/SignupRequest.java:16]
- [x] [Review][Patch] DB unique 경합 판별이 예외 메시지 문자열에 의존해 드라이버 메시지 변화 시 `409` 계약이 깨질 수 있다. [springboot/src/main/java/com/commitgotchi/common/error/DatabaseConstraint.java:11]
- [x] [Review][Patch] 실제 PostgreSQL 동시 가입 경합이 `409 USER_EMAIL_CONFLICT`로 변환되는지 검증하는 테스트가 없다. [springboot/src/test/java/com/commitgotchi/auth/application/AuthServiceTest.java:81]
- [x] [Review][Patch] `prod`와 개발 프로필을 함께 활성화하면 프로필 순서에 따라 운영 Swagger가 노출될 수 있다. [springboot/src/main/resources/application.yml:41]
- [x] [Review][Patch] 지원하지 않는 HTTP method/content type이 catch-all 예외 처리로 405/415 대신 500을 반환한다. [springboot/src/main/java/com/commitgotchi/common/error/GlobalExceptionHandler.java:44]

## Dev Notes

### Developer Context

- 이 스토리는 Epic 1의 첫 구현이며 이전 스토리 구현 관례가 없다. 아키텍처 §12를 인증 증분 SSOT로 사용한다.
- 현재 Spring Boot 코드는 `CommitgotchiApplication`, DB 연결을 검사하는 `HealthController`, 초기 `application.yml`뿐이다. 새로운 인증 기반을 추가하되 기존 health endpoint와 컨테이너 healthcheck를 보존한다.
- 공통 오류와 최소 Security 설정은 이후 Story 1.2~1.5가 확장할 기반이다. Story 1.1 요구만 만족하도록 작게 만들되 일회성 회원가입 전용 구조로 만들지 않는다.
- DB는 Spring Boot가 단독 소유한다. FastAPI, Vue, AI 흐름, 다른 도메인 테이블은 수정하지 않는다.

### Technical Requirements

- **DB 스키마:** 정확히 아래 계약을 따른다.

```sql
CREATE TABLE users (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized
        CHECK (email = lower(btrim(email)) AND length(email) > 0),
    CONSTRAINT ck_users_role
        CHECK (role IN ('USER', 'ADMIN'))
);
```

- **가입 API:** `POST /api/auth/signup`; JSON은 camelCase, 시각은 UTC ISO-8601, 성공 응답은 wrapper 없이 DTO를 직접 반환한다.
- **입력 검증:** email은 유효한 형식과 최대 254자, password는 12~64자 및 UTF-8 72바이트 이하. `role`을 포함한 unknown field는 무시하지 않고 거부한다.
- **비밀번호:** `BCryptPasswordEncoder(12)`를 Bean으로 제공한다. 평문은 저장, 응답, 로그에 남기지 않는다.
- **중복 처리:** 정규화 후 사전 조회만으로 끝내지 않는다. unique constraint가 최종 진실이며 `DataIntegrityViolationException` 중 `uq_users_email` 위반만 `USER_EMAIL_CONFLICT`로 변환한다. 다른 무결성 오류를 이메일 충돌로 위장하지 않는다.
- **오류 형식:** `status`, `code`, `message`, `timestamp`, `traceId`. 내부 예외 메시지나 stack trace는 클라이언트에 반환하지 않는다.
- **Swagger:** 문서 예시는 안전한 placeholder만 사용한다. 운영/기본 프로필에서 `springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`가 기본값이어야 한다.

### Architecture Compliance

- 의존 방향은 `API -> application -> domain/repository`다.
- Repository를 Controller에 주입하거나 노출하지 않는다.
- 이메일 정규화, Role 강제, 비밀번호 해싱은 Controller가 아니라 application/service 경계에서 수행한다.
- DB 변경은 Flyway만 사용한다. `ddl-auto=create|update`와 테스트 전용 Hibernate 자동 생성도 금지한다.
- Story 1.1에서 `V2__create_refresh_tokens.sql`, JWT 관련 클래스, 로그인, 관리자 fixture, 다른 도메인 패키지를 미리 구현하지 않는다.
- 초기 ADMIN 자격 증명이나 사용자를 운영 Flyway에 넣지 않는다.

### Library / Framework Requirements

- 기존 기준선: Java 17, Spring Boot `3.3.5`, Gradle, PostgreSQL 16.
- 추가:
  - `spring-boot-starter-security`
  - `spring-boot-starter-validation`
  - `org.flywaydb:flyway-core`
  - `org.flywaydb:flyway-database-postgresql`
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0`
  - test: `spring-security-test`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`
- Spring Boot가 관리하는 Spring/Flyway/Testcontainers 의존성 버전을 개별 지정하지 않는다.
- JWT용 `spring-security-oauth2-jose`는 Story 1.2에서 추가한다. Story 1.1에 선행 추가하지 않는다.

### Current Files To Update

- `springboot/build.gradle`
  - 현재: Web, JPA, Actuator, PostgreSQL, 기본 test만 포함한다.
  - 변경: Story 1.1 의존성과 테스트 의존성을 추가한다.
  - 보존: Java 17, Spring Boot 3.3.5, 기존 Web/JPA/Actuator/PostgreSQL 구성.
- `springboot/src/main/resources/application.yml`
  - 현재: 환경변수 기반 datasource, `ddl-auto=${JPA_DDL_AUTO:update}`, `open-in-view=false`, actuator 설정.
  - 변경: 기본 `validate`, Flyway, unknown-field 처리, springdoc 기본 비활성화 및 프로필별 활성화.
  - 보존: 환경변수 datasource 계약, 포트 8080, `open-in-view=false`, management health/info.
- `springboot/src/main/java/com/commitgotchi/web/HealthController.java`
  - 현재: `/api/health`에서 PostgreSQL 연결을 확인하고 항상 service/status/db 형태를 반환한다.
  - 변경: 원칙적으로 없음. Security 설정에서 공개 접근을 보장하고 회귀 테스트만 추가한다.
  - 보존: URL, 응답 필드, DB 상태 확인 동작.
- `springboot/Dockerfile`, `docker-compose.yml`
  - 현재: 컨테이너 healthcheck가 `/api/health`에 의존하고 compose가 `JPA_DDL_AUTO`를 전달한다.
  - 변경: 필요한 경우 compose의 `JPA_DDL_AUTO` 전달을 제거하거나 `validate`만 허용한다.
  - 보존: `/api/health` 기반 Docker healthcheck와 datasource 환경변수.

### Expected New Files

```text
springboot/src/main/java/com/commitgotchi/
├── auth/
│   ├── api/AuthController.java
│   ├── api/dto/SignupRequest.java
│   ├── api/dto/SignupResponse.java
│   └── application/AuthService.java
├── user/domain/User.java
├── user/domain/UserRole.java
├── user/domain/UserRepository.java
├── security/SecurityConfig.java
├── common/error/
    ├── ErrorCode.java
    ├── ErrorResponse.java
    └── GlobalExceptionHandler.java
└── common/web/TraceIdFilter.java

springboot/src/main/resources/
├── db/migration/V1__create_users.sql
└── application-{local,dev,test}.yml 또는 동등한 profile group 설정

springboot/src/test/java/com/commitgotchi/
├── auth/
├── user/
├── common/error/
└── support/
```

파일명은 기존 코드와 실제 구현 선택에 따라 소폭 조정할 수 있지만 패키지 경계와 책임은 유지한다.

### Testing Requirements

- Repository/DB 제약 검증은 H2로 대체하지 않고 PostgreSQL Testcontainers를 사용한다.
- Flyway가 빈 PostgreSQL에 V1만 적용한 뒤 JPA `validate`가 성공해야 한다.
- DB CHECK 직접 위반 테스트를 포함해 애플리케이션 검증만 테스트하는 오류를 피한다.
- API 통합 테스트는 HTTP 상태뿐 아니라 오류 `code`, 공통 필드, 응답/로그 민감정보 비노출을 검증한다.
- unknown field가 기본 Jackson 동작으로 조용히 무시되지 않는지 반드시 검증한다.
- Swagger 비활성화는 UI 경로뿐 아니라 `/v3/api-docs`도 함께 검증한다.
- 기본/운영 프로필에서 문서가 비활성이고 `local`/`dev`/`test`에서 활성인지 각각 증명한다.

### Latest Technical Information

- 2026-06-11 기준 springdoc 공식 호환표에서 Spring Boot `3.3.x`는 springdoc `2.6.x` 계열과 호환된다. 현재 프로젝트의 Boot `3.3.5`를 유지하므로 호환선의 `2.6.0`을 사용하고 `2.8.x` 최신판으로 임의 업그레이드하지 않는다.
- springdoc는 `springdoc.api-docs.enabled`와 `springdoc.swagger-ui.enabled`로 API 문서와 UI를 각각 비활성화할 수 있다. 운영/기본 설정에서 둘 다 `false`로 두고 개발 프로필에서만 켠다.
- Flyway의 PostgreSQL 지원은 별도 `org.flywaydb:flyway-database-postgresql` 모듈이 필요하다.
- Spring Security는 adaptive one-way password 함수의 work factor를 실제 시스템에서 검증 시간에 맞게 조정하도록 권장한다. 프로젝트 결정은 cost 12이므로 이를 구현하고 테스트하되 운영 기준 장비에서 성능을 확인한다.

### Git Intelligence

- 최근 커밋은 기획/아키텍처 문서 정렬 작업이며 Spring Boot 구현 관례를 추가하지 않았다.
- 현재 구현 기준선은 최초 커밋의 얇은 Spring Boot 골격이다. 따라서 본 스토리의 패키지/테스트 패턴이 후속 인증 스토리의 기준이 된다.
- 작업 트리에 사용자 문서 변경이 존재하므로 구현 시 관련 없는 기획 산출물을 되돌리거나 덮어쓰지 않는다.

### Project Structure Notes

- 아키텍처의 이전 예시 경로 `backend-spring/`이 아니라 실제 저장소 경로 `springboot/`를 사용한다.
- 기존 `web/HealthController`를 인증 패키지로 이동하는 리팩터링은 범위 밖이다.
- Vue/FastAPI/AI 흐름은 Story 1.1과 무관하며 수정하지 않는다.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` - Epic 1 / Story 1.1]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md` - §12.1~12.12]
- [Source: `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md` - §4.1 FR-1, FR-28 및 인증 통합 승인 기준]
- [Source: `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/addendum.md` - 첫 번째 구현 증분 인증·인가 기술 제약]
- [Source: `springboot/build.gradle`]
- [Source: `springboot/src/main/resources/application.yml`]
- [Source: `springboot/src/main/java/com/commitgotchi/web/HealthController.java`]
- [springdoc official documentation and compatibility matrix](https://springdoc.org/)
- [Spring Security password storage guidance](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [Flyway PostgreSQL database support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)

## Dev Agent Record

### Agent Model Used

GPT-5.4

### Debug Log References

- 로컬 `gradle`/Wrapper가 없어 Docker Gradle 실행 경로를 사용함.
- 중첩 Docker의 Testcontainers 연결을 위해 `TESTCONTAINERS_RYUK_DISABLED=true`, `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`로 검증함.
- 최종 검증: `gradle clean build --no-daemon` 성공, 25 tests / 0 failures / 0 skipped.

### Implementation Plan

- 프로필/스키마 기준선을 먼저 고정하고 Flyway V1과 JPA validate를 연결함.
- 공통 오류/traceId 기반 위에 서비스 경계 정규화, BCrypt cost 12, DB unique 경합 변환을 구현함.
- 최소 SecurityFilterChain과 프로필 제한 OpenAPI를 추가하고 PostgreSQL Testcontainers 기반 전체 계약 테스트로 검증함.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created
- No previous story intelligence exists because this is the first story in Epic 1
- `sprint-status.yaml` was not present, so no sprint status entry was updated
- Flyway V1 `users` 스키마, JPA validate, 정규화 email/role DB 제약을 구현함.
- 회원가입 API가 정규화 이메일, BCrypt cost 12, 강제 `USER`, 안전한 성공/오류 응답을 제공함.
- 서버 생성 traceId, unknown field 거부, DB unique 경합 409 변환을 구현함.
- 기본/운영 Swagger 비활성화 및 `local`/`dev`/`test` 활성화를 구현함.
- Docker Gradle `clean build`와 PostgreSQL Testcontainers를 포함한 19개 테스트가 모두 통과함.

### File List

- `_bmad-output/implementation-artifacts/1-1-safe-signup-and-api-docs-foundation.md`
- `docker-compose.yml`
- `springboot/build.gradle`
- `springboot/src/main/java/com/commitgotchi/auth/api/AuthController.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/dto/SignupRequest.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/dto/SignupResponse.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/validation/ValidSignupEmail.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/validation/ValidSignupEmailValidator.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/validation/ValidSignupPassword.java`
- `springboot/src/main/java/com/commitgotchi/auth/api/validation/ValidSignupPasswordValidator.java`
- `springboot/src/main/java/com/commitgotchi/auth/application/AuthService.java`
- `springboot/src/main/java/com/commitgotchi/common/error/DatabaseConstraint.java`
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorResponse.java`
- `springboot/src/main/java/com/commitgotchi/common/error/GlobalExceptionHandler.java`
- `springboot/src/main/java/com/commitgotchi/common/error/InvalidSignupException.java`
- `springboot/src/main/java/com/commitgotchi/common/error/UserEmailConflictException.java`
- `springboot/src/main/java/com/commitgotchi/common/web/TraceIdFilter.java`
- `springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`
- `springboot/src/main/java/com/commitgotchi/user/domain/User.java`
- `springboot/src/main/java/com/commitgotchi/user/domain/UserRepository.java`
- `springboot/src/main/java/com/commitgotchi/user/domain/UserRole.java`
- `springboot/src/main/resources/application.yml`
- `springboot/src/main/resources/db/migration/V1__create_users.sql`
- `springboot/src/test/java/com/commitgotchi/auth/AuthApiIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/auth/application/AuthServiceTest.java`
- `springboot/src/test/java/com/commitgotchi/common/web/TraceIdFilterTest.java`
- `springboot/src/test/java/com/commitgotchi/common/error/DatabaseConstraintTest.java`
- `springboot/src/test/java/com/commitgotchi/security/PasswordEncoderTest.java`
- `springboot/src/test/java/com/commitgotchi/support/PostgresIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/DefaultSwaggerDisabledIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/DevSwaggerEnabledIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/LocalSwaggerEnabledIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/MixedProdDevSwaggerDisabledIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/ProdSwaggerDisabledIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/user/DatabaseMigrationIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/user/domain/UserTest.java`

## Change Log

- 2026-06-11: Story 1.1 안전한 회원가입, 공통 오류/traceId, Flyway users 기준선, 프로필 제한 Swagger 및 자동화 테스트 구현.
- 2026-06-12: 코드 리뷰 지적 7건 수정 및 실제 PostgreSQL 동시 가입 경합을 포함한 회귀 테스트 보강.
