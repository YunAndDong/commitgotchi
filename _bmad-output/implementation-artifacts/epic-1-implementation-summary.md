# Epic 1 구현 요약: 안전한 계정 접근과 권한 관리

작성일: 2026-06-12

구현 모듈: `springboot/`

상태: **done**

## 목적

사용자가 안전하게 가입하고 로그인해 보호 API를 사용할 수 있도록 인증·인가 기반을 구축했다. `USER`와 `ADMIN` 권한을 구분하고, Refresh Token Rotation과 멱등 로그아웃을 포함한 인증 수명주기를 완성했다.

## 완료 범위

| Story | 상태 | 구현 결과 | 완료 근거 |
|---|---|---|---|
| 1.1 안전한 회원가입과 API 문서 기반 구축 | done | 이메일 정규화, BCrypt cost 12, 강제 `USER`, 공통 오류/traceId, Flyway V1, 프로필 제한 Swagger | Story 최종 빌드 25 tests 통과 |
| 1.2 로그인과 JWT Access Token 보호 API | done | 로그인, HS256 Access Token, `/api/users/me`, JWT 오류 구분, Bearer OpenAPI | Story 최종 회귀 49 tests 통과 |
| 1.3 Role 기반 관리자 인가 | done | `/api/admin/ping`, `USER`/`ADMIN` 인가, `401`/`403` 구분, 테스트 전용 ADMIN fixture | Story 최종 회귀 55 tests 통과 |
| 1.4 Refresh Token 발급과 Rotation | done | Flyway V2, opaque Refresh Token 해시 저장, Rotation, 행 잠금, 재사용 탐지와 활성 토큰 전체 폐기 | 코드리뷰 반영 후 63 tests 통과 |
| 1.5 로그아웃과 인증 보안 마무리 | done | 멱등 로그아웃, exact-origin CORS, 민감정보 비노출, 인증 Epic 종단 테스트, README | 최종 회귀 78 tests 통과 |

## 제공 API

| Method | Path | 접근 정책 | 주요 결과 |
|---|---|---|---|
| `POST` | `/api/auth/signup` | 공개 | `USER` 계정 생성 |
| `POST` | `/api/auth/login` | 공개 | Access/Refresh Token Pair 발급 |
| `POST` | `/api/auth/refresh` | 공개 | Refresh Token Rotation |
| `POST` | `/api/auth/logout` | 공개 | 제출한 활성 Refresh Token 세션 멱등 종료 |
| `GET` | `/api/users/me` | `USER`, `ADMIN` | 현재 인증 사용자 조회 |
| `GET` | `/api/admin/ping` | `ADMIN` | 관리자 인가 검증 |

## 핵심 보안 계약

- Access Token은 HS256 JWT이며 유효기간은 15분이다.
- Refresh Token은 32바이트 CSPRNG opaque 값이며 유효기간은 30일이다.
- Refresh Token 원문은 반환 시점에만 사용하고 DB에는 SHA-256 lowercase hex 해시만 저장한다.
- Refresh 요청은 행 잠금 기반으로 직렬화하며, 폐기된 토큰 재사용을 탐지하면 해당 사용자의 활성 Refresh Token을 모두 폐기한다.
- 로그아웃은 제출한 활성 Refresh Token 세션 하나만 종료하며 반복 호출에도 빈 `204`를 반환한다.
- 공개 회원가입은 항상 `USER`만 생성하며 운영 ADMIN 자격 증명을 API나 Flyway에 포함하지 않는다.
- CORS는 환경변수 exact-origin allowlist를 사용하고 credentials/cookie CORS를 비활성화한다.
- 운영 및 기본 프로필에서는 Swagger UI와 `/v3/api-docs`를 비활성화한다.
- 응답과 기본 로그에는 비밀번호, 토큰 원문/hash, Authorization 헤더, Secret, 내부 예외 상세를 노출하지 않는다.

## 데이터베이스 변경

| Migration | 내용 |
|---|---|
| `V1__create_users.sql` | 정규화 이메일 unique/CHECK, `USER|ADMIN` Role CHECK를 포함한 `users` 생성 |
| `V2__create_refresh_tokens.sql` | 사용자 FK, unique token hash, 만료·폐기 상태와 인덱스를 포함한 `refresh_tokens` 생성 |

## 오류 계약

공통 오류 응답은 HTTP 상태와 애플리케이션 오류 코드를 함께 제공하며 서버 생성 `traceId`로 추적한다.

- 가입/입력: `USER_EMAIL_CONFLICT`, `VALIDATION_FAILED`
- 로그인: `AUTH_INVALID_CREDENTIALS`
- Access Token: `AUTH_ACCESS_TOKEN_MISSING`, `AUTH_ACCESS_TOKEN_INVALID`, `AUTH_ACCESS_TOKEN_EXPIRED`
- Refresh Token: `AUTH_REFRESH_TOKEN_INVALID`, `AUTH_REFRESH_TOKEN_REUSED`
- 인가: `AUTH_FORBIDDEN`
- HTTP/서버: `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_FOUND`, `INTERNAL_SERVER_ERROR`

## 검증 결과

- 2026-06-12 현재 작업 트리 전체 회귀 재검증: **78 tests / 0 failures / 0 errors**
- PostgreSQL 16 Testcontainers, 실제 SecurityFilterChain, Flyway 마이그레이션을 사용한다.
- 종단 시나리오: 회원가입 → 로그인 → `/api/users/me` → Rotation → 로그아웃 → 재발급 실패
- 추가 검증: 이메일 중복, 공개 ADMIN 생성 거부, JWT 변조/만료, Role 인가, 동시 Rotation, 재사용 탐지, CORS, Swagger 프로필, 민감정보 비노출

실행:

```bash
cd springboot
./gradlew clean test
```

## 남은 작업과 비범위

- 저위험 deferred: prod/default 프로필에서 `/api/admin/ping`의 Swagger 비노출을 직접 단언하는 전용 테스트
- 비범위: Access Token blacklist, 기기별 로그아웃/token-family 관리, Refresh Token HttpOnly cookie 전환, 운영 ADMIN 관리 API
- CI에서 Gradle 8.10.2/JDK 17 기준 `clean test`를 자동 실행하는 작업은 아직 남아 있다.

## 문서 연결

- 계획 기준: [Epic 및 Story 계획](../planning-artifacts/epics.md)
- 실행 안내: [프로젝트 README](../../README.md)
- 상세 구현 기록: [구현 산출물 인덱스](./README.md)
- 미룬 작업: [Deferred Work](./deferred-work.md)
