---
story: INFRA-3
status: draft
scope: infra (EC2 프로비저닝 + SSM env 주입 + 수동 배포)
phase: Phase 4-5
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - scripts/ec2-bootstrap.sh   # 신규
  - scripts/deploy.sh          # 신규 (수동 배포 + SSM fetch)
---

# Story INFRA-3: EC2 프로비저닝 + SSM env 주입 + 수동 배포

Status: draft

## Story

As a 운영자,
I want EC2 small을 띄우고 SSM Parameter Store에서 env를 주입해 손으로 한 번 배포해보는 경로를,
so that 자동 CD를 붙이기 전에 배포 절차와 환경변수 주입이 실제로 동작함을 검증한다.

## 범위

- EC2 small 프로비저닝(SSM agent, docker/compose, instance role).
- SSM 파라미터 적재: `/commitgotchi/prod/...` (secret은 SecureString) — plan §5.2 기준. `SPRING_INTERNAL_API_SECRET`, SQS 묶음 포함.
- bootstrap/deploy 스크립트: ECR login → SSM fetch → `.env.prod` 생성 → `compose pull` → `up -d` → health check.
- Security Group: 80/443 public, SSH 제한(가능하면 SSM Session Manager), 앱 포트 비공개.

## Acceptance Criteria

### AC1 — 인스턴스 준비
- **Then** EC2 instance role이 ECR pull + SSM read(`/commitgotchi/prod/*` 경로 한정) + KMS decrypt(해당 key 한정)만 가진다.

### AC2 — SSM env 주입
- **When** deploy 스크립트를 실행하면,
- **Then** `/commitgotchi/prod/*` 값을 읽어(SecureString decrypt) 배포 시점에 `.env.prod`/export로 주입한다.
- **And** 평문 secret은 레포·이미지·로그에 남기지 않는다.

### AC3 — 수동 배포 성공
- **When** `compose -f docker-compose.prod.yml pull && up -d`를 실행하면,
- **Then** 전 서비스가 기동되고 health check가 통과한다(컨테이너 HEALTHCHECK + Nginx 경유 외부 확인).

## 검증 / 보류
- HTTPS 도메인으로 `/`(Vue 웹), `/api/health`(Spring Boot), `/character-assets/...`(기본 sprite) 응답 확인. 팀 runbook Smoke Tests 재사용.
- 🔶 인스턴스 타입(x86 vs arm), 디스크/스왑 확정.
- 🔶 SSH vs SSM Session Manager 최종 선택(권장: SSM).
