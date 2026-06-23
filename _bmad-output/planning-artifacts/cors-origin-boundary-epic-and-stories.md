---
title: CORS and Origin Boundary Epic
status: draft
created: 2026-06-23
updated: 2026-06-23
baseline_commit: 46d644548d6362edf7b4e971635fb8e6c40b98c9
scope: springboot-vue-extension-deployment
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md
  - _bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/addendum.md
  - _bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/reconcile-auth-update.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md
  - _bmad-output/planning-artifacts/ux-designs/ux-commitgotchi-2026-06-07/EXPERIENCE.md
  - _bmad-output/planning-artifacts/ux-designs/ux-commitgotchi-2026-06-07/DESIGN.md
  - README.md
  - springboot/docs/cors-chrome-extension-allowlist.md
  - springboot/docs/troubleshooting-login-network-error.md
  - vue/EXTENSION.md
  - springboot/src/main/java/com/commitgotchi/security/CommitgotchiCorsConfiguration.java
  - springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java
  - springboot/src/main/resources/application.yml
  - springboot/src/test/java/com/commitgotchi/security/CorsConfigurationIntegrationTest.java
  - springboot/src/test/java/com/commitgotchi/security/CommitgotchiCorsConfigurationTest.java
  - vue/src/api/client.js
  - vue/vite.config.js
  - vue/nginx.conf
  - vue/public/manifest.json
  - docker-compose.yml
  - .env.example
  - vue/.env.example
  - fastapi/app/main.py
  - fastapi/app/config.py
confirmedDecisions:
  - Vue does not directly call FastAPI from the browser.
  - Browser-facing API traffic goes through Spring Boot.
---

# CORS and Origin Boundary Epic

## Purpose

이 문서는 현재 `springboot`와 `vue` 사이의 CORS 상태를 기준으로, 앞으로 배포와 Chrome extension까지 포함해 어떤 구현을 해야 하고 어떤 결정을 내려야 하는지 정리한다.

핵심 결론은 명확하다. 브라우저 클라이언트는 Spring Boot Public API를 호출한다. Vue가 FastAPI를 직접 호출하는 경로는 만들지 않는다. FastAPI는 Spring Boot와 서버 간 통신만 수행하므로 현재는 FastAPI CORS가 필요 없다.

## Current State

### Spring Boot CORS

- CORS 정책의 source of truth는 `CommitgotchiCorsConfiguration`이다.
- Spring Security는 `SecurityConfig`에서 이 `CorsConfigurationSource`를 사용한다.
- CORS 적용 경로는 `/api/**`뿐이다.
- 허용 origin은 `CORS_ALLOWED_ORIGINS` exact origin 목록과 고정 Chrome extension origin을 합친 값이다.
- 고정 Chrome extension origin은 `chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`이다.
- 허용 method는 `GET`, `POST`, `PATCH`, `DELETE`, `OPTIONS`다.
- 허용 header는 `Authorization`, `Content-Type`이다.
- `Access-Control-Allow-Credentials: true`가 반환된다.
- 운영 `prod` 프로필은 최소 하나 이상의 정확한 HTTPS origin을 요구하며 wildcard, origin pattern, path/query/fragment가 포함된 값을 거부한다.

### Vue API Access

- Vue는 dev proxy를 쓰지 않는다.
- `vue/src/api/client.js`는 `VITE_API_BASE_URL`을 prefix로 사용해 Spring Boot `/api/**`를 직접 호출한다.
- 현재 로컬 env 기준:
  - `VITE_API_BASE_URL=http://localhost:8080`
  - `CORS_ALLOWED_ORIGINS=http://localhost:5173`
- Vue API client는 cookie refresh 흐름 때문에 `credentials: 'include'`를 사용한다.
- Vue API client는 `Authorization` header를 사용하고, `PATCH`/`DELETE` 및 SSE `text/event-stream` GET도 사용한다.

### FastAPI Boundary

- Vue가 FastAPI를 직접 호출하지 않는 것으로 확정한다.
- FastAPI는 현재 `CORSMiddleware`를 갖고 있지 않다.
- FastAPI가 외부에서 받는 HTTP endpoint는 현재 내부 성격의 `POST /api/internal/quizzes/grade`와 health endpoint다.
- Docker Compose에서 FastAPI는 `SPRING_BOOT_INTERNAL_BASE_URL=http://springboot:8080`으로 Spring Boot를 호출한다.
- 따라서 브라우저 CORS 범위에는 FastAPI를 넣지 않는다.

### Static Assets

- `/character-assets/**`는 Spring Security에서 `permitAll`이지만 CORS 적용 대상인 `/api/**`에는 포함되지 않는다.
- Vue는 character sprite URL을 `<img>` probe와 CSS `background-image`로 사용한다.
- 단순 이미지 표시만 한다면 별도 CORS가 없어도 동작 가능하다.
- 향후 canvas pixel read, `fetch()`로 이미지 binary 처리, CDN 전환이 생기면 asset CORS 정책을 별도로 결정해야 한다.

### Documentation Alignment

- COR-1.1 이전 루트 `README.md`의 CORS 설명은 현재 코드와 어긋나 있었다.
- COR-1.1에서 README를 `GET`, `POST`, `PATCH`, `DELETE`, `OPTIONS`, `Authorization`, `Content-Type`,
  credentials 허용 기준으로 정렬했다.
- 상세 CORS source of truth는 `CommitgotchiCorsConfiguration`이며,
  `springboot/docs/cors-chrome-extension-allowlist.md`와 troubleshooting 문서가 같은 계약을 설명한다.

## Requirements Inventory

### Functional Requirements

COR-FR1: Vue web client는 설정된 exact origin에서 Spring Boot `/api/**`를 호출할 수 있어야 한다.

COR-FR2: Chrome extension popup은 고정 extension origin으로 Spring Boot `/api/**`를 호출할 수 있어야 한다.

COR-FR3: Spring Boot CORS 허용 method/header는 실제 Vue API client가 사용하는 `GET`, `POST`, `PATCH`, `DELETE`, `OPTIONS`, `Authorization`, `Content-Type`와 일치해야 한다.

COR-FR4: Vue browser runtime은 FastAPI를 직접 호출하지 않아야 한다. AI 관련 요청은 Spring Boot가 서버 간 통신으로 중계하거나 콜백을 수신한다.

COR-FR5: 운영 배포는 same-origin reverse proxy 방식 또는 cross-origin API 방식 중 하나를 명확히 선택하고, `VITE_API_BASE_URL`과 `CORS_ALLOWED_ORIGINS`를 그 선택에 맞춰 관리해야 한다.

COR-FR6: Chrome extension 운영 배포 시 `VITE_API_BASE_URL`, `manifest.json`의 `host_permissions`, Spring Boot CORS allowlist가 같은 API origin을 가리켜야 한다.

COR-FR7: `/character-assets/**`의 browser access 정책은 현재 이미지 표시 용도와 향후 fetch/canvas/CDN 사용 가능성을 구분해 명시해야 한다.

COR-FR8: CORS 관련 문서, env template, troubleshooting 문서는 현재 코드와 같은 계약을 설명해야 한다.

### Non-Functional Requirements

COR-NFR1: 운영 CORS는 exact HTTPS origin만 허용하고 wildcard 또는 pattern을 허용하지 않는다.

COR-NFR2: credentials가 활성화되어 있으므로 `Access-Control-Allow-Origin: *`는 절대 사용하지 않는다.

COR-NFR3: CORS preflight 실패는 인증/비밀번호 문제가 아니라 origin, method, header allowlist 문제로 진단 가능해야 한다.

COR-NFR4: CORS allowlist가 안전하지 않은 경우 Spring Boot는 fail-fast로 시작하지 않아야 한다.

COR-NFR5: FastAPI CORS는 브라우저 직접 접근이 생기기 전까지 추가하지 않는다. 추가 시 별도 decision record와 테스트가 필요하다.

COR-NFR6: CORS 회귀 테스트는 로컬 Vue, 운영 HTTPS origin 예시, Chrome extension origin, 거부 origin, mutating method preflight를 포함해야 한다.

## Decisions

### COR-D1: Spring Boot Owns Browser-Facing CORS

Spring Boot가 브라우저-facing Public API의 CORS를 소유한다. Vue는 프록시 없이 `VITE_API_BASE_URL`로 Spring Boot를 호출할 수 있고, 운영 배포 방식에 따라 same-origin 또는 cross-origin 중 하나로 정리한다.

### COR-D2: Vue Does Not Call FastAPI Directly

Vue는 FastAPI를 직접 호출하지 않는다. FastAPI는 Spring Boot와 서버 간 계약으로만 연결한다. 따라서 현재 FastAPI에 browser CORS를 추가하지 않는다.

### COR-D3: Chrome Extension Origin Is a First-Class Client

Chrome extension popup은 일반 웹 origin과 다른 `chrome-extension://...` origin을 갖는다. 이 origin은 manifest `key`로 고정되어 있으므로 Spring Boot CORS에 명시적으로 신뢰 origin으로 남긴다.

### COR-D4: CORS Scope Remains `/api/**`

현재 CORS 범위는 `/api/**`로 유지한다. `/character-assets/**`는 지금 당장 CORS에 포함하지 않되, asset 처리 방식이 바뀌면 별도 story에서 결정한다.

## Open Decisions

1. 운영 배포 origin 모델
   - Option A: `https://app.example.com` 하나에서 Vue static과 `/api/**` reverse proxy를 모두 제공한다. 이 경우 브라우저 API 요청은 same-origin이 되고 CORS 의존도가 줄어든다.
   - Option B: `https://app.example.com`에서 Vue를 제공하고 `https://api.example.com` 또는 별도 API origin으로 Spring Boot를 제공한다. 이 경우 `CORS_ALLOWED_ORIGINS=https://app.example.com`이 필수다.

2. 운영 public Nginx 책임 범위
   - repo의 `vue/nginx.conf`는 SPA static server일 뿐 public TLS/reverse proxy가 아니다.
   - 서버 인스턴스의 public Nginx 설정을 repo에 문서화할지, 별도 운영 runbook으로 둘지 결정해야 한다.

3. Chrome extension 운영 API 접근
   - 현재 `vue/public/manifest.json`의 `host_permissions`는 `http://localhost:8080/*`만 허용한다.
   - 운영 extension에서 실제 API를 호출하려면 운영 API host permission과 build-time `VITE_API_BASE_URL` 전략을 정해야 한다.

4. Asset serving contract
   - `/character-assets/**`를 Spring Boot에서 계속 제공할지, Vue static asset/CDN/S3 URL로 이동할지 결정해야 한다.
   - canvas/fetch가 필요해지면 asset CORS header가 필요할 수 있다.

## Implementation Work

- README의 CORS 설명을 실제 코드에 맞게 수정한다.
- 운영 origin 모델을 선택하고 `.env.example`, `vue/.env.example`, Docker Compose 설명을 정렬한다.
- public Nginx/reverse proxy runbook을 추가한다.
- Chrome extension 운영 API host permission과 build strategy를 문서화하고 필요 시 manifest를 조정한다.
- `/character-assets/**`의 CORS 필요 여부를 결정하고 문서화한다.
- CORS regression matrix를 문서화하고, 가능하면 Spring Boot 통합 테스트 또는 smoke script로 고정한다.
- FastAPI에는 browser-facing endpoint가 생기기 전까지 CORS를 추가하지 않는다는 guardrail을 남긴다.

## Epic List

| Epic | Name | Goal | Status |
| --- | --- | --- | --- |
| COR-1 | Browser Origin Boundary and Deployment CORS Hardening | Vue, Spring Boot, Chrome extension, FastAPI 내부 경계를 명확히 고정하고 운영 배포 CORS 실패를 예방한다. | draft |

## Epic COR-1: Browser Origin Boundary and Deployment CORS Hardening

사용자는 Vue 웹 또는 Chrome extension에서 인증과 게임 기능을 안정적으로 사용할 수 있어야 한다. 개발자와 운영자는 어떤 origin이 Spring Boot API를 호출할 수 있는지, FastAPI가 왜 CORS 대상이 아닌지, 운영 배포에서 어떤 값을 맞춰야 하는지 혼동하지 않아야 한다.

### Story COR-1.1: CORS Source of Truth Documentation Alignment

As a 개발자,
I want CORS 관련 README와 troubleshooting 문서가 실제 Spring Boot 설정과 일치하기를 원한다,
so that CORS 장애를 오래된 문서 때문에 잘못 진단하지 않는다.

**Acceptance Criteria**

1. 루트 README의 CORS 설명이 실제 코드와 일치한다.
2. `/api/**`, 허용 origin, method, header, credentials 정책이 한 곳에 정확히 정리된다.
3. Chrome extension origin 자동 추가 정책이 문서화된다.
4. Vue가 FastAPI를 직접 호출하지 않는다는 결정이 문서화된다.
5. `springboot/docs/troubleshooting-login-network-error.md`가 현재 method allowlist와 credentials 정책을 기준으로 유지된다.

**Tasks**

- [x] README CORS 문구를 `GET,POST,PATCH,DELETE,OPTIONS`, `Authorization, Content-Type`, `credentials=true` 기준으로 수정한다.
- [x] `springboot/docs/cors-chrome-extension-allowlist.md`에 Vue-FastAPI 직접 접근 금지 결정을 추가한다.
- [x] troubleshooting 문서의 진단 순서에 운영 origin 모델 확인을 추가한다.
- [x] 문서에 FastAPI CORS 미설정이 의도된 상태임을 명시한다.

### Review Findings

- [x] [Review][Patch] Planning artifact still describes README CORS docs as drifted after COR-1.1 alignment [_bmad-output/planning-artifacts/cors-origin-boundary-epic-and-stories.md:84]
- [x] [Review][Patch] Documentation source-of-truth remains an open decision after this story already chose and updated README [_bmad-output/planning-artifacts/cors-origin-boundary-epic-and-stories.md:160]

**Status**

done

**Dev Agent Record**

Debug Log:
- 2026-06-23: `CommitgotchiCorsConfiguration`, CORS 통합 테스트, FastAPI entrypoint를 확인해 현재 런타임 계약을 문서 기준으로 삼았다.
- 2026-06-23: sprint-status 파일이 없어 스토리 문서 자체의 체크박스와 기록만 갱신했다.
- 2026-06-23: `./gradlew test`, `npm test`, `git diff --check`, FastAPI `CORSMiddleware` 검색으로 문서 정렬과 회귀 상태를 검증했다.

Completion Notes:
- README의 CORS 설명을 `/api/**`, exact origin allowlist + 고정 Chrome extension origin, `GET/POST/PATCH/DELETE/OPTIONS`, `Authorization/Content-Type`, `credentials=true` 기준으로 정렬했다.
- Chrome extension allowlist 문서에 Vue가 FastAPI를 직접 호출하지 않는 결정과 FastAPI browser CORS 미설정 guardrail을 추가했다.
- troubleshooting 문서에 운영 same-origin/cross-origin 모델 확인 순서와 FastAPI를 CORS 대상으로 보지 않는 진단 기준을 추가했다.
- 런타임 코드 변경은 하지 않았다.

File List:
- README.md
- springboot/docs/cors-chrome-extension-allowlist.md
- springboot/docs/troubleshooting-login-network-error.md
- _bmad-output/planning-artifacts/cors-origin-boundary-epic-and-stories.md

Change Log:
- 2026-06-23: COR-1.1 문서 정렬 완료. Spring Boot CORS source of truth와 Vue/FastAPI origin boundary를 문서화했다.

### Story COR-1.2: Production Origin Model Decision and Environment Matrix

As a 배포 담당자,
I want 운영 origin 모델과 env 조합을 선택하고 표준화하기를 원한다,
so that Vue build URL과 Spring Boot CORS allowlist가 서로 어긋나지 않는다.

**Acceptance Criteria**

1. 운영 배포가 same-origin reverse proxy인지 cross-origin API인지 결정되어 문서화된다.
2. 선택한 모델에 따른 `VITE_API_BASE_URL`, `CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE`, cookie secure 설정이 표로 정리된다.
3. local Docker Compose, local Vite dev, production web, production extension 각각의 expected origin이 정리된다.
4. 잘못된 조합의 증상과 수정 방법이 troubleshooting에 연결된다.

**Tasks**

- [x] Option A same-origin reverse proxy와 Option B cross-origin API의 장단점을 비교한다.
- [x] 운영 기본안을 선택한다.
- [x] `.env.example`과 `vue/.env.example` 주석을 선택한 운영 모델 기준으로 보강한다.
- [x] public Nginx/reverse proxy runbook 초안을 작성한다.

**Status**

review

**Dev Agent Record**

Implementation Plan:
- 운영 기본안은 Option A same-origin reverse proxy로 선택하고, Option B cross-origin API는 별도 API 도메인과 CORS 운영 필요성이 생길 때 후속 decision record로 전환한다.
- Production web은 빈 `VITE_API_BASE_URL`로 `/api/**`를 같은 origin에 호출하고, production extension은 `https://app.example.com` 절대 URL을 사용하도록 문서화한다.
- 런타임 Java/FastAPI 코드는 변경하지 않고 env template, README, troubleshooting, CORS 문서, public Nginx runbook만 보강한다.

Debug Log:
- 2026-06-23: `application.yml`에서 `commitgotchi.auth.refresh-cookie-secure`와 `prod` profile의 secure cookie override를 확인했다.
- 2026-06-23: `vue/src/api/client.js`, `vue/nginx.conf`, `docker-compose.yml`을 확인해 Vue가 Spring Boot `/api/**`를 호출하고 public Nginx는 repo에 없는 서버 레이어임을 기준으로 삼았다.
- 2026-06-23: `git diff --check`, `npm test`, `./gradlew test`, FastAPI `CORSMiddleware` 검색으로 문서/env 변경과 guardrail을 검증했다.

Completion Notes:
- 운영 origin 모델은 Option A same-origin reverse proxy로 확정했다.
- local Docker Compose, local Vite dev, production web, production extension의 expected origin/env 조합을 README와 public Nginx runbook 표로 정리했다.
- `.env.example`과 `vue/.env.example`에 production web과 production extension의 `VITE_API_BASE_URL` 차이를 명시했다.
- troubleshooting 문서에 잘못된 env 조합의 증상과 수정 방법을 추가했다.
- public Nginx/reverse proxy runbook 초안을 추가했다.
- FastAPI browser CORS는 추가하지 않았다.

File List:
- .env.example
- README.md
- vue/.env.example
- springboot/docs/cors-chrome-extension-allowlist.md
- springboot/docs/troubleshooting-login-network-error.md
- springboot/docs/public-nginx-reverse-proxy-runbook.md
- _bmad-output/planning-artifacts/cors-origin-boundary-epic-and-stories.md

Change Log:
- 2026-06-23: COR-1.2 운영 origin 모델을 same-origin reverse proxy로 확정하고 env matrix, troubleshooting, public Nginx runbook을 문서화했다.

### Story COR-1.3: Chrome Extension Production API Access Contract

As a Chrome extension 사용자,
I want extension popup에서도 운영 Spring Boot API를 호출할 수 있기를 원한다,
so that 웹이 아닌 extension 환경에서도 실제 로그인과 게임 상태를 사용할 수 있다.

**Acceptance Criteria**

1. extension origin은 `chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`으로 유지된다.
2. 운영 API를 호출할 경우 `manifest.json`의 `host_permissions`가 운영 API origin을 포함한다.
3. extension build의 `VITE_API_BASE_URL`이 운영 API origin 또는 same-origin 정책과 일치한다.
4. refresh cookie 흐름이 로컬 HTTP와 운영 HTTPS에서 어떻게 다른지 문서화된다.
5. extension CORS preflight smoke test 절차가 문서화된다.

**Tasks**

- [x] 운영 extension이 호출할 API origin을 확정한다.
- [x] 필요 시 `vue/public/manifest.json` host permissions를 운영 API origin까지 확장한다.
- [x] extension build/runbook에 `VITE_API_BASE_URL` 값을 명시한다.
- [x] `vue/EXTENSION.md`에 운영 API 연결 절차를 추가한다.

**Status**

review

**Dev Agent Record**

Implementation Plan:
- COR-1.2 Option A 결정에 맞춰 production extension API origin을 `https://app.example.com`으로 확정한다.
- runtime 코드는 변경하지 않고 manifest host permission, extension 문서, runbook, manifest 계약 테스트만 갱신한다.
- local HTTP refresh cookie 한계와 production HTTPS refresh cookie 기대 동작, extension preflight smoke 절차를 `vue/EXTENSION.md`에 남긴다.

Debug Log:
- 2026-06-23: sprint-status 파일이 없어 스토리 문서 자체의 체크박스와 기록만 갱신했다.
- 2026-06-23: `CommitgotchiCorsConfiguration`, `RefreshTokenCookie`, Vue API client, manifest, COR-1.2 README/runbook/env 설명을 확인했다.
- 2026-06-23: RED 단계에서 `vue/_tests/extension-manifest.mjs`에 production API host permission 기대값을 추가했고, `npm test`가 기대대로 실패했다.
- 2026-06-23: `vue/public/manifest.json`, `vue/EXTENSION.md`, public Nginx runbook을 갱신한 뒤 `npm test`, production extension build, `./gradlew test`, `git diff --check`로 검증했다.

Completion Notes:
- Production extension API origin을 `https://app.example.com`으로 확정했다.
- `vue/public/manifest.json` `host_permissions`에 `https://app.example.com/*`를 추가하고, extension ID는 `chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn` 그대로 유지했다.
- `vue/EXTENSION.md`에 local/production extension build 명령, 운영 API 연결 계약, local HTTP refresh cookie 한계, production HTTPS refresh cookie 동작, extension CORS preflight smoke test 절차를 추가했다.
- public Nginx runbook의 extension release checklist를 COR-1.3 manifest 결정과 맞췄다.
- FastAPI browser CORS와 runtime 코드는 변경하지 않았다.

File List:
- vue/public/manifest.json
- vue/EXTENSION.md
- vue/_tests/extension-manifest.mjs
- springboot/docs/public-nginx-reverse-proxy-runbook.md
- _bmad-output/planning-artifacts/cors-origin-boundary-epic-and-stories.md

Change Log:
- 2026-06-23: COR-1.3 production extension API access contract 완료. 운영 API origin, manifest host permission, build/runbook, refresh cookie 차이, CORS preflight smoke 절차를 문서화하고 검증했다.

### Story COR-1.4: Character Asset Serving Boundary

As a 프런트엔드 개발자,
I want character sprite asset의 cross-origin 정책을 명확히 알고 싶다,
so that 이미지 표시, fallback, 향후 canvas/fetch 사용에서 CORS 문제가 생기지 않는다.

**Acceptance Criteria**

1. 현재 Vue 사용 방식이 `<img>` probe와 CSS `background-image` 기반임이 문서화된다.
2. `/character-assets/**`가 현재 `/api/**` CORS 정책 밖에 있다는 점이 문서화된다.
3. 단순 표시에는 별도 CORS가 필요 없고, canvas/fetch/CDN 전환에는 asset CORS 결정이 필요하다는 기준이 남는다.
4. asset serving 위치를 Spring Boot, Vue static, S3/CDN 중 어디로 둘지 후속 결정 항목으로 기록한다.

**Tasks**

- [x] `CharacterAssetWebConfig`와 Vue sprite rendering 사용처를 문서에 연결한다.
- [x] asset CORS가 필요한 시나리오와 필요 없는 시나리오를 구분한다.
- [x] CDN/S3 전환 시 필요한 CORS header 예시를 운영 runbook에 남긴다.

**Status**

review

**Dev Agent Record**

Implementation Plan:
- 런타임 CORS나 static asset serving 코드는 변경하지 않고, 현재 구현을 코드로 확인한 뒤 운영 runbook에 asset boundary를 문서화한다.
- `CharacterAssetWebConfig`, `SecurityConfig`, `CommitgotchiCorsConfiguration`, Vue `apiAssetUrl`, `normalizeSpriteSheetUrl`, `CgSprite` 렌더링 경로를 연결해 `/character-assets/**`가 `/api/**` CORS 정책 밖에 있음을 명시한다.
- 단순 이미지 표시와 canvas/fetch/CDN/S3 전환 시나리오를 분리하고, CDN/S3 전환 시 필요한 asset CORS header 예시를 API CORS와 별도 정책으로 남긴다.

Debug Log:
- 2026-06-23: sprint-status 파일이 없어 스토리 문서 자체의 체크박스와 기록만 갱신했다.
- 2026-06-23: `CharacterAssetWebConfig`가 `/character-assets/**`를 repo `docs` sprite PNG 정적 리소스로 노출하고, `SecurityConfig`가 해당 경로를 `permitAll`로 여는 것을 확인했다.
- 2026-06-23: `CommitgotchiCorsConfiguration`이 `registerCorsConfiguration("/api/**", configuration)`만 등록해 `/character-assets/**`가 현재 API CORS 범위 밖임을 확인했다.
- 2026-06-23: `CgSprite.vue`가 숨김 `<img class="spr__probe">`로 load/error를 확인하고 CSS `backgroundImage`/`backgroundPosition`으로 sprite frame을 표시하며, Vue runtime이 sprite binary `fetch()`나 canvas pixel read를 사용하지 않는 것을 확인했다.
- 2026-06-23: `git diff --check`, `npm test`, `./gradlew test`, FastAPI `CORSMiddleware` 검색으로 문서 변경과 guardrail을 검증했다.

Completion Notes:
- 운영 runbook에 Character Asset Serving Boundary 섹션을 추가해 Spring Boot static asset serving, Vue sprite rendering 사용처, `/api/**` CORS와 `/character-assets/**`의 분리 기준을 연결했다.
- 단순 `<img>`/CSS 이미지 표시는 별도 asset CORS 없이 가능하고, `fetch()` binary 처리, canvas pixel readback, CDN/S3 전환은 별도 asset CORS 결정이 필요하다고 정리했다.
- CDN/S3 전환 시의 `Access-Control-Allow-Origin`, `Vary: Origin`, `GET/HEAD/OPTIONS`, exposed headers, S3/CloudFront 계열 CORS rule 예시를 runbook에 남겼다.
- FastAPI browser CORS와 Spring Boot `/api/**` CORS 범위는 변경하지 않았다.

File List:
- springboot/docs/public-nginx-reverse-proxy-runbook.md
- _bmad-output/planning-artifacts/cors-origin-boundary-epic-and-stories.md

Change Log:
- 2026-06-23: COR-1.4 character asset serving boundary 문서화 완료. 현재 Vue sprite 표시 방식, Spring Boot asset 경로의 `/api/**` CORS 외부 상태, asset CORS 필요/불필요 기준, CDN/S3 header 예시를 운영 runbook에 정리했다.

### Story COR-1.5: CORS Regression Matrix and Smoke Tests

As a 개발자,
I want CORS 정책을 matrix로 자동 또는 반자동 검증하기를 원한다,
so that origin, method, header 변경이 로그인/수정/삭제/SSE를 조용히 깨뜨리지 않는다.

**Acceptance Criteria**

1. Spring Boot 통합 테스트가 허용 web origin, 허용 extension origin, 거부 origin을 검증한다.
2. `PATCH`와 `DELETE` preflight가 회귀 테스트에 포함된다.
3. `Authorization, Content-Type` header allowlist가 검증된다.
4. SSE GET 요청이 CORS 정책상 막히지 않는지 확인 절차가 있다.
5. 운영 배포 후 curl smoke test 예시가 문서화된다.

**Tasks**

- [x] 기존 `CorsConfigurationIntegrationTest` 커버리지를 현재 API client 사용 목록과 대조한다.
- [x] 필요 시 SSE preflight 또는 actual request 테스트를 추가한다.
- [x] 운영 smoke test curl 명령을 문서화한다.
- [x] CI 또는 release checklist에 CORS matrix 확인을 추가한다.

**Status**

review

**Dev Agent Record**

Implementation Plan:
- Vue API client의 실제 method/header/SSE 사용 목록을 기준으로 기존 Spring Boot CORS 테스트의 빈틈을 확인한다.
- Spring Boot CORS source of truth는 `CommitgotchiCorsConfiguration`에 유지하고, `/api/**` matrix 테스트만 보강한다.
- 운영 배포 후 사용할 curl smoke 절차와 release checklist를 public Nginx runbook에 남긴다.

Debug Log:
- 2026-06-23: sprint-status 파일이 없어 COR-1.5 진행 상태는 스토리 문서에만 기록한다.
- 2026-06-23: `vue/src/api/client.js` 사용 목록을 확인해 JSON API가 `GET/POST/PATCH/DELETE`, `Authorization`, `Content-Type`, credentials를 사용하고, SSE는 `GET` + `Authorization` + safelisted `Accept: text/event-stream`을 사용함을 대조했다.
- 2026-06-23: RED 단계에서 SSE preflight 테스트가 `Access-Control-Allow-Headers` 전체 allowlist를 기대해 실패했다. Spring CORS는 요청된 허용 header만 echo하므로 SSE preflight 기대를 `Authorization`으로 조정하고 JSON API preflight에서 `Authorization, Content-Type` allowlist를 검증하도록 분리했다.
- 2026-06-23: `./gradlew test --tests com.commitgotchi.security.CorsConfigurationIntegrationTest --tests com.commitgotchi.security.CommitgotchiCorsConfigurationTest`로 CORS matrix 테스트를 검증했다.
- 2026-06-23: `./gradlew test`, `npm test`, `git diff --check`, FastAPI `CORSMiddleware` 검색으로 전체 회귀와 guardrail을 검증했다.

Completion Notes:
- Spring Boot CORS 통합 테스트가 local web origin, production web origin, trusted Chrome extension origin, rejected origin을 명시적으로 검증하도록 보강했다.
- Vue가 사용하는 `PATCH`와 `DELETE` preflight를 web/extension origin matrix로 고정했다.
- `Authorization, Content-Type` header allowlist와 non-allowlisted header 거부를 테스트로 검증했다.
- SSE `/api/game/characters/{id}/events`의 `GET` preflight와 actual request가 CORS header를 유지하는지 테스트했다.
- `CommitgotchiCorsConfigurationTest`에 `/api/**` matrix source of truth와 `/character-assets/**`가 API CORS 범위 밖임을 확인하는 guardrail을 추가했다.
- 운영 runbook에 production web, extension, rejected origin, mutating method, SSE curl smoke test와 release CORS matrix checklist를 추가했다.
- FastAPI browser CORS와 runtime CORS 설정은 변경하지 않았다.

File List:
- springboot/src/test/java/com/commitgotchi/security/CorsConfigurationIntegrationTest.java
- springboot/src/test/java/com/commitgotchi/security/CommitgotchiCorsConfigurationTest.java
- springboot/docs/public-nginx-reverse-proxy-runbook.md
- _bmad-output/planning-artifacts/cors-origin-boundary-epic-and-stories.md

Change Log:
- 2026-06-23: COR-1.5 구현 시작. Story Status를 in-progress로 설정하고 구현 계획을 기록했다.
- 2026-06-23: COR-1.5 CORS regression matrix와 운영 smoke/release checklist 문서화를 완료하고 Status를 review로 전환했다.

## Non-Goals

- Vue에서 FastAPI를 직접 호출하도록 바꾸지 않는다.
- FastAPI에 browser CORS를 선제적으로 추가하지 않는다.
- Access Token blacklist, OAuth, 비밀번호 재설정 등 인증 기능 확장은 이 Epic 범위가 아니다.
- public Nginx의 실제 서버 배포 파일을 무조건 repo에 커밋하는 것은 이 문서에서 강제하지 않는다. 단, 선택한 운영 모델은 문서화해야 한다.

## Suggested Implementation Order

1. COR-1.1로 문서 drift를 먼저 제거한다.
2. COR-1.2로 운영 origin 모델을 확정한다.
3. COR-1.3으로 extension 운영 API 접근을 맞춘다.
4. COR-1.4로 asset boundary를 명확히 한다.
5. COR-1.5로 회귀 테스트와 smoke test를 고정한다.

## Traceability

| Requirement | Stories |
| --- | --- |
| COR-FR1 | COR-1.1, COR-1.2, COR-1.5 |
| COR-FR2 | COR-1.1, COR-1.3, COR-1.5 |
| COR-FR3 | COR-1.1, COR-1.5 |
| COR-FR4 | COR-1.1, COR-1.2 |
| COR-FR5 | COR-1.2 |
| COR-FR6 | COR-1.3 |
| COR-FR7 | COR-1.4 |
| COR-FR8 | COR-1.1, COR-1.2, COR-1.3, COR-1.4, COR-1.5 |
| COR-NFR1 | COR-1.2, COR-1.5 |
| COR-NFR2 | COR-1.1, COR-1.5 |
| COR-NFR3 | COR-1.1, COR-1.5 |
| COR-NFR4 | COR-1.5 |
| COR-NFR5 | COR-1.1, COR-1.2 |
| COR-NFR6 | COR-1.5 |
