---
story: INFRA-2
status: draft
scope: infra (ECR + GitHub Actions CI)
phase: Phase 3
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - .github/workflows/ci.yml   # 신규
---

# Story INFRA-2: ECR + GitHub Actions CI

Status: draft

## Story

As a 개발자,
I want PR에서는 test/build만, main merge에서는 이미지 빌드 후 ECR push까지 하는 CI를,
so that 검증된 커밋만 불변 태그 이미지로 레지스트리에 쌓인다.

## 범위

- ECR repository **3개** 생성: `commitgotchi-springboot`, `commitgotchi-fastapi`, `commitgotchi-vue`(웹 빌드).
- **Vue 웹 빌드는 파이프라인 대상**(same-origin, `VITE_API_BASE_URL` 빈 값). **확장 빌드는 파이프라인 밖**(절대 URL, 스토어, plan §2.1.1).
- `.github/workflows/ci.yml` 작성.
- 이미지 태그: `sha-<git-sha>` + 채널 태그(`staging`). `latest`는 운영 기준으로 안 씀.

## Acceptance Criteria

### AC1 — PR: build only
- **Given** PR이 열리면,
- **When** CI가 돌면,
- **Then** Vue 웹 test/build, Spring Boot test, FastAPI test, Docker image **build**가 수행되고 **ECR push는 없다**.

### AC2 — main merge: build + push
- **When** main에 merge되면,
- **Then** test 통과 후 이미지 3종(springboot/fastapi/vue 웹)을 빌드해 `sha-<git-sha>`와 `staging` 태그로 ECR에 push한다.
- **And** vue 웹 이미지는 `VITE_API_BASE_URL` 빈 값으로 빌드한다(same-origin).

### AC3 — 자격증명
- **Then** GitHub Actions는 정적 AWS 키 대신 **OIDC AssumeRole**로 ECR push 권한을 단기 획득한다(앱 secret 미보유).

## 검증 / 보류
- PR/main 각각에서 워크플로 동작 확인, ECR에 태그 정상 생성 확인.
- 🔶 각 앱 test 명령/캐시 전략 확정 (`./gradlew test`, FastAPI pytest, `npm test`/`npm run build`).
- 🔶 (파이프라인 밖) 확장 빌드(`VITE_API_BASE_URL=https://app.example.com`)/스토어 게시는 팀 runbook extension 체크리스트 따름.
