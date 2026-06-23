---
story: INFRA-1
status: draft
scope: infra (.env 통합 + 로컬 통합 실행 + prod compose overlay 드라이런)
phase: Phase 2
plan: ../mvp-cicd-pipeline-plan.md
refs:
  - ../../springboot/docs/public-nginx-reverse-proxy-runbook.md
related_files:
  - .env.example
  - .env.prod.example          # 신규
  - docker-compose.yml
  - docker-compose.prod.yml     # 신규 (overlay)
  - nginx/                      # 신규 (공개 리버스 프록시 config)
---

# Story INFRA-1: .env 통합 + 로컬 통합 실행 + prod compose 드라이런

Status: draft

## Story

As a 개발자/운영자,
I want local과 prod가 같은 변수 이름 집합을 쓰고 값만 다르게 하는 통합 `.env` 체계와, 그것으로 로컬 통합 실행 및 prod 설정 드라이런을 할 수 있는 compose 구성을,
so that 배포 전에 prod 프로필/설정 오류를 로컬에서 싸게 잡고, 같은 구조를 그대로 EC2로 승격할 수 있다.

## 범위

- **`.env` 통합(SSOT)**: 루트 `.env`를 로컬 통합 실행의 단일 기준으로 정리. `.env.example` 보강, `.env.prod.example` 신규.
  - 같은 변수 이름, 값만 환경별(plan §5).
  - **실측 갭 수정**: `docker-compose.yml`의 `fastapi` 서비스에 `GEMINI_API_KEY: ${GEMINI_API_KEY}` 주입(현재 누락 → AI 미동작).
- **`docker-compose.prod.yml` overlay**: base compose에 얹어 prod 설정으로 띄우는 overlay.
  - `SPRING_PROFILES_ACTIVE=prod`, `CORS_ALLOWED_ORIGINS=https://app.example.com`, secure cookie.
  - Nginx 서비스 추가(same-origin: `/`→Vue, `/api/**`·`/character-assets/**`→Spring, FastAPI 비프록시).
  - 드라이런은 로컬 build 이미지로, 실배포(INFRA-3)는 ECR `image:`로 — 이미지 출처만 다름.
- **Nginx config**: 팀 `public-nginx-reverse-proxy-runbook.md`의 server block을 차용(재작성 아님).
- **SQS/S3 값**: local은 기존 `fastapi/.env` 큐 재사용(plan §6.7), S3는 공용 버킷 + `dev/` prefix(plan §6.5) — 값만 `.env`에 배선.

## Acceptance Criteria

### AC1 — 로컬 통합 실행 (dev)
- **When** `cp .env.example .env`로 값 채우고 `docker compose up --build`,
- **Then** vue/springboot/fastapi/postgres가 기동되고 `/api/health`(spring·fastapi)가 통과한다.
- **And** `GEMINI_API_KEY`가 fastapi 컨테이너에 주입되어 캐릭터 이미지 생성/퀴즈 채점이 실제로 동작한다.

### AC2 — prod 설정 드라이런 (로컬)
- **When** `.env.prod`(로컬 드라이런 값)로 `docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up`,
- **Then** Spring이 `prod` 프로필로 부팅하고(잘못된 CORS면 fail-fast로 부팅 실패하는 것을 확인), Nginx 443 단일 진입점으로 `/`·`/api/**`가 응답한다.

### AC3 — env 변수 이름 일관성
- **Then** local과 prod가 **같은 변수 이름**을 쓰고 값만 다르다(`.env.example`/`.env.prod.example`가 같은 키 집합).
- **And** `VITE_API_BASE_URL`은 빌드 시점 변수로, 웹=빈 값(prod)·`http://localhost:8080`(local)로 구분된다.

### AC4 — dev 무영향
- **Then** 기존 `docker-compose.yml` 단독 실행 흐름은 그대로 동작한다(overlay는 추가일 뿐).

## 검증 / 보류
- 로컬 통합 스모크: 회원가입→로그인→캐릭터 생성(이미지 §4.4)→퀴즈. 배포 검증은 팀 runbook Smoke Tests 재사용.
- 한계: TLS·secure 쿠키·실 S3/SQS·`chrome-extension` origin은 로컬 100% 재현 불가. 드라이런 목적은 "config/프로필 오류 조기 발견".
- 🔶 report consumer 워커를 통합 compose에 별도 서비스로 띄울지(localstack) vs 수동 실행 — 결정 후 반영.
- 🔶 HTTPS는 Certbot(runbook). Vue 웹을 vue 컨테이너 proxy vs 호스트 static root.
</content>
