---
story: INFRA-3
status: draft
scope: infra (EC2 prod 배포 + SSM env 주입 + 수동 배포)
phase: Phase 4
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - scripts/deploy.sh           # 신규 (SSM fetch → .env.prod → compose up)
---

# Story INFRA-3: EC2 prod 배포 + SSM env 주입 + 수동 배포

Status: draft

## Story

As a 운영자,
I want INFRA-2가 만든 EC2/SSM을 사용해 SSM 값으로 `.env.prod`를 만들고 손으로 한 번 배포해보는 경로를,
so that 자동 CD(INFRA-4)를 붙이기 전에 prod 배포 절차와 env 주입이 실제로 동작함을 검증한다.

## 전제
- INFRA-2 완료(EC2·instance role·SSM 적재·ECR·prod SQS·S3 버킷 존재).
- INFRA-1의 **독립 `docker-compose.prod.yml`**(단독 실행, vue 미정의) + Nginx config 사용.

## 범위

- `scripts/deploy.sh`(수동): ECR login → SSM `/commitgotchi/prod/*` fetch(SecureString decrypt) → `.env.prod` 생성 → `docker compose -f docker-compose.prod.yml pull && up -d`(독립 단독 실행) → health check.
- prod 이미지는 ECR `image:` 참조(INFRA-1 독립 prod compose).
- SQS는 prod 전용 큐(SSM), S3는 공용 버킷 + `prod/` prefix.

## Acceptance Criteria

### AC1 — SSM env 주입
- **When** deploy 스크립트를 실행하면,
- **Then** `/commitgotchi/prod/*` 값을 읽어 배포 시점에 `.env.prod`/export로 주입한다(평문 secret은 레포·이미지·로그에 안 남김).

### AC2 — 수동 배포 성공
- **When** `compose ... pull && up -d`,
- **Then** API Nginx·Spring·FastAPI·PostgreSQL이 기동되고 health check가 통과한다(컨테이너 HEALTHCHECK + Nginx 경유 외부 + 팀 runbook Smoke Tests). **Vue 없음.**

### AC3 — 권한 경계
- **Then** EC2 instance role은 ECR pull + SSM read(`/commitgotchi/prod/*` 한정) + S3(공용 버킷 한정) + KMS decrypt(해당 key)만 가진다.

## 검증 / 보류
- HTTPS 도메인으로 `/api/health`(Spring), `/character-assets/...`(기본 sprite) 응답 확인(프론트 서빙 없음).
- 🔶 배포 채널: 수동 SSH vs SSM Session Manager(권장: SSM).
- 🔶 report consumer 워커 prod 기동 방식(INFRA-1 결정 따름).
