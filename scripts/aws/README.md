# Commit-Gotchi INFRA-2 AWS Bootstrap

`bootstrap-infra-2.sh` creates the INFRA-2 AWS baseline with `aws-cli`:

- ECR repositories: `commitgotchi-springboot`, `commitgotchi-fastapi`
- Prod-only SQS report queue and DLQ
- Shared private S3 bucket with `prod/` object prefix
- EC2 runtime IAM role and instance profile
- EC2 security group, instance, and Elastic IP
- SSM parameters under `/commitgotchi/prod/...`

Vue/ECR/front-end hosting is intentionally out of scope.

`bootstrap-github-oidc.sh` prepares the INFRA-4 GitHub Actions OIDC deploy role.
Its default mode is a non-mutating plan; actual IAM/OIDC changes require
`--apply --confirm-apply commitgotchi-github-oidc`.

The default S3 bucket name is account-scoped: `commitgotchi-character-images-<aws-account-id>`. With the current bootstrap profile, the planned default is `commitgotchi-character-images-491013322019`.

## Safety Defaults

- The script rejects the AWS CLI `default` profile.
- The default profile is `commitgotchi-bootstrap`.
- The default region is `ap-northeast-2`.
- The default mode is a non-mutating plan. It may run read-only `describe`, `get`, and `sts` calls, but it does not create or update AWS resources.
- Actual resource creation/update requires both `--apply` and the confirmation token.
- SSM secrets are written as `SecureString` and are never printed to logs.

## Plan / Review

```bash
./scripts/aws/bootstrap-infra-2.sh \
  --profile commitgotchi-bootstrap \
  --region ap-northeast-2
```

Review the printed plan before any apply. The plan includes the create/update AWS CLI commands that would run, always with `--profile commitgotchi-bootstrap` and `--region ap-northeast-2`.

## GitHub Actions OIDC Plan

Run this before INFRA-4 workflow apply to review the OIDC provider, deploy role,
and inline policy shape. This does not mutate AWS resources:

```bash
./scripts/aws/bootstrap-github-oidc.sh \
  --profile commitgotchi-bootstrap \
  --region ap-northeast-2
```

Actual IAM/OIDC changes require operator approval and a verified current GitHub
OIDC thumbprint:

```bash
export GITHUB_OIDC_THUMBPRINT='verify-current-github-thumbprint-before-apply'

./scripts/aws/bootstrap-github-oidc.sh \
  --profile commitgotchi-bootstrap \
  --region ap-northeast-2 \
  --apply \
  --confirm-apply commitgotchi-github-oidc
```

## Apply

Do not run this until the final operator approval is given.

```bash
export DB_PASSWORD='set-prod-db-password'
export JWT_SECRET_BASE64='set-prod-jwt-secret-base64'
export SPRING_INTERNAL_API_SECRET='set-prod-internal-api-secret'
export GEMINI_API_KEY='set-prod-gemini-api-key'

./scripts/aws/bootstrap-infra-2.sh \
  --profile commitgotchi-bootstrap \
  --region ap-northeast-2 \
  --apply \
  --confirm-apply commitgotchi-prod-bootstrap
```

If secret env vars are missing in an interactive terminal, the script prompts without echoing input. In non-interactive mode, missing secrets fail the apply.

## Optional Overrides To Confirm Before Apply

Set these only when the defaults are not correct:

```bash
export S3_BUCKET_NAME='commitgotchi-character-images-491013322019'
export S3_OBJECT_PREFIX='prod/'
export SQS_QUEUE_NAME='commitgotchi-prod-report-request'
export SQS_DLQ_NAME='commitgotchi-prod-report-request-dlq'
export SQS_VISIBILITY_TIMEOUT='900'
export SQS_MAX_RECEIVE_COUNT='3'

export VPC_ID='vpc-...'
export SUBNET_ID='subnet-...'
export EC2_INSTANCE_TYPE='t3.small'
export EC2_VOLUME_SIZE_GB='30'
export EC2_DOCKER_COMPOSE_VERSION='v2.29.2'
export EC2_KEY_NAME=''
export SSH_ALLOWED_CIDR=''

export SSM_KMS_KEY_ID=''
```

`SSH_ALLOWED_CIDR` is empty by default, so SSH stays closed and access should use SSM Session Manager. If SSH is needed, use a single trusted CIDR, for example `203.0.113.10/32`.

The EC2 user-data installs Docker and verifies `docker compose version`. It first tries the distro `docker-compose-plugin` package and falls back to the pinned GitHub binary version in `EC2_DOCKER_COMPOSE_VERSION`.

## Commands That Apply Can Run

The apply path uses idempotent `describe/get || create` style checks and then enforces attributes/policies:

- `ecr describe-repositories`, `ecr create-repository`
- `sqs get-queue-url`, `sqs create-queue`, `sqs set-queue-attributes`, `sqs get-queue-attributes`
- `s3api head-bucket`, `s3api create-bucket`, `s3api put-public-access-block`, `s3api put-bucket-tagging`
- `iam get-role`, `iam create-role`, `iam attach-role-policy`, `iam put-role-policy`, `iam get-instance-profile`, `iam create-instance-profile`, `iam add-role-to-instance-profile`
- `ec2 describe-vpcs`, `ec2 describe-subnets`, `ec2 describe-security-groups`, `ec2 create-security-group`, `ec2 authorize-security-group-ingress`, `ec2 run-instances`, `ec2 allocate-address`, `ec2 associate-address`
- `ssm get-parameter`, `ssm put-parameter`

The EC2 security group opens only `80/tcp` and `443/tcp` publicly by default. App ports `8080`, `8000`, and `5432` are not opened.

## SSM Parameters Written

Secrets:

- `/commitgotchi/prod/db/DB_PASSWORD`
- `/commitgotchi/prod/spring/JWT_SECRET_BASE64`
- `/commitgotchi/prod/spring/SPRING_INTERNAL_API_SECRET`
- `/commitgotchi/prod/fastapi/GEMINI_API_KEY`

Non-secrets include DB names, CORS origin, S3 bucket/prefix, SQS queue/DLQ names, URLs and ARNs, ECR image URI placeholders, EC2 instance/profile/security group/EIP details, AWS account, and region.

## Final Manual Step

After apply succeeds, register the Elastic IP in Gabia DNS:

- `commitgotchi.store` A record -> `EC2_PUBLIC_IP`

Route53 is not created for MVP. Route53 should be revisited with a later ALB/ACM migration.
