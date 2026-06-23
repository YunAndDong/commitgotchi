---
story: INFRA-3.5
status: done
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

Status: done

> INFRA-3 production deploy is already live. This story removes the production
> requirement that the whole repo exist on EC2 by moving runtime deploy to
> immutable images, a small deploy bundle, and SSM-provided environment values.
> Real EC2 redeploy verified `/character-assets/default_image1.png` through the
> public domain on 2026-06-23.

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

Operator approved the actual state-changing steps on 2026-06-23:

1. Rebuild and push Spring Boot image containing the baked classpath sprites.
2. Upload the generated bundle to:
   `s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/<commit>/deploy-bundle.tar.gz`
3. On EC2, download and extract the bundle into `/opt/commitgotchi`.
4. Run `./scripts/deploy.sh` on EC2 using the instance role.
5. Recheck:
   - `https://commitgotchi.store/api/health`
   - `https://commitgotchi.store/character-assets/default_image1.png`

## Production Redeploy Results

2026-06-23 production redeploy:

- ✅ Rebuilt and pushed Spring Boot ECR image:
  - `491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-springboot:prod`
  - digest: `sha256:5062e00c14333f56bc49c2d87ca9b23306cb272d812546a31144aa5b0844db8c`
  - pushed at: `2026-06-23T23:33:48.396000+09:00`
- ✅ FastAPI image was not rebuilt because INFRA-3.5 did not change FastAPI runtime code.
- ✅ Generated and uploaded deploy bundle:
  - source commit: `38bd9ffc4041ba7bc12d4ade6dbfd3c99abe2e3b`
  - S3 URI: `s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/38bd9ffc4041ba7bc12d4ade6dbfd3c99abe2e3b/deploy-bundle.tar.gz`
  - size: `11997` bytes
  - contents: `docker-compose.prod.yml`, `nginx/api-only.conf`, `postgres/init/`,
    `scripts/deploy.sh`, `scripts/bootstrap-tls.sh`
- ✅ EC2 bundle-based deploy completed through SSM Run Command.
  - Bundle extracted to `/opt/commitgotchi`.
  - `/opt/commitgotchi` contains deploy bundle files and generated `.env.prod` only.
  - Confirmed no `docs/`, `springboot/`, `fastapi/`, or `vue/` directories exist on the EC2 deploy path.
  - `./scripts/deploy.sh` ran on EC2 without `AWS_PROFILE` or static AWS credential env, using
    `arn:aws:sts::491013322019:assumed-role/commitgotchi-prod-ec2-role/i-0df1583a004e52aaf`.
  - macOS extended attribute warnings appeared during `tar` extraction, but extraction and deploy succeeded.
- ✅ Container state after deploy:
  - `commitgotchi-nginx-prod`: running
  - `commitgotchi-postgres-prod`: running/healthy
  - `commitgotchi-springboot-prod`: running/healthy
  - `commitgotchi-fastapi-prod`: running/healthy
- ✅ Public smoke tests:
  - `https://commitgotchi.store/api/health` -> `{"status":"ok","db":"up","service":"springboot"}`
  - `https://commitgotchi.store/character-assets/default_image1.png` -> HTTP 200, `content-type: image/png`
  - `https://commitgotchi.store/` -> HTTP 404, expected for API-only deployment with no Vue serving.

## Current Status Notes

- INFRA-3.5 is complete: production no longer requires the full repo or host
  `docs/` directory on EC2.
- No secret plaintext should be printed, committed, or stored in the bundle.
- No additional IAM was necessary because `prod/deploy-bundles/...` is under
  the existing EC2 role `prod/` S3 object prefix.
