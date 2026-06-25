# Commit-Gotchi Runtime Deploy Scripts

INFRA-3 adds two EC2-side runtime scripts:

- `scripts/deploy.sh`: SSM -> `.env.prod` -> ECR pull -> `docker compose up -d` -> health check.
- `scripts/bootstrap-tls.sh`: first Let's Encrypt certificate issuance before nginx starts.
- `scripts/make-deploy-bundle.sh`: local-only packager for the minimal EC2
  runtime bundle.

Vue/Chrome extension deployment is intentionally out of scope.

## Deploy Bundle

INFRA-3.5 deploys from ECR images plus a small runtime bundle instead of a full
repo clone on EC2. The bundle is intended to be extracted at `/opt/commitgotchi`
and contains only:

- `docker-compose.prod.yml`
- `nginx/api-only.conf`
- `postgres/init/*`
- `scripts/deploy.sh`
- `scripts/bootstrap-tls.sh`

It does not contain `docs/`, source trees, `.env.prod`, or secret values.
`deploy.sh` still creates `.env.prod` on the EC2 host from SSM at deploy time.

## GitHub Actions CI/CD

INFRA-4 adds two backend-only workflows:

- `.github/workflows/ci.yml`: runs Spring Boot tests, FastAPI tests, and Docker
  build validation on pull requests and pushes. It never pushes images to ECR.
- `.github/workflows/deploy.yml`: manual production deploy through
  `workflow_dispatch` and GitHub Environment `prod`. It uses OIDC, pushes immutable `sha-<git-sha>` ECR
  images, uploads a deploy bundle to S3, and triggers EC2 through SSM Run
  Command.

Vue/Chrome extension build and deployment remain outside these workflows.

Configure these GitHub repository variables before enabling deploys:

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

Create the GitHub Environment named `prod` before using `deploy.yml`. Required
reviewer and wait timer protection may not be available on every GitHub
repository plan; if protection is unavailable, keep production deploy
`workflow_dispatch` only and do not add automatic tag triggers.

Do not add app runtime secrets to GitHub Secrets. Runtime secret/config values
remain in SSM Parameter Store and are read by the EC2 instance role when
`scripts/deploy.sh` runs.

The deploy workflow sends an EC2 SSM command equivalent to:

1. Download
   `s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/<commit>/deploy-bundle.tar.gz`.
2. Verify the bundle does not contain `docs/`, `springboot/`, `fastapi/`,
   `vue/`, `.env*`, or unexpected paths.
3. Replace `/opt/commitgotchi` with the bundle contents.
4. Unset `AWS_PROFILE`, `AWS_DEFAULT_PROFILE`, `AWS_PROFILE_NAME`, and static
   AWS credential environment variables.
5. Export `SPRINGBOOT_IMAGE` and `FASTAPI_IMAGE` with immutable
   `sha-<git-sha>` ECR image URIs.
6. Run `./scripts/deploy.sh`.

Prepare the GitHub OIDC role with the plan-only script first:

```bash
./scripts/aws/bootstrap-github-oidc.sh \
  --profile commitgotchi-bootstrap \
  --region ap-northeast-2
```

The role policy should allow only:

- ECR auth and push for `commitgotchi-springboot` and `commitgotchi-fastapi`.
- S3 `PutObject`/`GetObject` under
  `commitgotchi-character-images-491013322019/prod/deploy-bundles/*`.
- SSM `SendCommand`/command result reads for `i-0df1583a004e52aaf`.
- Minimal EC2/SSM describe calls for deploy preflight.

Do not run `--apply`, ECR push, S3 upload, SSM SendCommand, or the real GitHub
production workflow before operator approval.

Create the local bundle:

```bash
./scripts/make-deploy-bundle.sh
tar -tzf deploy-bundle.tar.gz
```

The approved S3 handoff location stays under the existing prod prefix, avoiding
new bucket or IAM policy changes:

```text
s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/<commit>/deploy-bundle.tar.gz
```

After operator approval, upload from a trusted machine:

```bash
aws s3 cp deploy-bundle.tar.gz \
  s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/<commit>/deploy-bundle.tar.gz
```

After operator approval, fetch and extract on EC2 with the instance role:

```bash
aws s3 cp \
  s3://commitgotchi-character-images-491013322019/prod/deploy-bundles/<commit>/deploy-bundle.tar.gz \
  /tmp/deploy-bundle.tar.gz

sudo install -d -m 0755 /opt/commitgotchi
sudo tar -xzf /tmp/deploy-bundle.tar.gz -C /opt/commitgotchi
sudo chown -R "$USER":"$USER" /opt/commitgotchi

cd /opt/commitgotchi
./scripts/deploy.sh
```

If the EC2 deploy user is not the interactive `$USER`, substitute the actual
deploy user before running `chown`. Do not run the upload, EC2 fetch, or deploy
commands before operator approval.

## `deploy.sh`

Production deploy must run on the prod EC2 instance with the instance role
`commitgotchi-prod-ec2-role`. The script rejects the AWS CLI `default` profile,
named profiles in actual deploy mode, and static AWS credential environment
variables. Local validation is read-only only:

```bash
AWS_PROFILE=commitgotchi-bootstrap ./scripts/deploy.sh --plan
```

Actual EC2 deploy:

```bash
cd /opt/commitgotchi
./scripts/deploy.sh
```

What actual deploy does:

1. Verify AWS caller identity is the EC2 instance role.
2. Read `/commitgotchi/prod/*` SSM parameters with `--with-decryption`.
3. Generate `.env.prod` with mode `600`; secret values are not printed.
4. Verify Let's Encrypt files exist for `commitgotchi.store`.
5. Verify `SPRINGBOOT_IMAGE` and `FASTAPI_IMAGE` tags exist in ECR.
6. Run ECR login.
7. Run `docker compose --env-file .env.prod -f docker-compose.prod.yml pull`.
8. Run `docker compose --env-file .env.prod -f docker-compose.prod.yml up -d`.
9. Check container state/health and `/api/health` via public HTTPS, then local nginx fallback.

If `REPORT_REQUEST_QUEUE_ENABLED=true`, `DEPLOY_WORKER_PROFILE=auto` enables
the compose `worker` profile so `fastapi-report-worker` starts too.
In that mode `deploy.sh` also requires `REPORT_REQUEST_QUEUE_URL`; the FastAPI
worker consumes by URL and cannot fall back to queue name. Queue-enabled deploys
also require `REPORT_REQUEST_DISPATCHER_ENABLED=true` so existing outbox rows are
drained. If the dispatcher is enabled while the queue is disabled, deploy fails
before compose to avoid marking outbox rows as sent through the noop producer.

### Deploy Options

| Option | Default | Notes |
| --- | --- | --- |
| `--plan`, `--dry-run` | off | Read-only validation and command preview. |
| `--region REGION` | `ap-northeast-2` | AWS region. |
| `--ssm-prefix PREFIX` | `/commitgotchi/prod` | SSM root prefix. |
| `--env-file PATH` | `.env.prod` | Generated env file path. |
| `--compose-file PATH` | `docker-compose.prod.yml` | Prod compose file. |
| `--domain DOMAIN` | `commitgotchi.store` | Public API domain. |
| `--profile NAME` | unset | Allowed only with `--plan`; never copy bootstrap profile to EC2. |

### Deploy Environment Variables

| Variable | Default | Notes |
| --- | --- | --- |
| `AWS_REGION` / `AWS_DEFAULT_REGION` | `ap-northeast-2` | Region override. |
| `AWS_PROFILE` / `AWS_PROFILE_NAME` | unset | Read-only local plan only; `default` is rejected. |
| `SSM_PREFIX` | `/commitgotchi/prod` | Same as `--ssm-prefix`. |
| `DOMAIN` | `commitgotchi.store` | Same as `--domain`. |
| `SPRINGBOOT_IMAGE` | SSM `/deploy/SPRINGBOOT_IMAGE` | Optional image override for plan or emergency rollback. |
| `FASTAPI_IMAGE` | SSM `/deploy/FASTAPI_IMAGE` | Optional image override for plan or emergency rollback. |
| `DEPLOY_WORKER_PROFILE` | `auto` | `auto`, `true`, or `false`. |
| `HEALTH_TIMEOUT_SECONDS` | `180` | Container health wait. |
| `HEALTH_EXTERNAL_URL` | `https://commitgotchi.store/api/health` | First HTTP health target. |
| `HEALTH_LOCAL_URL` | `https://commitgotchi.store/api/health` | Used with `--resolve DOMAIN:443:127.0.0.1`. |

### Missing ECR Images

`deploy.sh` stops before `docker compose pull` if the configured ECR tag does
not exist. INFRA-3 does not build or push images. Push manually or complete
INFRA-4 first.

Reference manual push shape:

```bash
aws ecr get-login-password --region ap-northeast-2 \
  | docker login --username AWS --password-stdin 491013322019.dkr.ecr.ap-northeast-2.amazonaws.com

docker build -t 491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-springboot:prod ./springboot
docker push 491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-springboot:prod

docker build -t 491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-fastapi:prod ./fastapi
docker push 491013322019.dkr.ecr.ap-northeast-2.amazonaws.com/commitgotchi-fastapi:prod
```

## `bootstrap-tls.sh`

Initial TLS bootstrap must run before nginx compose starts because
`nginx/api-only.conf` directly references:

- `/etc/letsencrypt/live/commitgotchi.store/fullchain.pem`
- `/etc/letsencrypt/live/commitgotchi.store/privkey.pem`

Default mode is read-only and does not invoke certbot:

```bash
./scripts/bootstrap-tls.sh --plan --expected-ip 54.116.84.198
```

Actual issuance requires explicit approval and `--apply`:

```bash
./scripts/bootstrap-tls.sh --apply --email ops@example.com --expected-ip 54.116.84.198
```

The script refuses to proceed in apply mode when port `80` is already in use or
Commit-Gotchi prod containers are running. It does not stop nginx/compose for
the operator.

### TLS Options

| Option | Default | Notes |
| --- | --- | --- |
| `--apply` | off | Actually run `certbot certonly --standalone`. |
| `--plan`, `--dry-run` | on | Read-only preview. |
| `--domain DOMAIN` | `commitgotchi.store` | Certificate domain. |
| `--email EMAIL` | unset | Required for apply unless `--no-email`. |
| `--no-email` | off | Explicit no-email registration. |
| `--staging` | off | Use Let's Encrypt staging. |
| `--force` | off | Continue even if certificate files already exist. |
| `--expected-ip IP` | unset | DNS sanity check. |

Renewal is documented but not automated by this script:

```bash
sudo certbot renew --dry-run
sudo certbot renew
```

Do not add crontab, systemd timers, or nginx reload hooks until the operator
approves the renewal policy.
