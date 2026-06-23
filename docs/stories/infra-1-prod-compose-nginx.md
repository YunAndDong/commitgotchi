---
story: INFRA-1
status: draft
scope: infra (prod compose + Nginx 초안)
phase: Phase 2
plan: ../mvp-cicd-pipeline-plan.md
refs:
  - ../../springboot/docs/public-nginx-reverse-proxy-runbook.md
related_files:
  - docker-compose.prod.yml   # 신규 (이 스토리 산출물)
  - nginx/                     # 신규 (공개 리버스 프록시 config)
---

# Story INFRA-1: prod compose + Nginx 초안

Status: draft

## Story

As a 운영자,
I want 단일 EC2에서 Vue(웹)·Spring Boot·FastAPI·PostgreSQL을 ECR 이미지로 띄우고 Nginx가 same-origin 진입점이 되는 prod compose를,
so that 팀이 확정한 Option A 운영 모델 그대로 배포 기준 토폴로지를 확보한다.

## 범위

- `docker-compose.prod.yml` 초안: `build:`가 아니라 **ECR 이미지 참조(`image:`)** 기준.
- **운영 origin = Option A same-origin reverse proxy**(COR-1.2). Nginx가 `/`에 Vue 웹, `/api/**`·`/character-assets/**`를 Spring Boot로 프록시.
- Vue는 **웹 빌드**(`VITE_API_BASE_URL` 빈 값)만 EC2 대상. 확장 빌드는 파이프라인 밖.
- 앱 포트(8080/8000/5432, vue 80)는 호스트로 **공개하지 않고** compose 내부 네트워크로만.
- 환경변수는 `.env.prod`(SSM 주입, INFRA-3)에서 읽도록 구조만 잡는다.
- Nginx config는 팀 runbook의 server block을 기반으로 한다(재작성 아님, 차용).

## Acceptance Criteria

### AC1 — prod compose 토폴로지
- **Given** ECR에 푸시된 이미지(springboot/fastapi/vue 웹)가 있다고 가정할 때,
- **When** `docker compose -f docker-compose.prod.yml up -d`를 실행하면,
- **Then** Nginx·Vue·Spring Boot·FastAPI·PostgreSQL이 기동되고 앱 포트는 외부로 노출되지 않는다.
- **And** PostgreSQL은 named volume으로 데이터를 영속한다.

### AC2 — Nginx 라우팅 (same-origin)
- **Then** `/`→Vue 웹, `/api/**`·`/character-assets/**`→Spring Boot로 프록시된다.
- **And** FastAPI로는 절대 프록시하지 않는다.
- **And** `Host`/`X-Forwarded-*`/`Upgrade`/`Connection` 헤더를 보존한다(SSE 위해).
- **And** CORS 헤더는 Nginx가 붙이지 않는다(Spring Boot 소유, 패스스루).

### AC3 — dev 무영향
- **Then** 기존 `docker-compose.yml`(local)은 변경 없이 그대로 동작한다.

## 검증 / 보류
- 로컬에서 prod compose 기동 시뮬레이션(이미지는 임시 로컬 빌드 대체 가능).
- 배포 검증은 팀 runbook의 Smoke Tests 재사용.
- 🔶 HTTPS는 Certbot(runbook 예시) — ⏭ 후속 ACM/ALB 후보.
- 🔶 Vue 웹을 vue 컨테이너 proxy로 둘지, 정적 파일을 Nginx 호스트 root로 복사할지(runbook 두 예시) 선택.
