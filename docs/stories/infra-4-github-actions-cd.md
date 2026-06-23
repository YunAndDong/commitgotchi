---
story: INFRA-4
status: draft
scope: infra (GitHub Actions CD + rollback)
phase: Phase 6
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - .github/workflows/deploy.yml   # 신규
---

# Story INFRA-4: GitHub Actions CD (자동 배포 + 롤백)

Status: draft

## Story

As a 운영자,
I want main merge는 staging, tag/manual dispatch는 prod로 자동 배포되고 실패 시 직전 태그로 롤백되는 CD를,
so that 수동 배포(INFRA-3)를 자동화하고 장애 복구를 단순화한다.

## 전제
- INFRA-2(CI/ECR), INFRA-3(EC2/SSM/수동 배포 스크립트) 완료.

## 범위

- `.github/workflows/deploy.yml`: OIDC AssumeRole → 배포 트리거.
- 배포 채널: EC2에 **SSM Run Command**(권장) 또는 SSH로 deploy 스크립트 실행.
- 롤백: health check 실패 시 직전 `sha-<git-sha>` 이미지로 재기동.

## Acceptance Criteria

### AC1 — 트리거별 환경
- **When** main에 merge되면 → staging(dev EC2)로 배포된다.
- **When** tag(`vX.Y.Z`) push 또는 manual dispatch면 → prod로 배포(승격)된다.

### AC2 — secret 비보유 + 권한 분리
- **Then** GitHub Actions는 OIDC로 deploy role을 Assume하고 앱 secret을 직접 들지 않는다.
- **And** CI의 ECR push 권한과 EC2 instance의 pull 권한은 분리되어 있다.

### AC3 — 배포 + 롤백
- **When** 배포가 실행되면 EC2에서 ECR login → SSM fetch → compose pull → up -d → health check 순으로 수행된다.
- **And** health check 실패 시 직전 image 태그로 rollback한다(MVP는 수동 트리거 허용 가능).

## 검증 / 보류
- staging 자동 배포 happy path + 의도적 실패로 롤백 동작 확인.
- 🔶 롤백 자동화 수준(완전 자동 vs 수동) 확정.
- 🔶 무중단 여부 — MVP는 짧은 다운타임 허용.
