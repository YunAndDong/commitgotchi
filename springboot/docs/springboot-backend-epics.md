---
title: Commit-Gotchi Spring Boot Backend Epics
status: draft
scope: springboot-only
created: 2026-06-17
updated: 2026-06-18
inputDocuments:
  - ../../_bmad-output/planning-artifacts/epics.md
  - ../../_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md
  - ../../_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md
  - ../../vue/_planning/frontend-epic.md
  - ../../vue/src/api/client.js
  - ../../vue/src/stores/game.js
---

# Commit-Gotchi Spring Boot Backend Epics

## 목적

이 문서는 인증 에픽 이후 Spring Boot 백엔드에서 진행해야 할 미구현 영역을 에픽 단위로 정리한다. 마스터 BMad 산출물은 `_bmad-output/planning-artifacts/epics.md`에 남아 있지만, 이번 문서는 `springboot/` 하위 작업만 허용된 상황에서 Spring Boot 담당 구현 순서와 스토리 후보를 빠르게 잡기 위한 백엔드 전용 기준선이다.

## 현재 구현 상태

- 완료된 기준선: 회원가입, 로그인, JWT Access Token, Refresh Token Rotation, 로그아웃, Role 기반 인가, `/api/users/me`, `/api/admin/ping`, 공통 오류 응답, Swagger 프로필 제어, CORS 설정.
- 프로토타입 게임 API: `GameController`가 `/api/game/**` 엔드포인트를 제공하고 `GameService`가 프런트 상태에 가까운 compatibility JSON을 유지한다.
- BE-2.6 완료 기준선: 정규화된 `characters` 테이블, 캐릭터 생성/조회/수정/삭제/활성 지정, 이미지 fallback/retry, OpenAPI 계약, projection/persistence boundary 회귀가 Spring Boot 테스트로 고정되었다.
- BE-2.7 예정 기준선: BE-3 착수 전, 아직 `game_states` compatibility layer에 남아 있는 리포트/퀴즈/daily-report 성장 bridge가 정규화 캐릭터 SoR과 충돌하지 않도록 회귀 테스트와 문서 가드레일을 추가한다.
- 미구현으로 볼 영역: 학습 리포트 정규화, 일일 AI 리포트, 추천 퀴즈 정규화/채점, 대시보드/랭킹, 공유 게시판/리뷰 도메인.
- 테스트 공백: BE-2는 통합 회귀 게이트를 갖췄고, BE-3 이후 도메인은 인증/캐릭터 에픽 수준의 테스트 밀도를 새로 적용해야 한다.

## 계획 원칙

- `springboot/` 하위만 변경한다. Vue, FastAPI, `_bmad-output`은 참고만 한다.
- 기존 인증/인가 계약과 오류 응답 형식은 유지한다.
- 프런트가 이미 호출하는 `/api/game/**` 계약은 구현 중 깨지지 않게 유지하거나, 변경 시 별도 호환 계층과 명시적 마이그레이션 스토리를 둔다.
- `game_states` JSON 저장은 프로토타입 어댑터로 간주한다. 후속 구현의 System of Record는 Flyway로 관리되는 정규화 테이블과 도메인 서비스로 옮긴다.
- FastAPI 또는 외부 AI 의존성은 포트/어댑터 뒤에 격리하고, 로컬/테스트에서는 결정론적 Fake로 검증한다.
- 점수 반영, 활성 캐릭터 단일성, AI 콜백은 트랜잭션과 멱등성 테스트를 완료 기준에 포함한다.

## 구현 전 결정 필요 사항

1. 캐릭터 이미지 생성 방식: `architecture.md`를 SSOT로 잠그고, Spring Boot가 FastAPI `POST /api/ai/commitgotchi`를 동기 HTTP로 호출하는 흐름을 따른다.
2. 퀴즈 채점 방식: `architecture.md`의 비동기 요청 수락 + `POST /api/internal/quizzes/grade-result` 웹훅 계약을 따른다. FastAPI webhook의 `emotion`/`statusMessage`는 Spring Boot가 수락한다.
3. 능력치 키 이름: BE-2.6 기준 공개 API는 `algo`, `cs`, `db`, `net`, `fw`를 유지하고 DB 컬럼은 `stat_algorithm`, `stat_cs`, `stat_db`, `stat_network`, `stat_framework`로 매핑한다.
4. API 경로: BE-2 공개 계약은 `/api/game/**` facade로 고정되었다. `/api/characters` 같은 신규 경로는 별도 ADR/마이그레이션 스토리 없이는 정식 계약으로 소개하지 않는다.

## 에픽 목록

| Epic | 이름 | 주요 FR | 의존성 | 상태 |
| --- | --- | --- | --- | --- |
| BE-2 | 캐릭터 도메인과 활성 캐릭터 단일성 | FR-3, FR-4, FR-5, FR-6, FR-7, FR-16 일부 | 인증 에픽 | BE-2.7 review |
| BE-3 | 학습 리포트와 일일 AI 성장 루프 | FR-8, FR-9, FR-10, FR-11, FR-12, FR-13, FR-16, FR-21, FR-22, FR-23 | BE-2 | 대기 |
| BE-4 | 추천 퀴즈 제출, 채점, 피드백 반영 | FR-13, FR-14, FR-15, FR-16, FR-21, FR-23 | BE-2, BE-3 일부 | 대기 |
| BE-5 | 대시보드와 랭킹 조회 | FR-17, FR-18, FR-21, FR-23 | BE-2, BE-3, BE-4 | 대기 |
| BE-6 | 공유 게시판과 리뷰 | FR-19, FR-20 | BE-2 | 대기 |
| BE-7 | 프로토타입 게임 상태 제거와 운영 품질 강화 | NFR, 회귀 안정성 | BE-2~BE-6 | 대기 |

## BE-2: 캐릭터 도메인과 활성 캐릭터 단일성

사용자는 최대 3개의 캐릭터를 생성, 조회, 수정, 삭제하고 그중 하나를 활성 캐릭터로 지정할 수 있다. 첫 캐릭터는 자동 활성화되며, 능력치와 전투력은 사용자 직접 수정이 아니라 학습/퀴즈 결과로만 바뀐다. 이미지 생성 실패 시에도 Fallback 상태로 캐릭터 생성은 성공해야 한다.

### Story BE-2.1: 캐릭터 정규화 스키마와 도메인 모델 도입

As a 백엔드 개발자, I want 캐릭터와 능력치를 정규화 테이블로 관리하고, So that JSON blob이 아니라 트랜잭션 가능한 도메인 모델을 기준으로 후속 기능을 구현할 수 있다.

Acceptance Criteria:
- Flyway로 `characters`와 관련 능력치/이미지 상태 컬럼 또는 보조 테이블을 생성한다.
- 캐릭터는 `user_id`, `name`, `design_keyword`, `personality`, `emotion`, `status_message`, `is_evolved`, `image_status`, `sprite_sheet_url`, `sprite_meta`, `is_active`, 생성/수정 시각을 가진다.
- 능력치 5종과 전투력 합계 규칙을 표현하고, 직접 수정 가능 필드와 시스템 전용 필드를 분리한다.
- 같은 사용자에게 활성 캐릭터가 최대 1개만 존재하도록 DB 제약 또는 트랜잭션 잠금 전략을 둔다.
- Repository/도메인 단위 테스트와 PostgreSQL 통합 테스트가 포함된다.

### Story BE-2.2: 캐릭터 생성과 첫 활성화

As a 사용자, I want 이름, 키워드, 성격으로 캐릭터를 만들고, So that 학습을 시작할 분신을 가질 수 있다.

Acceptance Criteria:
- 캐릭터가 3개인 사용자의 추가 생성은 `400` 또는 명시적 도메인 오류로 거부된다.
- 첫 캐릭터는 자동 활성화되고 능력치 5종은 0으로 시작한다.
- 두 번째 이후 캐릭터 생성 시 새 캐릭터를 활성화할지 여부를 계약으로 고정하고, 활성 단일성이 유지된다.
- 입력값 검증 실패, 소유권 없는 접근, 인증 실패가 공통 오류 응답 형식을 따른다.
- 현재 Vue가 기대하는 `/api/game/characters`와 `/api/game/state` 응답을 깨지 않도록 projection 또는 호환 DTO를 제공한다.

### Story BE-2.3: 캐릭터 조회, 수정, 삭제

As a 사용자, I want 내 캐릭터 목록과 상세를 보고 편집/삭제하고, So that 나의 학습 분신을 관리할 수 있다.

Acceptance Criteria:
- 사용자는 자신의 캐릭터만 조회, 수정, 삭제할 수 있다.
- 이름, 키워드, 성격만 사용자 수정 가능하고 능력치, 전투력, 진화 상태는 직접 수정할 수 없다.
- 활성 캐릭터 삭제 시 남은 캐릭터가 있으면 재지정 정책을 따르고, 없으면 활성 없음 상태가 된다.
- 삭제와 활성 재지정은 하나의 트랜잭션으로 처리된다.
- 목록/상세/수정/삭제 API 통합 테스트를 포함한다.

### Story BE-2.4: 활성 캐릭터 지정

As a 사용자, I want 보유 캐릭터 중 하나를 활성 캐릭터로 지정하고, So that 이후 학습 점수가 올바른 대상에 반영되게 할 수 있다.

Acceptance Criteria:
- 새 활성 캐릭터 지정 시 기존 활성 캐릭터는 자동 해제된다.
- 다른 사용자의 캐릭터를 활성화할 수 없다.
- 동시 활성화 요청에서도 최종 상태에 활성 캐릭터가 최대 1개만 남는다.
- `/api/game/characters/{id}/active` 호환 엔드포인트와 도메인 서비스 테스트를 포함한다.

### Story BE-2.5: 이미지 생성 어댑터와 Fallback

As a 사용자, I want 이미지 생성 실패와 무관하게 캐릭터 생성이 완료되고, So that AI 의존성 장애 때문에 시작 흐름이 막히지 않게 할 수 있다.

Acceptance Criteria:
- 이미지 생성 방식에 대한 결정 사항을 반영해 `PENDING`, `READY`, `FAILED` 또는 `FALLBACK` 상태 전이를 고정한다.
- FastAPI 호출은 인터페이스 뒤에 격리하고 테스트에서는 Fake 어댑터로 검증한다.
- 실패 시 기본 이미지 또는 Fallback 상태가 저장되고 캐릭터 생성 자체는 성공한다.
- `/api/game/characters/{id}/retry-image` 호환 동작을 유지하거나 대체 계약을 문서화한다.

### Story BE-2.6: 캐릭터 에픽 계약 회귀와 BE-3 인계 안정화

As a 백엔드 개발자, I want BE-2 캐릭터 도메인의 공개 API, projection, 테스트 게이트, 인계 문서를 한 번 더 고정하고, So that BE-3 학습 리포트와 성장 루프가 안정적인 활성 캐릭터 기반 위에서 구현될 수 있다.

Acceptance Criteria:
- `/api/game/state`, `/api/game/characters`, `/api/game/characters/{id}` `GET`/`PATCH`/`DELETE`, `/api/game/characters/{id}/active`, `/api/game/characters/{id}/retry-image` 계약이 OpenAPI 또는 문서와 통합 테스트로 확인된다.
- BE-2.1~BE-2.5의 생성, 조회, 수정, 삭제, 활성 지정, 이미지 fallback/retry, 소유권, 인증 실패, 활성 단일성, `game_states` projection 경계가 하나의 회귀 게이트에서 깨지지 않는다.
- 캐릭터 System of Record가 `characters` 테이블이라는 점과 `game_states.state_json.characters`가 저장 시 비워지는 projection 전용 필드라는 점을 문서화하고 검증한다.
- BE-3에서 재사용할 활성 캐릭터 조회, 능력치 키 매핑(`algo/cs/db/net/fw`), 전투력/진화/감정 갱신 서비스 경계를 명확히 인계한다.
- 새 리포트/퀴즈/게시판 정규화 테이블, SQS, FastAPI, Vue 변경은 포함하지 않는다.

### Story BE-2.7: 프로토타입 성장 Bridge 회귀와 BE-3 착수 게이트

As a 백엔드 개발자, I want `GameService`에 남아 있는 리포트/퀴즈/daily-report compatibility bridge를 정규화 캐릭터 SoR 기준으로 회귀 고정하고, So that BE-3가 새 학습 리포트 스키마와 멱등 성장 루프를 만들 때 기존 `/api/game/**` 데모 흐름과 캐릭터 불변식이 깨지지 않게 할 수 있다.

Acceptance Criteria:
- `POST /api/game/reports`, `POST /api/game/quizzes/{id}/submit`, `POST /api/game/daily-report/deliver`가 능력치, 전투력, 감정, 상태 메시지를 `game_states.state_json.characters`가 아니라 `CharacterCommandService`와 `LearningCharacter` 도메인 메서드로만 변경한다는 회귀 테스트를 추가한다.
- 퀴즈 제출과 daily report delivery는 동일 quiz/report에 대해 점수를 한 번만 반영하고, 이미 `scored=true` 또는 `scoreApplied=true`인 compatibility JSON을 재호출해도 능력치가 다시 증가하지 않는다.
- 이미지/캐릭터 BE-2 계약처럼 `game_states.state_json.characters`는 모든 bridge mutation 후에도 저장본에서 빈 배열 `[]`로 유지되고, API 응답에서만 normalized projection이 overlay된다.
- 활성 캐릭터 변경 또는 삭제 후 stale `dailyReport.characterId`, pending reports, starter quizzes가 남아 있어도 missing character row에 대해 점수 반영 성공 표시를 저장하지 않는다.
- BE-3 dev agent가 재사용해야 할 locked active lookup, idempotency marker와 growth write atomicity, stat key mapping, Fallback behavior를 `springboot/docs/character-api-contract.md` 또는 동등한 Spring Boot 문서에 명시한다.
- 새 study log/report/quiz 정규화 테이블, SQS producer, FastAPI callback, Vue 변경, `/api/game/**` 경로 변경은 포함하지 않는다.

## BE-3: 학습 리포트와 일일 AI 성장 루프

사용자는 하루 학습 리포트를 저장하고, 시스템은 자정 배치 또는 결정된 처리 방식에 따라 AI 일일 리포트를 생성 요청한다. AI 결과는 멱등하게 수신되어 활성 캐릭터의 능력치, 전투력, 감정, 진화 상태에 반영된다.

### Story BE-3.1: 일일 학습 리포트 저장

Acceptance Criteria:
- 사용자, 캐릭터, 날짜 기준으로 학습 리포트를 저장/갱신한다.
- 활성 캐릭터가 없으면 리포트 저장이 거부되거나 명시적인 빈 상태 응답을 반환한다.
- 제목, 본문, 태그, 기분 입력값을 검증한다.
- 동일 날짜 중복 저장 정책을 명확히 하고 테스트한다.

### Story BE-3.2: 일일 리포트 생성 요청 적재

Acceptance Criteria:
- 대상 날짜, 사용자, 활성 캐릭터, 학습 리포트로 멱등 `requestId`를 생성한다.
- 로컬/테스트에서는 SQS 없이 Outbox 또는 Fake producer로 검증 가능해야 한다.
- 이미 요청한 리포트는 중복 적재하지 않는다.
- 실패한 적재를 재시도할 수 있는 상태를 기록한다.

### Story BE-3.3: AI 일일 리포트 결과 수신

Acceptance Criteria:
- FastAPI 콜백 또는 Internal API 요청을 서버 간 인증으로 보호한다.
- `requestId` 기준으로 중복 콜백을 흡수한다.
- 성공 결과는 리포트 본문, 피드백, 다음 학습 추천, 추천 퀴즈, 점수 변화량을 저장한다.
- 실패 결과는 Fallback 상태와 사용자 노출 가능한 메시지로 저장한다.

### Story BE-3.4: 점수 변화량, 전투력, 진화, 감정 반영

Acceptance Criteria:
- 점수 변화량은 활성 캐릭터에 일 단위로 한 번만 반영된다.
- 전투력은 항상 능력치 5종 합과 일치한다.
- 전투력 1,000점 이상 도달 시 캐릭터당 최대 한 번만 진화한다.
- 감정과 상태 메시지는 AI 결과 또는 Fallback 규칙으로 갱신된다.
- 중복 콜백, 동시 콜백, 실패 콜백에 대한 통합 테스트를 포함한다.

### Story BE-3.5: 일일 리포트 조회와 `/api/game/state` projection

Acceptance Criteria:
- 사용자는 대기, 실패, 완료 상태를 조회할 수 있다.
- 오전 9시 제공이라는 제품 문구와 실제 API 상태 모델을 정합화한다.
- Vue가 기대하는 `dailyReport`, `reports`, `quizzes` projection을 제공한다.

## BE-4: 추천 퀴즈 제출, 채점, 피드백 반영

사용자는 추천 퀴즈를 조회하고 답안을 제출한다. 시스템은 제출을 저장하고 결정된 AI 채점 방식으로 피드백과 점수 변화량을 받아 활성 캐릭터에 중복 없이 반영한다.

### Story BE-4.1: 추천 퀴즈 저장과 조회

Acceptance Criteria:
- 일일 리포트 결과 또는 문제 은행에서 추천 퀴즈를 저장한다.
- 퀴즈는 질문, 선택지 또는 서술형 입력 모델, 모범답안, 점수 배분, 출처를 가진다.
- 사용자는 자신의 추천 퀴즈만 조회할 수 있다.

### Story BE-4.2: 퀴즈 답안 제출

Acceptance Criteria:
- 제출 답안을 저장하고 `SUBMITTED`, `GRADING`, `GRADED`, `FAILED` 상태를 관리한다.
- 이미 채점 완료된 제출은 중복 채점되지 않는다.
- 객관식/서술형 지원 범위를 스토리에서 고정한다.

### Story BE-4.3: AI 채점 요청과 결과 수신

Acceptance Criteria:
- 동기 채점 또는 비동기 웹훅 결정을 반영해 어댑터를 구현한다.
- 결과 수신은 `submissionId` 기준으로 멱등 처리한다.
- 피드백, 정오답, 점수 변화량, 실패 사유를 저장한다.
- AI 장애 시 사용자 제출은 사라지지 않고 Fallback 상태로 조회된다.

### Story BE-4.4: 퀴즈 점수 성장 반영

Acceptance Criteria:
- 채점 점수 변화량은 제출 단위로 한 번만 활성 캐릭터에 반영된다.
- 일일 리포트 점수와 퀴즈 점수가 이중계상되지 않는다.
- 전투력, 진화, 감정, 상태 메시지 규칙은 BE-3의 성장 규칙과 동일한 도메인 서비스를 사용한다.

## BE-5: 대시보드와 랭킹 조회

사용자는 대시보드에서 활성 캐릭터, 능력치, 전투력, 최근 학습 현황, 감정 메시지를 확인하고 전투력 기준 랭킹에서 자신의 위치를 확인할 수 있다.

### Story BE-5.1: 대시보드 조회 API

Acceptance Criteria:
- 활성 캐릭터, 능력치, 전투력, 감정, 상태 메시지, 최근 리포트, 오늘 퀴즈 상태를 한 번에 조회한다.
- 활성 캐릭터가 없는 사용자는 명확한 빈 상태를 받는다.
- `/api/game/state` projection과 중복되는 데이터는 동일한 읽기 모델에서 나온다.

### Story BE-5.2: 주간 학습 현황

Acceptance Criteria:
- 최근 7일 학습 여부와 점수 변화량을 계산한다.
- 날짜 경계는 `Asia/Seoul` 기준으로 고정한다.
- 리포트 실패/대기 상태가 주간 통계에 어떻게 반영되는지 명시한다.

### Story BE-5.3: 전투력 랭킹

Acceptance Criteria:
- 활성 캐릭터 전투력 기준 랭킹을 조회한다.
- 내 순위와 상위 목록을 함께 제공한다.
- 동점 정렬 기준과 페이지네이션 정책을 고정한다.
- 필요한 인덱스와 통합 테스트를 포함한다.

## BE-6: 공유 게시판과 리뷰

사용자는 자신의 캐릭터를 공유 게시판에 올리고 다른 사용자의 캐릭터를 조회하며 리뷰를 작성할 수 있다. 게시글과 리뷰 수정/삭제는 소유자에게만 허용된다.

### Story BE-6.1: 공유 게시글 생성과 조회

Acceptance Criteria:
- 사용자는 자신의 캐릭터를 선택해 공유 게시글을 생성할 수 있다.
- 게시글은 캐릭터 스냅샷과 현재 캐릭터 참조 중 어떤 모델을 쓸지 결정하고 일관되게 저장한다.
- 목록/상세 조회는 인증 사용자에게 제공하고, 공개 범위는 스토리에서 고정한다.

### Story BE-6.2: 공유 게시글 수정과 삭제

Acceptance Criteria:
- 게시글 작성자만 설명 수정과 삭제를 할 수 있다.
- 삭제 후 리뷰 처리 정책을 DB 제약과 함께 고정한다.
- 권한 부족은 `403`, 없는 리소스는 일관된 오류로 반환한다.

### Story BE-6.3: 리뷰 작성, 수정, 삭제

Acceptance Criteria:
- 사용자는 공유 게시글에 별점과 텍스트 리뷰를 작성할 수 있다.
- 리뷰 작성자만 자신의 리뷰를 수정/삭제할 수 있다.
- 평균 평점은 일관되게 계산되고 동시 요청에서도 깨지지 않는다.
- 자기 게시글 리뷰 허용 여부를 스토리에서 결정한다.

## BE-7: 프로토타입 게임 상태 제거와 운영 품질 강화

후속 도메인 구현이 완료되면 `game_states.state_json` 기반 프로토타입 저장소를 제거하거나 읽기 호환 projection 전용으로 축소한다. 모든 게임 기능은 정규화 도메인과 테스트 가능한 서비스로 이동한다.

### Story BE-7.1: `game_states` 의존성 제거 계획과 마이그레이션

Acceptance Criteria:
- 어떤 데이터가 정규화 테이블로 이동되었는지 매핑표를 작성한다.
- 기존 개발 데이터 보존이 필요하면 일회성 마이그레이션을 제공한다.
- 신규 요청 처리는 더 이상 `state_json`을 System of Record로 사용하지 않는다.

### Story BE-7.2: OpenAPI와 프런트 계약 회귀 테스트

Acceptance Criteria:
- `/api/game/**` 또는 신규 도메인 API의 OpenAPI 문서가 인증 에픽 수준으로 정리된다.
- Vue `client.js`가 호출하는 경로별 성공/오류 계약을 Spring Boot 통합 테스트로 보호한다.
- 실제 JWT/Refresh Token/Secret이 문서 예시에 포함되지 않는다.

### Story BE-7.3: 관측성과 운영 안전장치

Acceptance Criteria:
- AI 요청/콜백, 점수 반영, 활성 캐릭터 변경, 게시글/리뷰 변경에 traceId가 연결된다.
- 민감정보는 로그에 남기지 않는다.
- 반복 실패, 중복 콜백, 비정상 상태를 운영자가 식별할 수 있는 최소 로그/메트릭 지점을 둔다.

## 권장 진행 순서

1. BE-2.6 review findings를 모두 해결하고 캐릭터 계약 기준선을 닫는다.
2. BE-2.7로 prototype growth bridge와 BE-3 착수 게이트를 회귀 고정한다.
3. BE-2가 끝나면 `bmad-retrospective`로 학습을 정리한다.
4. BE-3 착수 전에는 리포트 저장/AI 결과 수신/성장 반영의 idempotency와 트랜잭션 경계를 스토리에서 확정한다.
5. BE-4 착수 전에는 퀴즈 채점 방식 충돌을 `bmad-correct-course` 또는 아키텍처 ADR로 정리한다.
6. BE-7은 기능 에픽 완료 후 진행한다. 단, `/api/game/state` 호환을 깨는 변경이 필요하면 해당 시점에 BE-7 일부를 앞으로 당긴다.

## Definition of Done

- Flyway 마이그레이션과 JPA 매핑 검증이 통과한다.
- 도메인 규칙은 단위 테스트로, API 계약은 MockMvc 통합 테스트로 검증한다.
- 보안 경계는 인증 사용자, 타 사용자 리소스, 관리자 권한 여부를 테스트한다.
- 멱등성, 동시성, Fallback은 해당되는 모든 AI/성장 스토리에 포함한다.
- Swagger/OpenAPI는 로컬/개발/테스트 프로필에서 검증 가능하고 운영 프로필에서는 비활성 상태를 유지한다.
- `game_states` JSON 저장소에 새 핵심 도메인 규칙을 추가하지 않는다.
