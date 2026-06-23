#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_NAME="$(basename "$0")"
PROJECT="commitgotchi"
ENVIRONMENT="prod"
DEFAULT_PROFILE="commitgotchi-bootstrap"
DEFAULT_REGION="ap-northeast-2"
CONFIRM_TOKEN="commitgotchi-github-oidc"

AWS_PROFILE_NAME="${AWS_PROFILE_NAME:-$DEFAULT_PROFILE}"
AWS_REGION_NAME="${AWS_REGION:-${AWS_DEFAULT_REGION:-$DEFAULT_REGION}}"
APPLY=false
CONFIRM_APPLY=""

GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-YunAndDong/commitgotchi}"
GITHUB_ENVIRONMENT="${GITHUB_ENVIRONMENT:-prod}"
GITHUB_SUBJECTS="${GITHUB_SUBJECTS:-repo:${GITHUB_REPOSITORY}:ref:refs/heads/main,repo:${GITHUB_REPOSITORY}:ref:refs/tags/v*,repo:${GITHUB_REPOSITORY}:environment:${GITHUB_ENVIRONMENT}}"
GITHUB_OIDC_PROVIDER_HOST="token.actions.githubusercontent.com"
GITHUB_OIDC_PROVIDER_URL="https://${GITHUB_OIDC_PROVIDER_HOST}"
GITHUB_OIDC_THUMBPRINT="${GITHUB_OIDC_THUMBPRINT:-}"

GITHUB_ACTIONS_ROLE_NAME="${GITHUB_ACTIONS_ROLE_NAME:-commitgotchi-github-actions-deploy-role}"
GITHUB_ACTIONS_POLICY_NAME="${GITHUB_ACTIONS_POLICY_NAME:-commitgotchi-github-actions-deploy}"

ECR_REPOSITORIES_CSV="${ECR_REPOSITORIES_CSV:-commitgotchi-springboot,commitgotchi-fastapi}"
S3_BUCKET_NAME="${S3_BUCKET_NAME:-}"
BUNDLE_PREFIX="${BUNDLE_PREFIX:-prod/deploy-bundles}"
EC2_INSTANCE_ID="${EC2_INSTANCE_ID:-i-0df1583a004e52aaf}"

TMP_FILES=()

usage() {
  cat <<USAGE
Usage:
  $SCRIPT_NAME [--profile commitgotchi-bootstrap] [--region ap-northeast-2]
  $SCRIPT_NAME --apply --confirm-apply $CONFIRM_TOKEN

Default mode is a non-mutating plan. It may run read-only sts/iam checks and
prints the IAM/OIDC changes that would be made for GitHub Actions deploys.

Options:
  --apply                 Create/update the OIDC provider, role, and inline policy.
  --confirm-apply TOKEN   Required with --apply. Token: $CONFIRM_TOKEN
  --profile NAME          AWS CLI named profile. "default" is rejected.
  --region REGION         AWS region. Default: ap-northeast-2
  -h, --help              Show this help.

Environment overrides:
  GITHUB_REPOSITORY       Default: YunAndDong/commitgotchi
  GITHUB_ENVIRONMENT      Default: prod
  GITHUB_SUBJECTS         Comma-separated OIDC subject patterns.
  GITHUB_OIDC_THUMBPRINT  Required for --apply if the provider must be created.
  GITHUB_ACTIONS_ROLE_NAME, GITHUB_ACTIONS_POLICY_NAME
  ECR_REPOSITORIES_CSV    Default: commitgotchi-springboot,commitgotchi-fastapi
  S3_BUCKET_NAME          Default: commitgotchi-character-images-<account-id>
  BUNDLE_PREFIX           Default: prod/deploy-bundles
  EC2_INSTANCE_ID         Default: i-0df1583a004e52aaf
USAGE
}

log() {
  printf '[github-oidc] %s\n' "$*" >&2
}

plan() {
  printf '[plan] %s\n' "$*" >&2
}

die() {
  printf '[github-oidc:error] %s\n' "$*" >&2
  exit 1
}

cleanup() {
  local file
  set +u
  for file in "${TMP_FILES[@]}"; do
    [[ -n "$file" && -f "$file" ]] && rm -f "$file"
  done
  set -u
}
trap cleanup EXIT

make_tmp_file() {
  local file
  file="$(mktemp)"
  TMP_FILES+=("$file")
  printf '%s' "$file"
}

quote_aws_cmd() {
  printf 'aws --profile %q --region %q' "$AWS_PROFILE_NAME" "$AWS_REGION_NAME"
  local arg
  for arg in "$@"; do
    printf ' %q' "$arg"
  done
  printf '\n'
}

aws_cli() {
  command aws --profile "$AWS_PROFILE_NAME" --region "$AWS_REGION_NAME" "$@"
}

mutate_quiet() {
  local description="$1"
  shift
  if [[ "$APPLY" == "true" ]]; then
    log "$description"
    aws_cli "$@" >/dev/null
  else
    plan "$description"
    quote_aws_cmd "$@" >&2
  fi
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --apply)
        APPLY=true
        shift
        ;;
      --confirm-apply)
        [[ $# -ge 2 ]] || die "--confirm-apply requires a token."
        CONFIRM_APPLY="$2"
        shift 2
        ;;
      --profile)
        [[ $# -ge 2 ]] || die "--profile requires a profile name."
        AWS_PROFILE_NAME="$2"
        shift 2
        ;;
      --region)
        [[ $# -ge 2 ]] || die "--region requires a region."
        AWS_REGION_NAME="$2"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "Unknown argument: $1"
        ;;
    esac
  done
}

validate_config() {
  [[ -n "$AWS_PROFILE_NAME" ]] || die "AWS profile is required."
  [[ "$AWS_PROFILE_NAME" != "default" ]] || die "Refusing to use AWS CLI default profile."
  [[ "${AWS_PROFILE:-}" != "default" ]] || die "AWS_PROFILE=default is not allowed for this script."
  [[ "${AWS_DEFAULT_PROFILE:-}" != "default" ]] || die "AWS_DEFAULT_PROFILE=default is not allowed for this script."
  [[ -n "$AWS_REGION_NAME" ]] || die "AWS region is required."
  [[ "$AWS_REGION_NAME" == "$DEFAULT_REGION" ]] || log "Region override in use: $AWS_REGION_NAME"
  [[ "$BUNDLE_PREFIX" == prod/* ]] || die "BUNDLE_PREFIX must stay under prod/: $BUNDLE_PREFIX"
  [[ -n "$EC2_INSTANCE_ID" ]] || die "EC2_INSTANCE_ID is required."
  [[ -n "$GITHUB_REPOSITORY" ]] || die "GITHUB_REPOSITORY is required."
  [[ -n "$GITHUB_SUBJECTS" ]] || die "GITHUB_SUBJECTS is required."

  if [[ "$APPLY" == "true" && "$CONFIRM_APPLY" != "$CONFIRM_TOKEN" ]]; then
    die "--apply requires --confirm-apply $CONFIRM_TOKEN"
  fi
}

account_id() {
  aws_cli sts get-caller-identity --query Account --output text
}

resolve_account_scoped_defaults() {
  local account="$1"
  if [[ -z "$S3_BUCKET_NAME" ]]; then
    S3_BUCKET_NAME="commitgotchi-character-images-$account"
    log "S3_BUCKET_NAME not set; using account-scoped default: $S3_BUCKET_NAME"
  fi
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

json_string() {
  printf '"%s"' "$(json_escape "$1")"
}

json_array_from_csv() {
  local csv="$1"
  local old_ifs="$IFS"
  local values=()
  local value
  local first=true

  IFS=',' read -r -a values <<< "$csv"
  IFS="$old_ifs"

  printf '['
  for value in "${values[@]}"; do
    value="$(trim "$value")"
    [[ -n "$value" ]] || continue
    if [[ "$first" == "true" ]]; then
      first=false
    else
      printf ', '
    fi
    json_string "$value"
  done
  printf ']'
}

json_array_for_ecr_repositories() {
  local account="$1"
  local old_ifs="$IFS"
  local repos=()
  local repo
  local first=true

  IFS=',' read -r -a repos <<< "$ECR_REPOSITORIES_CSV"
  IFS="$old_ifs"

  printf '['
  for repo in "${repos[@]}"; do
    repo="$(trim "$repo")"
    [[ -n "$repo" ]] || continue
    if [[ "$first" == "true" ]]; then
      first=false
    else
      printf ', '
    fi
    json_string "arn:aws:ecr:${AWS_REGION_NAME}:${account}:repository/${repo}"
  done
  printf ']'
}

write_trust_policy() {
  local account="$1"
  local output="$2"
  local provider_arn="arn:aws:iam::${account}:oidc-provider/${GITHUB_OIDC_PROVIDER_HOST}"

  cat > "$output" <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "$(json_escape "$provider_arn")"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "${GITHUB_OIDC_PROVIDER_HOST}:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "${GITHUB_OIDC_PROVIDER_HOST}:sub": $(json_array_from_csv "$GITHUB_SUBJECTS")
        }
      }
    }
  ]
}
JSON
}

write_permission_policy() {
  local account="$1"
  local output="$2"
  local bucket_arn="arn:aws:s3:::${S3_BUCKET_NAME}"
  local bundle_arn="${bucket_arn}/${BUNDLE_PREFIX%/}/*"
  local instance_arn="arn:aws:ec2:${AWS_REGION_NAME}:${account}:instance/${EC2_INSTANCE_ID}"
  local run_shell_document_arn="arn:aws:ssm:${AWS_REGION_NAME}::document/AWS-RunShellScript"

  cat > "$output" <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "GetEcrLoginToken",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "PushBackendImages",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:BatchGetImage",
        "ecr:CompleteLayerUpload",
        "ecr:DescribeImages",
        "ecr:DescribeRepositories",
        "ecr:GetDownloadUrlForLayer",
        "ecr:InitiateLayerUpload",
        "ecr:PutImage",
        "ecr:UploadLayerPart"
      ],
      "Resource": $(json_array_for_ecr_repositories "$account")
    },
    {
      "Sid": "ListDeployBundlePrefix",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "$(json_escape "$bucket_arn")",
      "Condition": {
        "StringLike": {
          "s3:prefix": [
            "$(json_escape "${BUNDLE_PREFIX%/}/*")"
          ]
        }
      }
    },
    {
      "Sid": "ReadWriteDeployBundles",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject"
      ],
      "Resource": "$(json_escape "$bundle_arn")"
    },
    {
      "Sid": "SendDeployCommandToProdInstance",
      "Effect": "Allow",
      "Action": "ssm:SendCommand",
      "Resource": [
        "$(json_escape "$instance_arn")",
        "$(json_escape "$run_shell_document_arn")"
      ]
    },
    {
      "Sid": "ReadDeployCommandResults",
      "Effect": "Allow",
      "Action": [
        "ssm:GetCommandInvocation",
        "ssm:ListCommandInvocations",
        "ssm:ListCommands"
      ],
      "Resource": "*"
    },
    {
      "Sid": "DescribeDeployTarget",
      "Effect": "Allow",
      "Action": [
        "ec2:DescribeInstances",
        "ssm:DescribeInstanceInformation"
      ],
      "Resource": "*"
    }
  ]
}
JSON
}

oidc_provider_exists() {
  local provider_arn="$1"
  aws_cli iam get-open-id-connect-provider \
    --open-id-connect-provider-arn "$provider_arn" >/dev/null 2>&1
}

role_exists() {
  aws_cli iam get-role --role-name "$GITHUB_ACTIONS_ROLE_NAME" >/dev/null 2>&1
}

ensure_oidc_provider() {
  local provider_arn="$1"

  if oidc_provider_exists "$provider_arn"; then
    log "GitHub OIDC provider exists: $provider_arn"
    return
  fi

  if [[ "$APPLY" == "true" && -z "$GITHUB_OIDC_THUMBPRINT" ]]; then
    die "GITHUB_OIDC_THUMBPRINT is required to create the OIDC provider. Verify the current GitHub thumbprint before apply."
  fi

  if [[ "$APPLY" == "true" ]]; then
    mutate_quiet "create GitHub OIDC provider $provider_arn" \
      iam create-open-id-connect-provider \
      --url "$GITHUB_OIDC_PROVIDER_URL" \
      --client-id-list sts.amazonaws.com \
      --thumbprint-list "$GITHUB_OIDC_THUMBPRINT" \
      --tags "Key=Project,Value=$PROJECT" "Key=Environment,Value=$ENVIRONMENT"
  else
    plan "create GitHub OIDC provider: $provider_arn"
    plan "GITHUB_OIDC_THUMBPRINT must be verified and supplied before --apply if the provider is absent."
    quote_aws_cmd iam create-open-id-connect-provider \
      --url "$GITHUB_OIDC_PROVIDER_URL" \
      --client-id-list sts.amazonaws.com \
      --thumbprint-list '${GITHUB_OIDC_THUMBPRINT}' \
      --tags "Key=Project,Value=$PROJECT" "Key=Environment,Value=$ENVIRONMENT" >&2
  fi
}

ensure_role_and_policy() {
  local account="$1"
  local trust_policy="$2"
  local permission_policy="$3"

  if role_exists; then
    log "IAM role exists: $GITHUB_ACTIONS_ROLE_NAME"
    mutate_quiet "update assume-role policy for $GITHUB_ACTIONS_ROLE_NAME" \
      iam update-assume-role-policy \
      --role-name "$GITHUB_ACTIONS_ROLE_NAME" \
      --policy-document "file://$trust_policy"
  else
    mutate_quiet "create IAM role $GITHUB_ACTIONS_ROLE_NAME" \
      iam create-role \
      --role-name "$GITHUB_ACTIONS_ROLE_NAME" \
      --assume-role-policy-document "file://$trust_policy" \
      --tags "Key=Project,Value=$PROJECT" "Key=Environment,Value=$ENVIRONMENT"
  fi

  mutate_quiet "put inline deploy policy $GITHUB_ACTIONS_POLICY_NAME on $GITHUB_ACTIONS_ROLE_NAME" \
    iam put-role-policy \
    --role-name "$GITHUB_ACTIONS_ROLE_NAME" \
    --policy-name "$GITHUB_ACTIONS_POLICY_NAME" \
    --policy-document "file://$permission_policy"

  log "GitHub Actions role ARN: arn:aws:iam::${account}:role/${GITHUB_ACTIONS_ROLE_NAME}"
}

print_summary() {
  local account="$1"
  cat >&2 <<SUMMARY
[github-oidc] GitHub repo variable to set after apply:
  AWS_ROLE_ARN=arn:aws:iam::${account}:role/${GITHUB_ACTIONS_ROLE_NAME}

[github-oidc] Suggested GitHub repo/environment variables:
  AWS_REGION=${AWS_REGION_NAME}
  EC2_INSTANCE_ID=${EC2_INSTANCE_ID}
  S3_BUCKET=${S3_BUCKET_NAME}
  BUNDLE_PREFIX=${BUNDLE_PREFIX}
  SSM_PREFIX=/commitgotchi/prod
  DOMAIN=commitgotchi.store

[github-oidc] OIDC subjects:
  ${GITHUB_SUBJECTS}
SUMMARY
}

main() {
  parse_args "$@"
  require_command aws
  validate_config

  local account
  account="$(account_id)"
  resolve_account_scoped_defaults "$account"

  local provider_arn="arn:aws:iam::${account}:oidc-provider/${GITHUB_OIDC_PROVIDER_HOST}"
  local trust_policy
  local permission_policy
  trust_policy="$(make_tmp_file)"
  permission_policy="$(make_tmp_file)"

  write_trust_policy "$account" "$trust_policy"
  write_permission_policy "$account" "$permission_policy"

  log "AWS caller account: $account"
  log "GitHub repository: $GITHUB_REPOSITORY"
  log "Deploy target instance: $EC2_INSTANCE_ID"
  log "Deploy bundle prefix: s3://$S3_BUCKET_NAME/${BUNDLE_PREFIX%/}/"

  ensure_oidc_provider "$provider_arn"
  ensure_role_and_policy "$account" "$trust_policy" "$permission_policy"
  print_summary "$account"

  if [[ "$APPLY" != "true" ]]; then
    log "Plan completed. No IAM or AWS resources were changed."
  fi
}

main "$@"
