---
story: INFRA-5
status: draft
scope: infra/app (FastAPI S3 storage adapter — 공용 버킷 + prefix)
phase: Phase 6
plan: ../mvp-cicd-pipeline-plan.md
refs:
  - ../../fastapi/docs/character-image/stories/character-image-3-storage-adapter-s3-ready-contract.md
related_files:
  - fastapi/                     # S3 storage adapter (app 측)
---

# Story INFRA-5: FastAPI S3 이미지 저장 연동 + 운영 고도화 후보

Status: draft

## Story

As a 운영자,
I want FastAPI가 생성한 1×3 sprite를 공용 S3 버킷(prefix 분리)에 업로드하도록 연결하고, 운영 고도화 후보를 정리하는 단계를,
so that 캐릭터 이미지가 영속되고(§4.4 응답 shape 유지) MVP 이후 확장 경로가 준비된다.

## 전제
- INFRA-2가 만든 **공용 S3 버킷** + `S3_OBJECT_PREFIX`(`dev/`·`prod/`), EC2 instance role의 S3 권한.
- FastAPI `character-image-3`(storage-adapter-s3-ready-contract)와 한 묶음.

## 범위 (Phase 6: 확정)

- FastAPI storage adapter: 로컬 저장 → **공용 버킷 업로드**로 전환. 객체 키는 `${S3_OBJECT_PREFIX}characters/{characterId}/sprite-sheet.png`.
- §4.4 응답 shape 유지: `spriteSheetUrl`을 S3/CDN URL(또는 Presigned)로 채움. Spring 클라이언트 무수정.
- SSM `/commitgotchi/prod/shared/S3_BUCKET_NAME`·`S3_OBJECT_PREFIX` 사용.
- asset CORS는 API CORS와 분리(runbook의 S3/CloudFront 예시).

## 범위 (Phase 후속: 후보 — 의사결정 문서화)

- PostgreSQL 컨테이너 → **RDS** 전환 검토.
- 단일 EC2 → **ECS/Fargate + ALB** 전환 검토.
- SSM Parameter Store → **Secrets Manager**(rotation) 검토.
- S3 공용 버킷 → dev/prod **버킷 분리** 검토(plan §13 #9).

## Acceptance Criteria

### AC1 — S3 업로드
- **When** FastAPI가 캐릭터 이미지를 생성하면,
- **Then** 결과가 공용 버킷의 환경별 prefix 경로에 저장되고, §4.4 응답의 `spriteSheetUrl`이 그 URL로 채워진다.
- **And** 버킷은 퍼블릭 직접 노출되지 않는다(Presigned/프록시).

### AC2 — 권한·경계
- **Then** S3 접근은 EC2 instance role의 해당 버킷 한정 권한으로만. dev/prod는 prefix로 분리.

### AC3 — 고도화 의사결정 기록
- **Then** RDS/ECS/ALB/Secrets Manager/버킷 분리 전환 여부와 트리거 조건이 문서로 남는다.

## 검증 / 보류
- 이미지 생성 → S3 저장 → 조회 happy path. dev/prod prefix 분리 확인.
- 🔶 후속 항목은 트래픽/운영 부담이 임계를 넘을 때 착수(MVP에선 결정만).

> plan 말미와 동일: MVP 기준 계획이며, 운영 고도화 단계에서 ECS/RDS/ALB/Secrets Manager(및 S3 버킷 분리) 전환을 재검토한다.
