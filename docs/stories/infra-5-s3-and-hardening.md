---
story: INFRA-5
status: draft
scope: infra (S3 이미지 저장 연동 + 운영 고도화 후보)
phase: Phase 7-8
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - fastapi/                 # S3 storage adapter (앱 측, 후속)
---

# Story INFRA-5: S3 이미지 저장 연동 + 운영 고도화 후보

Status: draft

## Story

As a 운영자,
I want FastAPI 최종 이미지 생성 API가 S3에 저장되도록 연결하고, 운영 고도화 후보(RDS/ECS/ALB)를 정리하는 단계를,
so that 캐릭터 이미지가 영속되고 MVP 이후 확장 경로가 준비된다.

## 전제
- FastAPI S3 기반 최종 이미지 생성 API가 앱 레벨에서 완성되어야 함(현재 미완성, plan §2.3).

## 범위 (Phase 7: 확정)

- S3 bucket 생성(퍼블릭 접근 차단, Presigned URL 전략).
- EC2 instance role에 해당 bucket r/w 권한 추가(경로/버킷 한정).
- SSM에 `/commitgotchi/prod/fastapi/S3_BUCKET_NAME`(+ region) 적재.
- FastAPI ↔ S3 연동 동작 확인.

## 범위 (Phase 8: 후보 — 의사결정 문서화)

- PostgreSQL 컨테이너 → **RDS** 전환 검토(백업/HA/패치).
- 단일 EC2 → **ECS/Fargate + ALB** 전환 검토(가용성/오토스케일).
- SSM Parameter Store → **Secrets Manager**(rotation) 검토.

## Acceptance Criteria

### AC1 — S3 저장
- **When** FastAPI가 캐릭터 이미지를 생성하면,
- **Then** 결과가 S3 bucket에 저장되고 Spring Boot가 참조할 수 있는 URL/키가 반환된다.
- **And** bucket은 퍼블릭 직접 노출되지 않는다(Presigned 또는 프록시).

### AC2 — 권한 최소화
- **Then** S3 접근은 EC2 instance role의 해당 bucket 한정 권한으로만 이뤄진다.

### AC3 — 고도화 의사결정 기록
- **Then** RDS/ECS/ALB/Secrets Manager 전환 여부와 트리거 조건이 문서로 남는다.

## 검증 / 보류
- 이미지 생성 → S3 저장 → 조회 happy path 확인.
- 🔶 Phase 8 항목은 트래픽/운영 부담이 임계를 넘을 때 착수(MVP에선 결정만).

> plan 말미와 동일: MVP 기준 계획이며, 운영 고도화 단계에서 ECS/RDS/ALB/Secrets Manager 전환을 재검토한다.
