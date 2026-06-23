---
story: INFRA-3.5
status: in-progress
scope: infra (image-only deploy bundle + baked default character assets)
phase: Phase 4.5
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - ../../docker-compose.prod.yml
  - ../../nginx/api-only.conf
  - ../../postgres/init/
  - ../../scripts/deploy.sh
  - ../../scripts/bootstrap-tls.sh
  - ../../scripts/make-deploy-bundle.sh
  - ../../springboot/src/main/java/com/commitgotchi/character/image/CharacterAssetWebConfig.java
---

# Story INFRA-3.5: Deploy bundle + baked default character assets

Status: in-progress

> INFRA-3 production deploy is already live. This story removes the production
> requirement that the whole repo exist on EC2 by moving runtime deploy to
> immutable images, a small deploy bundle, and SSM-provided environment values.
> Do not close this story as `done` until a real EC2 redeploy verifies
> `/character-assets/default_image1.png` through the public domain.

## Story

As a 운영자,
I want production deploy to use ECR images plus a minimal deploy bundle,
so that EC2 does not need a full git clone or repo-local `docs/` directory for
runtime deploy.

## Scope

- Bake the default sprite PNGs into the Spring Boot image as classpath resources.
- Serve `/character-assets/**` from Spring Boot classpath resources instead of
  host `/docs`.
- Keep root `docs/default_image*.png` in place for existing references and local
  checks; deciding whether to remove those originals is out of scope.
- Remove production compose dependency on `./docs:/docs:ro`.
- Make production compose image-only for Spring Boot and FastAPI.
- Add a local-only deploy bundle builder that packages only:
  - `docker-compose.prod.yml`
  - `nginx/api-only.conf`
  - `postgres/init/*`
  - `scripts/deploy.sh`
  - `scripts/bootstrap-tls.sh`
- Document the S3 handoff path under the existing prod prefix:
  `s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/<commit>/deploy-bundle.tar.gz`

## Acceptance Criteria

### AC1 - Default sprites are image-baked

- **Given** the Spring Boot container has no host `/docs` mount,
- **When** `/character-assets/default_image1.png` is requested,
- **Then** Spring Boot returns HTTP 200 with PNG content from classpath resources.

### AC2 - Production compose no longer needs repo source

- **Given** the deploy bundle is extracted to `/opt/commitgotchi`,
- **When** `scripts/deploy.sh` runs there,
- **Then** its relative paths for compose, nginx config, postgres init scripts,
  and TLS/deploy scripts still resolve without `springboot/`, `fastapi/`, `vue/`,
  or `docs/` directories.

### AC3 - Deploy bundle contains only runtime files

- **When** `scripts/make-deploy-bundle.sh` is run,
- **Then** `deploy-bundle.tar.gz` contains only the approved runtime files and
  does not contain `docs/`, app source trees, `.env*`, or secrets.

### AC4 - Existing IAM boundary is preserved

- **Then** bundle upload/download uses the existing prod S3 prefix
  `prod/deploy-bundles/...`, which is covered by the EC2 role S3 policy for
  `prod/`.
- **And** no IAM or bucket policy change is applied without operator approval.

## Verification Plan

- `bash -n scripts/make-deploy-bundle.sh`
- `bash -n scripts/deploy.sh`
- `bash -n scripts/bootstrap-tls.sh`
- `docker compose -f docker-compose.prod.yml config --quiet`
- `./scripts/make-deploy-bundle.sh`, then inspect tar contents for the approved
  files only and no `docs/`.
- Spring Boot resource check:
  - Prefer `./gradlew test` from `springboot/` when Docker/Testcontainers is
    available.
  - At minimum, verify the resource is present in processed resources or the
    boot jar.
- `AWS_PROFILE=commitgotchi-bootstrap ./scripts/deploy.sh --plan`
- `git diff --check`

## Local Verification Results

2026-06-23 local-only verification:

- ✅ `bash -n scripts/make-deploy-bundle.sh`
- ✅ `bash -n scripts/deploy.sh`
- ✅ `bash -n scripts/bootstrap-tls.sh`
- ✅ `docker compose --env-file .env.prod.example -f docker-compose.prod.yml config --quiet`
- ✅ `./scripts/make-deploy-bundle.sh`
  - Created `deploy-bundle.tar.gz`.
  - Tar contents: `docker-compose.prod.yml`, `nginx/api-only.conf`,
    `postgres/init/`, `postgres/init/10-create-fastapi-db.sh`,
    `postgres/init/01-init-databases.sql`, `scripts/deploy.sh`,
    `scripts/bootstrap-tls.sh`.
  - Confirmed no `docs/`, `springboot/`, `fastapi/`, `vue/`, or `.env*`
    entries.
- ✅ Copied sprite resources match the root `docs/default_image*.png` originals
  byte-for-byte.
- ✅ `./gradlew bootJar -x test`
  - Confirmed jar contains `BOOT-INF/classes/character-assets/default_image1.png`,
    `default_image2.png`, and `default_image3.png`.
- ⚠️ `./gradlew test`
  - Compile and `processResources` completed, but Testcontainers tests failed
    before application assertions because this local Docker/Testcontainers setup
    could not find a valid Docker environment.
- ✅ `AWS_PROFILE=commitgotchi-bootstrap ./scripts/deploy.sh --plan`
  - SSM/ECR preflight passed with secret values redacted.
  - No `.env.prod`, Docker service, or AWS resource changes were made.
  - Local TLS cert/key warnings are expected outside the EC2 host.
- ✅ `git diff --check`

## Operational Handoff Before Actual Redeploy

Operator must approve the actual state-changing steps before they run:

1. Rebuild and push Spring Boot image containing the baked classpath sprites.
2. Rebuild and push FastAPI image only if its image tag is being refreshed.
3. Upload the generated bundle to:
   `s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/<commit>/deploy-bundle.tar.gz`
4. On EC2, download and extract the bundle into `/opt/commitgotchi`.
5. Run `./scripts/deploy.sh` on EC2 using the instance role.
6. Recheck:
   - `https://commitgotchi.store/api/health`
   - `https://commitgotchi.store/character-assets/default_image1.png`

## Current Status Notes

- Implementation is local only in this story until the operator approves image
  push, bundle upload, and EC2 redeploy.
- No secret plaintext should be printed, committed, or stored in the bundle.
- No additional IAM appears necessary because `prod/deploy-bundles/...` is under
  the existing `prod/` S3 object prefix.
