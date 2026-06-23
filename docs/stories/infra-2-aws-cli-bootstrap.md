---
story: INFRA-2
status: draft
scope: infra (aws-cli 부트스트랩: ECR·SQS·S3·IAM·EC2·SSM 생성)
phase: Phase 3
plan: ../mvp-cicd-pipeline-plan.md
related_files:
  - scripts/aws/                # 신규 (멱등 부트스트랩 스크립트)
---

# Story INFRA-2: aws-cli 부트스트랩 (리소스 생성 + SSM 적재)

Status: draft

## Story

As a 운영자,
I want 풀권한 IAM 계정으로 한 번 실행하면 ECR·SQS(prod)·S3·IAM·EC2·SSM을 멱등하게 생성/적재하는 aws-cli 스크립트를,
so that 콘솔 수작업 없이 prod 인프라를 재현 가능하게 부트스트랩하고, 생성 결과를 SSM에 모아 배포가 참조하게 한다.

## 범위 (plan §6.0 = aws-cli 부트스트랩)

- **aws-cli 프로필: `default` 금지.** 프로젝트 전용 named 프로필(예: `commitgotchi`)을 만들어 모든 명령에 `--profile commitgotchi`(또는 `AWS_PROFILE`)를 명시한다(plan §6.0).
- `scripts/aws/` 아래 **멱등** 스크립트(`describe-* || create-*` 패턴). 리전 기본 `ap-northeast-2`.
- 생성 대상:
  - **ECR** ×3: `commitgotchi-springboot`, `commitgotchi-fastapi`, `commitgotchi-vue`
  - **SQS(prod 전용)**: report-request 큐 + DLQ — local(`fastapi/.env`) 큐와 **분리된 새 큐**(plan §6.7). redrive policy `maxReceiveCount=3`.
  - **S3 공용 버킷** ×1: dev/prod 공용, 퍼블릭 접근 차단. 객체는 `dev/`·`prod/` prefix로 분리(plan §6.5).
  - **IAM**: EC2 instance role + (후속) GitHub Actions OIDC deploy role (plan §7.2 least-privilege 지향)
  - **EC2 small**: SSM agent, instance role 부여, Security Group(80/443 public, 앱 포트 비공개)
  - **SSM 적재**: 생성 결과(prod 큐 URL, 버킷명, prefix 등) + secret(SecureString)을 `/commitgotchi/prod/...`에 적재

## Acceptance Criteria

### AC0 — 전용 프로필
- **Then** 모든 aws-cli 호출이 프로젝트 전용 named 프로필을 사용하며 `default` 프로필에 의존하지 않는다.

### AC1 — 멱등 생성
- **When** 스크립트를 두 번 실행해도,
- **Then** 이미 존재하는 리소스는 재생성하지 않고 통과한다(중복/충돌 없음).

### AC2 — 리소스 분리 규칙
- **Then** prod SQS 큐는 local 큐와 별개 이름/URL로 생성된다.
- **And** S3는 단일 공용 버킷이며, prefix(`dev/`·`prod/`)로 객체 경로가 분리된다.

### AC3 — SSM 적재
- **Then** 생성 결과와 secret이 `/commitgotchi/prod/...`에 적재되고, secret은 SecureString이다.
- **And** 평문 secret은 스크립트 로그/레포에 남지 않는다.

### AC4 — 권한 경계 문서화
- **Then** 부트스트랩은 Admin 권한으로 실행하되, 운영용 EC2 role은 least-privilege(ECR pull, SSM read 경로 한정, S3 해당 버킷 한정)로 분리한다(plan §7).

## 검증 / 보류
- dry-run/`--dry-run` 또는 별도 테스트 계정에서 멱등성 확인.
- 🔶 인스턴스 타입(x86/arm), 디스크/스왑, 도메인(ACM/Certbot) 확정.
- 🔶 S3 prod prefix를 dev 자격증명으로 쓰기 금지(IAM)할지 — plan §13 #9.
- ⏭ Terraform/CDK 전환은 후속(상태관리 필요 시).
</content>
