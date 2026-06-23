---
story: INFRA-3
status: done
scope: infra (EC2 prod 배포 + SSM env 주입 + 수동 배포)
phase: Phase 4
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - scripts/deploy.sh           # SSM fetch → .env.prod → compose pull/up → health
  - scripts/bootstrap-tls.sh    # nginx 기동 전 Let's Encrypt 초기 발급
  - scripts/README.md           # INFRA-4 재사용 옵션/환경변수 문서
---

# Story INFRA-3: EC2 prod 배포 + SSM env 주입 + 수동 배포

Status: done

> 구현 스크립트, TLS bootstrap, ECR image push, EC2 instance role 기반
> `docker compose up -d`, 외부 HTTPS health/default sprite smoke test까지 완료.

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

### 구현 결과

- ✅ `scripts/deploy.sh`
  - EC2 instance role credential chain 우선. 실제 deploy 모드는 named profile,
    `default` profile, static AWS credential env를 거부한다.
  - 로컬 read-only 검증은 `AWS_PROFILE=commitgotchi-bootstrap ./scripts/deploy.sh --plan`만 허용한다.
  - SSM `/commitgotchi/prod/*` 값을 `--with-decryption`으로 읽고 `.env.prod`를 mode `600`으로 생성한다(실제 모드).
  - secret 값은 로그에 출력하지 않고 `<redacted>`로 표시한다.
  - TLS 인증서 파일과 ECR image tag 존재를 `docker compose pull` 전에 확인한다.
  - `REPORT_REQUEST_QUEUE_ENABLED=true`이면 `DEPLOY_WORKER_PROFILE=auto`로 compose `worker` profile을 켠다.
  - compose pull/up 후 컨테이너 상태/HEALTHCHECK와 `https://commitgotchi.store/api/health`,
    localhost nginx fallback을 확인한다.
- ✅ `scripts/bootstrap-tls.sh`
  - 기본 도메인 `commitgotchi.store`.
  - `certbot certonly --standalone` 명령을 생성하되, `--apply` 없이는 certbot을 실행하지 않는다.
  - 80 포트 또는 prod compose 컨테이너가 떠 있으면 apply 모드에서 중단한다.
  - renewal 명령은 문서화만 하고 crontab/systemd 변경은 하지 않는다.
- ✅ `scripts/README.md`
  - INFRA-4가 재사용할 deploy/TLS 옵션, 환경변수, ECR push 필요 시 수동 명령을 문서화했다.

### 검증 결과 (2026-06-23)

- ✅ `bash -n scripts/deploy.sh`
- ✅ `bash -n scripts/bootstrap-tls.sh`
- ⚠️ `shellcheck scripts/deploy.sh scripts/bootstrap-tls.sh`: 로컬 환경에 `shellcheck` 없음.
- ✅ `aws sts get-caller-identity --profile commitgotchi-bootstrap --region ap-northeast-2`
  - account `491013322019`
  - caller `arn:aws:iam::491013322019:user/commitgotchi-bootstrap-admin`
- ✅ `./scripts/bootstrap-tls.sh --plan --expected-ip 54.116.84.198`
  - `commitgotchi.store` A record가 `54.116.84.198`로 resolve됨.
  - 로컬 기준 80 포트 사용 없음.
  - certbot은 실행하지 않음.
- ✅ `AWS_PROFILE=commitgotchi-bootstrap ./scripts/deploy.sh --plan`
  - SSM `/commitgotchi/prod` 필수 값 조회 성공, secret은 redacted.
  - `.env.prod`는 생성하지 않고 mode `600` 생성 예정만 출력.
  - 로컬 `/etc/letsencrypt/live/commitgotchi.store/*` 인증서가 없어 실제 deploy 전 TLS bootstrap 필요.
  - ECR `prod` tag 이미지 preflight 통과:
    - `491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-springboot:prod`
    - `491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-fastapi:prod`
  - Docker compose pull/up, certbot, 원격 EC2 명령 없음.
- ✅ `env -u AWS_PROFILE -u AWS_DEFAULT_PROFILE ./scripts/deploy.sh --plan`
  - 로컬에서는 non-default profile 없이는 AWS 호출 전 거부됨.
- ✅ `docker buildx build --platform linux/amd64 --push`로 ECR `prod` tag push 완료.
  - Spring Boot digest: `sha256:1b6ae36006b8c03ba9fa6baf89e2a99f419d9cd53a140e2a67995ddd94ef45ae`
  - FastAPI digest: `sha256:ee0a6a0a289c53aad748ff1bb2cd9b27388d851cefc0823d11a041d8d7d8930e`
  - push 시각: 2026-06-23 20:49~20:50 KST
- ✅ EC2에서 TLS bootstrap 완료.
  - SSM command로 `certbot` 설치 후 `certbot certonly --standalone` 실행.
  - `commitgotchi.store` 인증서 발급 성공.
  - fullchain: `/etc/letsencrypt/live/commitgotchi.store/fullchain.pem`
  - privkey: `/etc/letsencrypt/live/commitgotchi.store/privkey.pem`
  - expiry: 2026-09-21 10:56:07 UTC.
  - `certbot-renew.timer`는 `disabled`/`inactive` 상태로 확인. 자동 갱신 활성화는 별도 승인 필요.
- ✅ EC2 Docker/Compose 복구 완료.
  - SSM Run Command로 `docker`, `git`, Docker Compose v2.29.2 설치.
  - `/opt/commitgotchi` 배포 디렉터리 생성.
- ✅ EC2 source 배치 완료.
  - GitHub HTTPS clone은 private repo 인증 정보가 없어 실패했다.
  - 운영 서버에 GitHub token을 두지 않기 위해 local `git archive`를 S3 `prod/` prefix로 임시 전송했고, EC2 instance role로 다운로드했다.
  - 임시 source artifact 객체는 배포 후 삭제했다.
- ✅ EC2 instance role 기반 deploy plan 통과.
  - EC2 caller: `arn:aws:sts::491013322019:assumed-role/commitgotchi-prod-ec2-role/i-0df1583a004e52aaf`
  - SSM 값 조회, TLS 파일 확인, ECR `prod` tag preflight 통과.
- ✅ 실제 EC2 수동 deploy 완료.
  - EC2에서 `AWS_PROFILE`/static AWS credential env 없이 `./scripts/deploy.sh` 실행.
  - `.env.prod`는 `/opt/commitgotchi/.env.prod`에 mode `600`으로 생성됨.
  - `docker compose --env-file .env.prod -f docker-compose.prod.yml pull && up -d` 실행.
  - Vue/front-end 서비스는 prod compose에 없어서 배포되지 않음.
- ✅ 컨테이너/health smoke test 통과.
  - `commitgotchi-nginx-prod`, `commitgotchi-postgres-prod`, `commitgotchi-springboot-prod`, `commitgotchi-fastapi-prod` running.
  - PostgreSQL/Spring Boot/FastAPI Docker health status healthy.
  - `https://commitgotchi.store/api/health` 응답: `{"db":"up","service":"springboot","status":"ok"}`
  - `https://commitgotchi.store/character-assets/default_image1.png` 응답: HTTP 200, `content-type: image/png`.

### 후속 작업

- 🔶 `certbot-renew.timer`는 아직 disabled/inactive다. 자동 갱신 활성화는 별도 승인 후 진행한다.
- 🔶 INFRA-4에서는 GitHub Actions가 ECR build/push와 EC2 deploy trigger를 담당하도록 구성한다.
- 🔶 private repo source 배치는 INFRA-4에서 CI artifact 또는 SSM deploy trigger로 대체한다.
- 🔶 report consumer 워커 prod 기동 방식은 `REPORT_REQUEST_QUEUE_ENABLED`와 compose `worker` profile로 제어한다.
