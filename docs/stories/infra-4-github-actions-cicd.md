---
story: INFRA-4
status: draft
scope: infra (GitHub Actions CI + CD)
phase: Phase 5
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - .github/workflows/ci.yml      # 신규
  - .github/workflows/deploy.yml  # 신규
---

# Story INFRA-4: GitHub Actions CI/CD (OIDC + ECR push + 배포 + 롤백)

Status: draft

## Story

As a 개발자/운영자,
I want PR 검증 → main merge 시 ECR push → tag/dispatch 시 prod 배포까지 자동화된 파이프라인을,
so that 검증된 이미지만 레지스트리에 쌓이고 수동 배포(INFRA-3)가 자동화된다.

## 전제
- INFRA-2(ECR·IAM OIDC role), INFRA-3(deploy 스크립트·EC2·SSM) 완료.

## 범위

- **CI (`ci.yml`)**: 백엔드 2종 + 웹 1종 대상. Chrome 확장 빌드는 파이프라인 밖(plan §2.1.1).
- **CD (`deploy.yml`)**: OIDC AssumeRole → ECR push → EC2에 SSM Run Command(권장)로 `deploy.sh` 실행.
- 이미지 태그: `sha-<git-sha>`(불변) + `staging` + `vX.Y.Z`. `latest`는 운영 기준 미사용.

## Acceptance Criteria

### AC1 — PR: build only
- **When** PR이 열리면 → Vue 웹 test/build, Spring test, FastAPI test, Docker image **build**만 수행하고 **ECR push 없음**.

### AC2 — main merge / tag: build + push + deploy
- **When** main merge → test 통과 후 이미지 3종을 `sha-<git-sha>`+`staging`으로 ECR push, staging 배포.
- **When** tag(`vX.Y.Z`)/manual dispatch → prod 배포(승격).
- **And** vue 웹 이미지는 `VITE_API_BASE_URL` 빈 값(same-origin)으로 빌드.

### AC3 — secret 비보유 + 권한 분리
- **Then** GitHub Actions는 OIDC로 deploy role을 Assume(앱 secret 미보유). CI push 권한과 EC2 pull 권한 분리.

### AC4 — 배포 + 롤백
- **When** 배포 실행 → EC2에서 ECR login → SSM fetch → compose pull → up -d → health check.
- **And** health check 실패 시 직전 `sha-` 태그로 rollback(MVP는 수동 트리거 허용).

## 검증 / 보류
- staging 자동 배포 happy path + 의도적 실패 롤백 확인. 배포 후 팀 runbook Smoke Tests/Release CORS Matrix를 release gate로.
- 🔶 각 앱 test 명령/캐시(`./gradlew test`, FastAPI pytest, `npm test`).
- 🔶 롤백 자동화 수준, 무중단 여부(MVP는 짧은 다운타임 허용).
</content>
