---
story: INFRA-4
status: done
scope: infra (GitHub Actions CI + prod CD)
phase: Phase 5
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - ../../.github/workflows/ci.yml
  - ../../.github/workflows/deploy.yml
  - ../infra-4-github-actions-prep.md
  - ../../scripts/README.md
  - ../../scripts/aws/bootstrap-github-oidc.sh
  - ../../scripts/make-deploy-bundle.sh
  - ../../scripts/deploy.sh
---

# Story INFRA-4: GitHub Actions CI/CD (OIDC + ECR + S3 bundle + SSM)

Status: done

Completed after PR merge, main CI verification, and a successful manual
`Production Deploy` run against prod.

## Story

As a 개발자/운영자,
I want PR/push backend CI and a manual production deploy workflow,
so that INFRA-3.5's ECR image + deploy bundle + SSM deployment flow can run
from GitHub Actions without storing app secrets in GitHub.

## Current Production Baseline

- INFRA-3 is done: EC2 manual prod deploy is live.
- INFRA-3.5 is done: prod deploy uses ECR images, an S3 deploy bundle, and SSM
  Parameter Store. EC2 does not need the full repository.
- Prod health is currently verified at `https://commitgotchi.store/api/health`.
- Default sprite is verified at
  `https://commitgotchi.store/character-assets/default_image1.png`.
- Target EC2 instance: `i-0df1583a004e52aaf`.
- Region: `ap-northeast-2`.
- SSM prefix: `/commitgotchi/prod`.
- Bundle path:
  `s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/<commit>/deploy-bundle.tar.gz`.

## Scope

- **CI (`ci.yml`)**
  - Runs on pull requests to `main` and pushes to `main`.
  - Runs Spring Boot tests with Gradle.
  - Runs FastAPI tests with pytest.
  - Builds Spring Boot and FastAPI Docker images for validation only.
  - Does not push to ECR.
  - Does not build or deploy Vue.

- **Production CD (`deploy.yml`)**
  - Runs from `workflow_dispatch` only until GitHub Environment reviewer
    protection is available for this repository.
  - Uses GitHub Environment `prod`; reviewer/wait timer protection could not be
    enabled on the current repo/plan, so manual workflow dispatch is the
    production safety gate for now.
  - Uses GitHub OIDC to assume the deploy role.
  - Builds and pushes immutable ECR tags only:
    `sha-<full-git-sha>`.
  - Runs `scripts/make-deploy-bundle.sh --commit <sha>`.
  - Verifies the deploy bundle excludes `docs/`, `springboot/`, `fastapi/`,
    `vue/`, and `.env*`.
  - Uploads the bundle to the approved S3 prefix.
  - Sends SSM Run Command to EC2. The EC2 command downloads the bundle, replaces
    `/opt/commitgotchi` with bundle contents, unsets local/static AWS
    credential variables, sets `SPRINGBOOT_IMAGE` and `FASTAPI_IMAGE` to the
    immutable `sha-` ECR images, then runs `scripts/deploy.sh`.

## Explicit Non-Scope

- Staging auto deploy is not implemented because staging infrastructure does
  not currently exist. Add it as future work after staging EC2/SSM/S3/ECR
  conventions exist.
- Vue/Chrome extension build, upload, store publication, and prod web serving
  remain outside this pipeline.
- The local work in this story must not run real GitHub Actions, apply IAM,
  push to ECR, upload to S3, or send SSM commands before operator approval.
- GitHub Secrets must not store application runtime secrets. Runtime values
  continue to come from SSM on the EC2 host through `scripts/deploy.sh`.

## Acceptance Criteria

### AC1 - PR/push backend CI

- **Given** a PR or push runs `Backend CI`,
- **When** the workflow executes,
- **Then** Spring Boot tests and FastAPI tests run, backend Docker images build,
  no ECR push occurs, and Vue is not built or deployed.

### AC2 - Manual prod deploy

- **Given** repository variables are configured and the OIDC role exists,
- **When** an operator starts `Production Deploy` manually,
- **Then** the workflow uses the `prod` GitHub Environment and only starts
  prod-changing steps after explicit manual dispatch.
- **And** automatic tag-triggered prod deploy remains future work until required
  reviewer protection can be enabled.

### AC3 - Immutable image tag deploy

- **Given** the deploy workflow is approved,
- **When** backend images are pushed,
- **Then** both images use the immutable tag `sha-<full-git-sha>`, and the EC2
  SSM command passes those exact image URIs to `deploy.sh` using
  `SPRINGBOOT_IMAGE` and `FASTAPI_IMAGE` overrides.
- **And** `latest` is not used as an operational deploy tag.

### AC4 - Deploy bundle safety

- **When** the deploy workflow creates the deploy bundle,
- **Then** it runs `scripts/make-deploy-bundle.sh --commit <sha>` and verifies
  the tarball contains only approved runtime files.
- **And** it fails if `docs/`, `springboot/`, `fastapi/`, `vue/`, `.env*`, or
  unexpected paths are present.

### AC5 - SSM deploy execution

- **Given** the bundle is uploaded to S3,
- **When** the SSM command runs on EC2,
- **Then** EC2 downloads the bundle, extracts it to `/opt/commitgotchi`, confirms
  only bundle files are present, unsets AWS profile/static credential variables,
  and runs `scripts/deploy.sh`.
- **And** the workflow prints the SSM command id, waits for completion, fetches
  command invocation output, and then performs public health/default sprite
  smoke checks.

### AC6 - Least-privilege OIDC role

- **Given** `scripts/aws/bootstrap-github-oidc.sh` is reviewed,
- **When** it is run in plan mode,
- **Then** it documents the role trust and policy without mutating IAM.
- **And** the role is restricted to `YunAndDong/commitgotchi`, the two backend
  ECR repositories, the approved deploy bundle S3 prefix, and SSM Run Command
  against the prod instance.

## Required GitHub Repository Variables

| Variable | Value |
| --- | --- |
| `AWS_REGION` | `ap-northeast-2` |
| `AWS_ROLE_ARN` | `arn:aws:iam::491013322019:role/commitgotchi-github-actions-deploy-role` |
| `EC2_INSTANCE_ID` | `i-0df1583a004e52aaf` |
| `S3_BUCKET` | `commitgotchi-character-images-491013322019` |
| `BUNDLE_PREFIX` | `prod/deploy-bundles` |
| `SSM_PREFIX` | `/commitgotchi/prod` |
| `DOMAIN` | `commitgotchi.store` |
| `SPRINGBOOT_ECR` | `491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-springboot` |
| `FASTAPI_ECR` | `491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-fastapi` |

No app runtime secret belongs in GitHub Secrets. Runtime secret values stay in
SSM Parameter Store and are read by the EC2 instance role at deploy time.

## Verification Plan

- Workflow YAML syntax validation.
- `bash -n scripts/deploy.sh scripts/make-deploy-bundle.sh scripts/bootstrap-tls.sh`
- `scripts/make-deploy-bundle.sh --commit <sha>` and tar content inspection.
- `docker compose --env-file .env.prod.example -f docker-compose.prod.yml config --quiet`
- Spring Boot test command check: `cd springboot && ./gradlew test --no-daemon`
  when Docker/Testcontainers is available.
- FastAPI test command check: `cd fastapi && python -m pytest`
- `AWS_PROFILE=commitgotchi-bootstrap ./scripts/deploy.sh --plan`
- `git diff --check`

## Local Verification Results

2026-06-23 local-only verification:

- ✅ Workflow YAML parsed with Ruby `YAML.load_file`:
  - `.github/workflows/ci.yml`
  - `.github/workflows/deploy.yml`
- ✅ `bash -n scripts/deploy.sh scripts/make-deploy-bundle.sh scripts/bootstrap-tls.sh scripts/aws/bootstrap-github-oidc.sh`
- ✅ `docker compose --env-file .env.prod.example -f docker-compose.prod.yml config --quiet`
- ✅ `./scripts/make-deploy-bundle.sh --commit daab918ecfe78deec6def43bbef1c9b3d3d82964 --output /tmp/commitgotchi-infra4-deploy-bundle.tar.gz`
  - Tar contents: `docker-compose.prod.yml`, `nginx/api-only.conf`,
    `postgres/init/`, `postgres/init/10-create-fastapi-db.sh`,
    `postgres/init/01-init-databases.sql`, `scripts/deploy.sh`,
    `scripts/bootstrap-tls.sh`.
  - Confirmed no `docs/`, `springboot/`, `fastapi/`, `vue/`, or `.env*`
    entries.
- ✅ FastAPI tests:
  - Temporary venv with Python 3.12.13.
  - `cd fastapi && python -m pytest`
  - Result: 249 passed.
- ⚠️ FastAPI tests with local default `python3` did not run because the default
  Python was 3.14 and no `psycopg-binary==3.2.3` wheel was available for that
  interpreter. CI pins Python 3.11.
- ⚠️ Spring Boot tests:
  - `cd springboot && ./gradlew test --no-daemon`
  - Compile/test discovery ran, but Testcontainers failed before application
    assertions with `DockerClientProviderStrategy` initialization errors in this
    local Docker/Testcontainers setup.
  - CI runs on `ubuntu-latest` with Docker available; keep Testcontainers as a
    required CI dependency for Spring integration tests.
- ✅ `AWS_PROFILE=commitgotchi-bootstrap ./scripts/deploy.sh --plan`
  - SSM and ECR preflight passed with secret values redacted.
  - No `.env.prod`, Docker service, or AWS resource changes were made.
  - Local TLS certificate warnings are expected outside the EC2 host.
- ✅ `git diff --check`

## Remote Completion Results

2026-06-24 remote verification:

- ✅ PR #14 merged to `main`.
  - Merge commit: `72f42175a06af937a9cd24ba1eacb8e7fa7c7860`
- ✅ `Backend CI` passed on `main`.
  - Run: `https://github.com/YunAndDong/commitgotchi/actions/runs/28038254902`
- ✅ Manual `Production Deploy` passed on `main`.
  - Run: `https://github.com/YunAndDong/commitgotchi/actions/runs/28038723964`
- ✅ Production health check passed:
  - `https://commitgotchi.store/api/health`
- ✅ Default sprite smoke check returned HTTP 200:
  - `https://commitgotchi.store/character-assets/default_image1.png`

## Future Work

- Add staging deployment only after staging infrastructure and SSM paths exist.
- Add a rollback convenience workflow that reuses a known-good `sha-` image tag.
- Consider ECR lifecycle policy for `sha-` tags after operational retention is
  decided.
