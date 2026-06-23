---
story: INFRA-1
status: draft
scope: infra (.env 통합 + 백엔드 통합 실행 + 독립 prod compose 드라이런, API 전용 Nginx)
phase: Phase 2
plan: ../mvp-cicd-pipeline-plan.md
refs:
  - ../../springboot/docs/public-nginx-reverse-proxy-runbook.md
related_files:
  - .env.example
  - .env.prod.example          # 신규
  - docker-compose.yml
  - docker-compose.prod.yml     # 신규 (독립 단독 실행, vue 미정의)
  - nginx/                      # 신규 (API 전용 리버스 프록시 config)
---

# Story INFRA-1: .env 통합 + 통합 실행 + prod 드라이런 (API 전용 Nginx)

Status: draft

## Story

As a 개발자/운영자,
I want local과 prod가 같은 변수 이름 집합을 쓰고 값만 다르게 하는 통합 `.env` 체계와, 그것으로 백엔드 통합 실행 및 prod 설정 드라이런을 할 수 있는 compose 구성을,
so that 배포 전에 prod 프로필/설정 오류를 로컬에서 싸게 잡고, 같은 구조를 그대로 EC2로 승격할 수 있다.

## 전제 (확장 전용)

- **Vue는 Chrome 확장프로그램 전용**이라 배포(ECR/prod compose/CI/Nginx 서빙) 대상이 아니다.
- Vue 소스·로컬 개발은 유지: **로컬 `docker-compose.yml`의 vue 서비스는 개발/미리보기용으로 둘 수 있다.** 단 독립 `docker-compose.prod.yml`에는 vue를 **정의하지 않는다**.
- Nginx는 **API 전용**: `/`는 프론트 서빙 없음.

## 범위

- **`.env` 통합(SSOT)**: 루트 `.env`를 통합 실행의 단일 기준으로 정리. `.env.example` 보강, `.env.prod.example` 신규.
  - 같은 변수 이름, 값만 환경별(plan §5).
  - **실측 갭 수정**: `docker-compose.yml`의 `fastapi` 서비스에 `GEMINI_API_KEY: ${GEMINI_API_KEY}` 주입(현재 누락 → AI 미동작).
- **독립 `docker-compose.prod.yml` (단독 실행, overlay 아님)**: prod 서비스를 자체 정의한다 — **nginx(API 전용) + spring + fastapi + postgres**. **vue는 정의하지 않는다**(overlay 병합으로는 base의 vue가 안 빠지므로 별도 파일 채택, B안).
  - `SPRING_PROFILES_ACTIVE=prod`, `CORS_ALLOWED_ORIGINS=https://commitgotchi.store`(부팅 placeholder), secure cookie.
  - **API 전용 Nginx 서비스**: `/api/**`·`/character-assets/**`→Spring, FastAPI 비프록시, `/`→Vue 서빙 없음.
  - 드라이런은 로컬 build 이미지로, 실배포(INFRA-3)는 ECR `image:`로 — 이미지 출처만 다름.
  - 실행: `docker compose -f docker-compose.prod.yml ...` (base와 병합하지 않음).
- **Nginx config**: 팀 `public-nginx-reverse-proxy-runbook.md` server block 차용하되 **API-only로 정렬**(웹 `/` 서빙 제거). runbook 상단에 API-only 정렬 노트 추가.
- **SQS/S3 값**: local은 기존 `fastapi/.env` 큐 재사용(plan §6.7), S3는 공용 버킷 + `dev/` prefix(plan §6.5) — 값만 `.env`에 배선.

## Acceptance Criteria

### AC1 — 로컬 통합 실행 (dev)
- **When** `cp .env.example .env`로 값 채우고 `docker compose up --build`,
- **Then** springboot/fastapi/postgres(+개발용 vue)가 기동되고 `/api/health`(spring·fastapi)가 통과한다.
- **And** `GEMINI_API_KEY`가 fastapi 컨테이너에 주입되어 캐릭터 이미지 생성/퀴즈 채점이 실제로 동작한다.

### AC2 — prod 설정 드라이런 (로컬, API 전용)
- **When** `docker compose -f docker-compose.prod.yml --env-file .env.prod up` (독립 단독 실행),
- **Then** Spring이 `prod` 프로필로 부팅하고(`CORS_ALLOWED_ORIGINS`에 HTTPS origin 없으면 fail-fast로 부팅 실패하는 것을 확인), **vue가 아예 정의되지 않아** 뜨지 않으며, Nginx 443이 `/api/**`·`/character-assets/**`에 응답한다(`/`는 프론트 없음).

### AC3 — env 변수 이름 일관성
- **Then** local과 prod가 **같은 변수 이름**을 쓰고 값만 다르다(`.env.example`/`.env.prod.example`가 같은 키 집합).
- **And** `VITE_API_BASE_URL`은 **확장 빌드 전용** 변수다(로컬 개발=`http://localhost:8080`, 확장 릴리스=`https://commitgotchi.store`, 둘 다 파이프라인 밖 빌드). prod 백엔드 배포 env에는 포함하지 않는다.

### AC4 — dev 무영향
- **Then** 기존 `docker-compose.yml` 단독 실행(개발용 vue 포함) 흐름은 그대로 동작한다(prod 파일은 완전 별개라 base를 안 건드림).

## 검증 / 보류
- 로컬 통합 스모크: 회원가입→로그인→캐릭터 생성(이미지 §4.4)→퀴즈. 배포 검증은 팀 runbook Smoke Tests(확장/거부 origin) 재사용.
- 한계: TLS·secure 쿠키·실 S3/SQS·`chrome-extension` origin은 로컬 100% 재현 불가. 드라이런 목적은 "config/프로필 오류 조기 발견".
- report consumer 워커 배선은 **INFRA-0**이 선행(별도 프로세스 + 별도 compose 서비스). 이 스토리는 그 worker 서비스를 독립 `docker-compose.prod.yml`에도 정의할지 결정(권장: 정의).
- 🔶 HTTPS는 Certbot(runbook).
