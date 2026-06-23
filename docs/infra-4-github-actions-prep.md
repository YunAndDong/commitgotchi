# INFRA-4 GitHub Actions Prep

INFRA-3 and INFRA-3.5 are done. Production currently deploys from:

- ECR images for Spring Boot and FastAPI.
- A small deploy bundle stored under the prod S3 prefix.
- SSM Parameter Store values under `/commitgotchi/prod`.

This note is the handoff for implementing INFRA-4 without requiring EC2 to clone
the private GitHub repository.

## Current Production Facts

| Item | Value |
| --- | --- |
| AWS account | `491013322019` |
| AWS region | `ap-northeast-2` |
| GitHub repository | `YunAndDong/commitgotchi` |
| EC2 instance | `i-0df1583a004e52aaf` |
| Domain | `commitgotchi.store` |
| SSM prefix | `/commitgotchi/prod` |
| S3 bucket | `commitgotchi-character-images-491013322019` |
| Deploy bundle prefix | `prod/deploy-bundles` |
| Spring ECR repo | `491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-springboot` |
| FastAPI ECR repo | `491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-fastapi` |

Latest verified deploy bundle flow:

1. Build and push backend images to ECR.
2. Run `scripts/make-deploy-bundle.sh --commit <commit>`.
3. Upload `deploy-bundle.tar.gz` to
   `s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/<commit>/deploy-bundle.tar.gz`.
4. EC2 downloads the bundle, extracts it to `/opt/commitgotchi`, and runs
   `scripts/deploy.sh`.
5. `deploy.sh` reads runtime env and secrets from SSM; GitHub Actions must not
   store app secrets.

## GitHub Repository Variables

Set these after the GitHub Actions OIDC role exists:

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

No GitHub app secrets are required for production runtime values. The GitHub
workflow should use OIDC to assume an AWS role and the EC2 instance role should
continue reading SSM.

## GitHub Actions OIDC Role Preparation

Use the plan-only script:

```bash
./scripts/aws/bootstrap-github-oidc.sh \
  --profile commitgotchi-bootstrap \
  --region ap-northeast-2
```

The default mode is non-mutating. It prints the IAM/OIDC changes that would be
made and performs only read-only `sts`/`iam` checks.

Actual IAM changes require explicit operator approval:

```bash
export GITHUB_OIDC_THUMBPRINT='verify-current-github-thumbprint-before-apply'

./scripts/aws/bootstrap-github-oidc.sh \
  --profile commitgotchi-bootstrap \
  --region ap-northeast-2 \
  --apply \
  --confirm-apply commitgotchi-github-oidc
```

Before applying, verify the current GitHub OIDC thumbprint from official AWS or
GitHub guidance. Do not apply with an unverified thumbprint.

Default OIDC trust subjects:

```text
repo:YunAndDong/commitgotchi:ref:refs/heads/main
repo:YunAndDong/commitgotchi:ref:refs/tags/v*
repo:YunAndDong/commitgotchi:environment:prod
```

Use GitHub Environment protection on `prod` before enabling production deploys.

## Required IAM Shape

The OIDC role should be limited to:

- ECR auth and push for `commitgotchi-springboot` and `commitgotchi-fastapi`.
- S3 `PutObject`/`GetObject` for
  `commitgotchi-character-images-491013322019/prod/deploy-bundles/*`.
- SSM `SendCommand` to `i-0df1583a004e52aaf` using `AWS-RunShellScript`.
- SSM command result reads and minimal EC2/SSM describe calls for deploy
  preflight.

The role should not have SSM Parameter Store read permissions for app secrets.
Runtime secrets remain EC2 instance-role responsibility through `scripts/deploy.sh`.

## INFRA-4 Workflow Expectations

`ci.yml`:

- Runs on PRs and pushes.
- Tests Spring Boot and FastAPI.
- Builds Docker images for validation.
- Does not push to ECR on PRs.
- Does not build or deploy Vue.

`deploy.yml`:

- Uses `permissions: id-token: write, contents: read`.
- Assumes the GitHub Actions OIDC role.
- Builds and pushes immutable `sha-<commit>` backend image tags.
- Creates and uploads the deploy bundle to S3.
- Sends SSM Run Command to EC2 to fetch the bundle and run `scripts/deploy.sh`.
- Waits for command completion and prints non-secret diagnostics.
- Runs public smoke checks for:
  - `https://commitgotchi.store/api/health`
  - `https://commitgotchi.store/character-assets/default_image1.png`

## Guardrails

- Do not run IAM apply, ECR push, S3 upload, SSM SendCommand, or real GitHub
  Actions deploys without operator approval.
- Do not store app secrets in GitHub Secrets.
- Do not use the AWS CLI `default` profile.
- Do not deploy Vue or serve frontend assets from prod Nginx.
