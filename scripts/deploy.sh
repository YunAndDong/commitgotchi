#!/usr/bin/env bash
set +o xtrace
set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DEFAULT_REGION="ap-northeast-2"
DEFAULT_DOMAIN="commitgotchi.store"
DEFAULT_SSM_PREFIX="/commitgotchi/prod"
EXPECTED_EC2_ROLE_NAME="${DEPLOY_EC2_ROLE_NAME:-commitgotchi-prod-ec2-role}"

AWS_REGION_NAME="${AWS_REGION:-${AWS_DEFAULT_REGION:-$DEFAULT_REGION}}"
AWS_PROFILE_NAME="${AWS_PROFILE_NAME:-${AWS_PROFILE:-${AWS_DEFAULT_PROFILE:-}}}"
SSM_PREFIX="${SSM_PREFIX:-$DEFAULT_SSM_PREFIX}"
DOMAIN="${DOMAIN:-$DEFAULT_DOMAIN}"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_ROOT/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.prod}"
TLS_CERT_DIR="${TLS_CERT_DIR:-/etc/letsencrypt/live/$DOMAIN}"
TLS_FULLCHAIN="${TLS_FULLCHAIN:-$TLS_CERT_DIR/fullchain.pem}"
TLS_PRIVKEY="${TLS_PRIVKEY:-$TLS_CERT_DIR/privkey.pem}"
NGINX_LETSENCRYPT_DIR="${NGINX_LETSENCRYPT_DIR:-/etc/letsencrypt}"
NGINX_CERTBOT_WEBROOT="${NGINX_CERTBOT_WEBROOT:-/var/www/certbot}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-5}"
HEALTH_EXTERNAL_URL="${HEALTH_EXTERNAL_URL:-https://$DOMAIN/api/health}"
HEALTH_LOCAL_URL="${HEALTH_LOCAL_URL:-https://$DOMAIN/api/health}"
DEPLOY_WORKER_PROFILE="${DEPLOY_WORKER_PROFILE:-auto}"
LOG_TAIL_LINES="${LOG_TAIL_LINES:-120}"

PLAN=false
DRY_RUN=false
COMPOSE_TOUCHED=false
CALLER_ACCOUNT=""
CALLER_ARN=""

ENV_KEYS=()
ENV_VALUES=()
ENV_SENSITIVE=()
MISSING_PARAMS=()
MISSING_IMAGES=()
PREFLIGHT_WARNINGS=()

usage() {
  cat <<USAGE
Usage:
  $SCRIPT_NAME [--plan|--dry-run] [--region ap-northeast-2]

Default mode performs the production deploy and must run on the prod EC2 host
with the instance role credential chain. Use --plan or --dry-run for local or
EC2 read-only validation.

Options:
  --plan                  Read-only validation and command preview.
  --dry-run               Alias for --plan.
  --region REGION         AWS region. Default: ap-northeast-2.
  --ssm-prefix PREFIX     SSM prefix. Default: /commitgotchi/prod.
  --env-file PATH         Generated env file. Default: .env.prod.
  --compose-file PATH     Compose file. Default: docker-compose.prod.yml.
  --domain DOMAIN         Public API domain. Default: commitgotchi.store.
  --profile NAME          AWS CLI named profile. Allowed only with --plan.
  -h, --help              Show this help.

Important environment overrides:
  AWS_REGION, AWS_PROFILE_NAME, AWS_PROFILE, AWS_DEFAULT_PROFILE
  SSM_PREFIX, DOMAIN, COMPOSE_FILE, ENV_FILE
  SPRINGBOOT_IMAGE, FASTAPI_IMAGE
  DEPLOY_WORKER_PROFILE=auto|true|false
  HEALTH_TIMEOUT_SECONDS, HEALTH_EXTERNAL_URL, HEALTH_LOCAL_URL

Actual deploy intentionally rejects AWS CLI default profile, local static
credentials, and named profiles. It expects the EC2 instance role:
  $EXPECTED_EC2_ROLE_NAME
USAGE
}

log() {
  printf '[deploy] %s\n' "$*" >&2
}

warn() {
  printf '[deploy:warn] %s\n' "$*" >&2
}

die() {
  printf '[deploy:error] %s\n' "$*" >&2
  exit 1
}

is_non_mutating() {
  [[ "$PLAN" == "true" || "$DRY_RUN" == "true" ]]
}

is_truthy() {
  case "${1:-}" in
    true|TRUE|True|1|yes|YES|Yes|on|ON|On) return 0 ;;
    *) return 1 ;;
  esac
}

is_falsey() {
  case "${1:-}" in
    false|FALSE|False|0|no|NO|No|off|OFF|Off) return 0 ;;
    *) return 1 ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --plan)
        PLAN=true
        shift
        ;;
      --dry-run)
        DRY_RUN=true
        PLAN=true
        shift
        ;;
      --region)
        [[ $# -ge 2 ]] || die "--region requires a value."
        AWS_REGION_NAME="$2"
        shift 2
        ;;
      --ssm-prefix)
        [[ $# -ge 2 ]] || die "--ssm-prefix requires a value."
        SSM_PREFIX="$2"
        shift 2
        ;;
      --env-file)
        [[ $# -ge 2 ]] || die "--env-file requires a path."
        ENV_FILE="$2"
        shift 2
        ;;
      --compose-file)
        [[ $# -ge 2 ]] || die "--compose-file requires a path."
        COMPOSE_FILE="$2"
        shift 2
        ;;
      --domain)
        [[ $# -ge 2 ]] || die "--domain requires a value."
        DOMAIN="$2"
        HEALTH_EXTERNAL_URL="${HEALTH_EXTERNAL_URL:-https://$DOMAIN/api/health}"
        HEALTH_LOCAL_URL="${HEALTH_LOCAL_URL:-https://$DOMAIN/api/health}"
        shift 2
        ;;
      --profile)
        [[ $# -ge 2 ]] || die "--profile requires a profile name."
        AWS_PROFILE_NAME="$2"
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

aws_cli() {
  if [[ -n "$AWS_PROFILE_NAME" ]]; then
    command aws --profile "$AWS_PROFILE_NAME" --region "$AWS_REGION_NAME" "$@"
  else
    command aws --region "$AWS_REGION_NAME" "$@"
  fi
}

quote_aws_cmd() {
  printf 'aws'
  if [[ -n "$AWS_PROFILE_NAME" ]]; then
    printf ' --profile %q' "$AWS_PROFILE_NAME"
  fi
  printf ' --region %q' "$AWS_REGION_NAME"
  local arg
  for arg in "$@"; do
    printf ' %q' "$arg"
  done
  printf '\n'
}

is_ec2_host() {
  local candidate
  for candidate in /sys/devices/virtual/dmi/id/product_uuid /sys/hypervisor/uuid; do
    if [[ -r "$candidate" ]] && grep -qi '^ec2' "$candidate" 2>/dev/null; then
      return 0
    fi
  done

  if command -v curl >/dev/null 2>&1; then
    local token
    token="$(curl -fsS --connect-timeout 1 --max-time 2 \
      -X PUT http://169.254.169.254/latest/api/token \
      -H 'X-aws-ec2-metadata-token-ttl-seconds: 30' 2>/dev/null || true)"
    if [[ -n "$token" ]]; then
      curl -fsS --connect-timeout 1 --max-time 2 \
        -H "X-aws-ec2-metadata-token: $token" \
        http://169.254.169.254/latest/meta-data/instance-id >/dev/null 2>&1 && return 0
    fi
  fi

  return 1
}

validate_config() {
  [[ -n "$AWS_REGION_NAME" ]] || die "AWS region is required."
  [[ "$AWS_REGION_NAME" == "$DEFAULT_REGION" ]] || warn "Region override in use: $AWS_REGION_NAME"
  [[ "$SSM_PREFIX" == /* ]] || die "SSM_PREFIX must start with '/': $SSM_PREFIX"
  [[ -f "$COMPOSE_FILE" ]] || die "Compose file not found: $COMPOSE_FILE"
  [[ "$DEPLOY_WORKER_PROFILE" == "auto" ]] || is_truthy "$DEPLOY_WORKER_PROFILE" || is_falsey "$DEPLOY_WORKER_PROFILE" \
    || die "DEPLOY_WORKER_PROFILE must be auto, true, or false."
  [[ "$HEALTH_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || die "HEALTH_TIMEOUT_SECONDS must be numeric."
  [[ "$HEALTH_INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || die "HEALTH_INTERVAL_SECONDS must be numeric."

  if [[ "${AWS_PROFILE:-}" == "default" || "${AWS_DEFAULT_PROFILE:-}" == "default" || "$AWS_PROFILE_NAME" == "default" ]]; then
    die "Refusing to use AWS CLI default profile."
  fi

  if is_non_mutating; then
    if [[ -z "$AWS_PROFILE_NAME" ]] && ! is_ec2_host; then
      die "Local --plan requires a non-default AWS profile, e.g. AWS_PROFILE=commitgotchi-bootstrap $SCRIPT_NAME --plan"
    fi
  else
    [[ -z "$AWS_PROFILE_NAME" ]] || die "Actual deploy must use the EC2 instance role, not an AWS CLI profile."
    [[ -z "${AWS_ACCESS_KEY_ID:-}" && -z "${AWS_SECRET_ACCESS_KEY:-}" && -z "${AWS_SESSION_TOKEN:-}" ]] \
      || die "Actual deploy refuses static AWS credential environment variables; use the EC2 instance role."
    is_ec2_host || die "Actual deploy must run on the prod EC2 host, not a local default credential chain."
  fi
}

load_caller_identity() {
  CALLER_ACCOUNT="$(aws_cli sts get-caller-identity --query Account --output text)"
  CALLER_ARN="$(aws_cli sts get-caller-identity --query Arn --output text)"
  log "AWS caller account: $CALLER_ACCOUNT"
  log "AWS caller ARN: $CALLER_ARN"

  if ! is_non_mutating; then
    case "$CALLER_ARN" in
      *":assumed-role/$EXPECTED_EC2_ROLE_NAME/"*) ;;
      *)
        die "Actual deploy must run as EC2 role $EXPECTED_EC2_ROLE_NAME. Current caller: $CALLER_ARN"
        ;;
    esac
  fi
}

set_env_value() {
  local key="$1"
  local value="$2"
  local sensitive="$3"
  local i
  for i in "${!ENV_KEYS[@]}"; do
    if [[ "${ENV_KEYS[$i]}" == "$key" ]]; then
      ENV_VALUES[$i]="$value"
      ENV_SENSITIVE[$i]="$sensitive"
      return
    fi
  done
  ENV_KEYS+=("$key")
  ENV_VALUES+=("$value")
  ENV_SENSITIVE+=("$sensitive")
}

has_env_key() {
  local key="$1"
  local i
  for i in "${!ENV_KEYS[@]}"; do
    [[ "${ENV_KEYS[$i]}" == "$key" ]] && return 0
  done
  return 1
}

get_env_value() {
  local key="$1"
  local i
  for i in "${!ENV_KEYS[@]}"; do
    if [[ "${ENV_KEYS[$i]}" == "$key" ]]; then
      printf '%s' "${ENV_VALUES[$i]}"
      return 0
    fi
  done
  return 1
}

derived_s3_object_prefix_uri() {
  local bucket="$1"
  local prefix="$2"
  prefix="${prefix#/}"
  prefix="${prefix%/}"
  if [[ -n "$prefix" ]]; then
    printf 's3://%s/%s' "$bucket" "$prefix"
  else
    printf 's3://%s' "$bucket"
  fi
}

set_env_default() {
  local key="$1"
  local value="$2"
  local sensitive="${3:-false}"
  has_env_key "$key" || set_env_value "$key" "$value" "$sensitive"
}

ssm_parameter_value() {
  local path="$1"
  aws_cli ssm get-parameter \
    --name "$path" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text
}

fetch_parameter_env() {
  local key="$1"
  local suffix="$2"
  local required="$3"
  local default_value="$4"
  local sensitive="$5"
  local path="$SSM_PREFIX/$suffix"
  local value

  if value="$(ssm_parameter_value "$path" 2>/dev/null)"; then
    [[ "$value" == "None" ]] && value=""
    set_env_value "$key" "$value" "$sensitive"
    if [[ "$sensitive" == "true" ]]; then
      log "SSM resolved: $path -> $key=<redacted>"
    else
      log "SSM resolved: $path -> $key"
    fi
    return
  fi

  if [[ "$required" == "true" ]]; then
    MISSING_PARAMS+=("$path")
    return
  fi

  set_env_value "$key" "$default_value" "$sensitive"
  warn "SSM optional parameter not found; using default for $key: $path"
}

apply_operator_overrides() {
  if [[ -n "${SPRINGBOOT_IMAGE:-}" ]]; then
    set_env_value "SPRINGBOOT_IMAGE" "$SPRINGBOOT_IMAGE" "false"
    warn "SPRINGBOOT_IMAGE override supplied by environment."
  fi
  if [[ -n "${FASTAPI_IMAGE:-}" ]]; then
    set_env_value "FASTAPI_IMAGE" "$FASTAPI_IMAGE" "false"
    warn "FASTAPI_IMAGE override supplied by environment."
  fi
}

load_env_from_ssm() {
  local default_springboot_image="$CALLER_ACCOUNT.dkr.ecr.$AWS_REGION_NAME.amazonaws.com/commitgotchi-springboot:prod"
  local default_fastapi_image="$CALLER_ACCOUNT.dkr.ecr.$AWS_REGION_NAME.amazonaws.com/commitgotchi-fastapi:prod"

  fetch_parameter_env "DB_USER" "db/DB_USER" "true" "" "false"
  fetch_parameter_env "SPRING_DB_NAME" "db/SPRING_DB_NAME" "true" "" "false"
  fetch_parameter_env "FASTAPI_DB_NAME" "db/FASTAPI_DB_NAME" "true" "" "false"
  fetch_parameter_env "DB_PASSWORD" "db/DB_PASSWORD" "true" "" "true"

  fetch_parameter_env "JWT_SECRET_BASE64" "spring/JWT_SECRET_BASE64" "true" "" "true"
  fetch_parameter_env "JWT_ISSUER" "spring/JWT_ISSUER" "false" "commitgotchi-springboot" "false"
  fetch_parameter_env "CORS_ALLOWED_ORIGINS" "spring/CORS_ALLOWED_ORIGINS" "true" "" "false"
  fetch_parameter_env "SPRING_INTERNAL_API_SECRET" "spring/SPRING_INTERNAL_API_SECRET" "true" "" "true"
  fetch_parameter_env "CHARACTER_IMAGE_ENABLED" "spring/CHARACTER_IMAGE_ENABLED" "false" "true" "false"
  fetch_parameter_env "REPORT_REQUEST_QUEUE_ENABLED" "spring/REPORT_REQUEST_QUEUE_ENABLED" "false" "false" "false"
  fetch_parameter_env "REPORT_REQUEST_DISPATCHER_ENABLED" "spring/REPORT_REQUEST_DISPATCHER_ENABLED" "false" "false" "false"

  fetch_parameter_env "GEMINI_API_KEY" "fastapi/GEMINI_API_KEY" "true" "" "true"
  fetch_parameter_env "AWS_REGION" "shared/AWS_REGION" "false" "$AWS_REGION_NAME" "false"
  fetch_parameter_env "S3_BUCKET_NAME" "shared/S3_BUCKET_NAME" "true" "" "false"
  fetch_parameter_env "S3_OBJECT_PREFIX" "shared/S3_OBJECT_PREFIX" "true" "" "false"
  if has_env_key "S3_BUCKET_NAME" && has_env_key "S3_OBJECT_PREFIX"; then
    local s3_bucket_name
    local s3_object_prefix
    s3_bucket_name="$(get_env_value S3_BUCKET_NAME)"
    s3_object_prefix="$(get_env_value S3_OBJECT_PREFIX)"
    set_env_default "CHARACTER_IMAGE_STORAGE_BACKEND" "s3" "false"
    set_env_default "CHARACTER_IMAGE_S3_PRESIGNED_GET_ENABLED" "true" "false"
    set_env_default "CHARACTER_IMAGE_S3_OBJECT_PREFIX" \
      "$(derived_s3_object_prefix_uri "$s3_bucket_name" "$s3_object_prefix")" \
      "false"
  fi
  fetch_parameter_env "REPORT_REQUEST_QUEUE_NAME" "shared/REPORT_REQUEST_QUEUE_NAME" "false" "" "false"
  fetch_parameter_env "REPORT_REQUEST_QUEUE_URL" "shared/REPORT_REQUEST_QUEUE_URL" "false" "" "false"
  fetch_parameter_env "REPORT_REQUEST_QUEUE_ARN" "shared/REPORT_REQUEST_QUEUE_ARN" "false" "" "false"
  fetch_parameter_env "REPORT_REQUEST_DLQ_NAME" "shared/REPORT_REQUEST_DLQ_NAME" "false" "" "false"
  fetch_parameter_env "REPORT_REQUEST_DLQ_URL" "shared/REPORT_REQUEST_DLQ_URL" "false" "" "false"
  fetch_parameter_env "REPORT_REQUEST_DLQ_ARN" "shared/REPORT_REQUEST_DLQ_ARN" "false" "" "false"
  fetch_parameter_env "SPRINGBOOT_IMAGE" "deploy/SPRINGBOOT_IMAGE" "false" "$default_springboot_image" "false"
  fetch_parameter_env "FASTAPI_IMAGE" "deploy/FASTAPI_IMAGE" "false" "$default_fastapi_image" "false"

  set_env_default "DB_PORT" "5432" "false"
  set_env_default "SPRING_PORT" "8080" "false"
  set_env_default "FASTAPI_PORT" "8000" "false"
  set_env_default "SPRING_PROFILES_ACTIVE" "prod" "false"
  set_env_default "REFRESH_COOKIE_SECURE" "true" "false"
  set_env_default "CHARACTER_IMAGE_BASE_URL" "http://fastapi:8000" "false"
  set_env_default "CHARACTER_IMAGE_FALLBACK_SPRITE_SHEET_URL" "/character-assets/default_image1.png" "false"
  set_env_default "QUIZ_GRADING_ENABLED" "false" "false"
  set_env_default "QUIZ_GRADING_BASE_URL" "http://fastapi:8000" "false"
  set_env_default "QUIZ_GRADING_CALLBACK_URL" "http://springboot:8080/api/internal/quizzes/grade-result" "false"
  set_env_default "REPORT_MIDNIGHT_SCHEDULER_ENABLED" "true" "false"
  set_env_default "AWS_SQS_ENDPOINT" "" "false"
  set_env_default "AWS_ACCESS_KEY_ID" "" "true"
  set_env_default "AWS_SECRET_ACCESS_KEY" "" "true"
  set_env_default "AWS_SESSION_TOKEN" "" "true"
  set_env_default "REPORT_REQUEST_QUEUE_ACCESS_KEY_ID" "" "true"
  set_env_default "REPORT_REQUEST_QUEUE_SECRET_ACCESS_KEY" "" "true"
  set_env_default "REPORT_REQUEST_QUEUE_SESSION_TOKEN" "" "true"
  set_env_default "SPRING_BOOT_INTERNAL_BASE_URL" "http://springboot:8080" "false"
  set_env_default "SPRING_REPORT_CALLBACK_PATH" "/api/report" "false"
  set_env_default "SPRING_QUIZ_GRADE_RESULT_PATH" "/api/internal/quizzes/grade-result" "false"
  set_env_default "SPRING_CALLBACK_TIMEOUT_SECONDS" "10" "false"
  set_env_default "GEMINI_QUIZ_GRADER_MODEL" "gemini-3.1-flash-lite" "false"
  set_env_default "GEMINI_REPORT_ANALYZER_MODEL" "gemini-3.1-flash-lite" "false"
  set_env_default "GEMINI_EMBEDDING_MODEL" "gemini-embedding-2" "false"
  set_env_default "GEMINI_EMBEDDING_DIMENSIONS" "768" "false"
  set_env_default "GEMINI_IMAGE_MODEL" "gemini-2.5-flash-image" "false"
  set_env_default "GEMINI_IMAGE_TIMEOUT_SECONDS" "30" "false"
  set_env_default "GEMINI_IMAGE_RETRY_LIMIT" "1" "false"
  set_env_default "CHARACTER_IMAGE_STORAGE_ROOT" "runtime/data/character-images" "false"
  set_env_default "NGINX_HTTP_PORT" "80" "false"
  set_env_default "NGINX_HTTPS_PORT" "443" "false"
  set_env_default "NGINX_LETSENCRYPT_DIR" "$NGINX_LETSENCRYPT_DIR" "false"
  set_env_default "NGINX_CERTBOT_WEBROOT" "$NGINX_CERTBOT_WEBROOT" "false"

  apply_operator_overrides

  if [[ ${#MISSING_PARAMS[@]} -gt 0 ]]; then
    printf '[deploy:error] Required SSM parameters are missing:\n' >&2
    local param
    for param in "${MISSING_PARAMS[@]}"; do
      printf '  - %s\n' "$param" >&2
    done
    die "SSM env resolution failed."
  fi
}

dotenv_quote() {
  local value="$1"
  [[ "$value" != *$'\n'* ]] || die "Refusing to write multiline value to $ENV_FILE."
  value="${value//$'\r'/}"
  value="${value//\\/\\\\}"
  value="${value//\'/\\\'}"
  printf "'%s'" "$value"
}

write_env_file() {
  if is_non_mutating; then
    log "Would write $ENV_FILE with mode 600 (${#ENV_KEYS[@]} keys; sensitive values redacted)."
    return
  fi

  local tmp_file="$ENV_FILE.tmp.$$"
  umask 077
  {
    printf '# Generated by scripts/deploy.sh from SSM %s. Do not commit.\n' "$SSM_PREFIX"
    printf '# Sensitive values are intentionally present only on the deployment host.\n\n'
    local i
    for i in "${!ENV_KEYS[@]}"; do
      printf '%s=' "${ENV_KEYS[$i]}"
      dotenv_quote "${ENV_VALUES[$i]}"
      printf '\n'
    done
  } >"$tmp_file"
  chmod 600 "$tmp_file"
  mv "$tmp_file" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  log "Wrote $ENV_FILE with mode 600."
}

file_exists_maybe_sudo() {
  local path="$1"
  [[ -f "$path" ]] && return 0
  if command -v sudo >/dev/null 2>&1; then
    sudo -n test -f "$path" >/dev/null 2>&1 && return 0
  fi
  return 1
}

preflight_tls() {
  local ok=true
  if file_exists_maybe_sudo "$TLS_FULLCHAIN"; then
    log "TLS fullchain exists: $TLS_FULLCHAIN"
  else
    PREFLIGHT_WARNINGS+=("Missing TLS certificate: $TLS_FULLCHAIN")
    ok=false
  fi

  if file_exists_maybe_sudo "$TLS_PRIVKEY"; then
    log "TLS private key exists: $TLS_PRIVKEY"
  else
    PREFLIGHT_WARNINGS+=("Missing TLS private key: $TLS_PRIVKEY")
    ok=false
  fi

  if [[ "$ok" != "true" ]]; then
    warn "Nginx config references Let's Encrypt files directly; compose would fail before TLS is bootstrapped."
    warn "Run scripts/bootstrap-tls.sh --plan first, then --apply after operator approval."
    is_non_mutating || die "TLS preflight failed."
  fi
}

IMAGE_REPO_NAME=""
IMAGE_TAG=""
IMAGE_DIGEST=""

parse_ecr_image() {
  local image="$1"
  local without_registry
  local first_component

  IMAGE_REPO_NAME=""
  IMAGE_TAG=""
  IMAGE_DIGEST=""

  if [[ "$image" == *@sha256:* ]]; then
    IMAGE_DIGEST="${image##*@}"
    without_registry="${image%@*}"
  elif [[ "${image##*/}" == *:* ]]; then
    IMAGE_TAG="${image##*:}"
    without_registry="${image%:*}"
  else
    return 1
  fi

  if [[ "$without_registry" == */* ]]; then
    first_component="${without_registry%%/*}"
    case "$first_component" in
      *.*|*:*|localhost)
        without_registry="${without_registry#*/}"
        ;;
    esac
  fi

  IMAGE_REPO_NAME="$without_registry"
  [[ -n "$IMAGE_REPO_NAME" ]] || return 1
}

preflight_ecr_image() {
  local label="$1"
  local image="$2"
  local image_id_arg
  local digest

  if ! parse_ecr_image "$image"; then
    MISSING_IMAGES+=("$label: $image (missing explicit tag or digest)")
    return
  fi

  if [[ -n "$IMAGE_DIGEST" ]]; then
    image_id_arg="imageDigest=$IMAGE_DIGEST"
  else
    image_id_arg="imageTag=$IMAGE_TAG"
  fi

  if digest="$(aws_cli ecr describe-images \
      --repository-name "$IMAGE_REPO_NAME" \
      --image-ids "$image_id_arg" \
      --query 'imageDetails[0].imageDigest' \
      --output text 2>/dev/null)"; then
    [[ "$digest" == "None" ]] && digest=""
    if [[ -n "$digest" ]]; then
      log "ECR image exists for $label: $IMAGE_REPO_NAME ${IMAGE_TAG:-$IMAGE_DIGEST}"
      return
    fi
  fi

  MISSING_IMAGES+=("$label: $image")
}

print_image_push_guidance() {
  local springboot_image
  local fastapi_image
  local prefix="[deploy:error]"
  springboot_image="$(get_env_value SPRINGBOOT_IMAGE)"
  fastapi_image="$(get_env_value FASTAPI_IMAGE)"
  if is_non_mutating; then
    prefix="[deploy:warn]"
  fi

  cat >&2 <<GUIDANCE
$prefix Required ECR image tag(s) are missing. Actual deploy would stop here.
Build/push is intentionally not performed by INFRA-3. Push images manually or
complete INFRA-4 first.

Reference commands to run from a trusted build machine after review:
  aws ecr get-login-password --region $AWS_REGION_NAME | docker login --username AWS --password-stdin $CALLER_ACCOUNT.dkr.ecr.$AWS_REGION_NAME.amazonaws.com
  docker build -t $springboot_image ./springboot
  docker push $springboot_image
  docker build -t $fastapi_image ./fastapi
  docker push $fastapi_image
GUIDANCE
}

preflight_ecr_images() {
  local springboot_image
  local fastapi_image
  springboot_image="$(get_env_value SPRINGBOOT_IMAGE)"
  fastapi_image="$(get_env_value FASTAPI_IMAGE)"

  preflight_ecr_image "springboot" "$springboot_image"
  preflight_ecr_image "fastapi" "$fastapi_image"

  if [[ ${#MISSING_IMAGES[@]} -gt 0 ]]; then
    printf '[deploy:warn] Missing ECR images:\n' >&2
    local image
    for image in "${MISSING_IMAGES[@]}"; do
      printf '  - %s\n' "$image" >&2
    done
    print_image_push_guidance
    is_non_mutating || die "ECR image preflight failed."
  fi
}

worker_profile_enabled() {
  local queue_enabled
  queue_enabled="$(get_env_value REPORT_REQUEST_QUEUE_ENABLED || true)"
  case "$DEPLOY_WORKER_PROFILE" in
    auto)
      is_truthy "$queue_enabled"
      ;;
    *)
      is_truthy "$DEPLOY_WORKER_PROFILE"
      ;;
  esac
}

compose_args() {
  printf '%s\n' "--env-file"
  printf '%s\n' "$ENV_FILE"
  if worker_profile_enabled; then
    printf '%s\n' "--profile"
    printf '%s\n' "worker"
  fi
  printf '%s\n' "-f"
  printf '%s\n' "$COMPOSE_FILE"
}

print_compose_command() {
  printf 'docker compose'
  local arg
  while IFS= read -r arg; do
    printf ' %q' "$arg"
  done < <(compose_args)
  local extra
  for extra in "$@"; do
    printf ' %q' "$extra"
  done
  printf '\n'
}

docker_compose() {
  local args=()
  local arg
  while IFS= read -r arg; do
    args+=("$arg")
  done < <(compose_args)
  command docker compose "${args[@]}" "$@"
}

ecr_login() {
  local registry="$CALLER_ACCOUNT.dkr.ecr.$AWS_REGION_NAME.amazonaws.com"
  if is_non_mutating; then
    log "Would login to ECR registry: $registry"
    printf '[plan] %s | docker login --username AWS --password-stdin %q\n' \
      "$(quote_aws_cmd ecr get-login-password)" "$registry" >&2
    return
  fi

  log "Logging in to ECR registry: $registry"
  aws_cli ecr get-login-password | docker login --username AWS --password-stdin "$registry" >/dev/null
}

compose_pull_up() {
  if is_non_mutating; then
    log "Would pull production images:"
    printf '[plan] ' >&2
    print_compose_command pull >&2
    log "Would start production services:"
    printf '[plan] ' >&2
    print_compose_command up -d >&2
    if worker_profile_enabled; then
      log "Worker profile would be enabled because REPORT_REQUEST_QUEUE_ENABLED/DEPLOY_WORKER_PROFILE requires it."
    else
      log "Worker profile would stay disabled."
    fi
    return
  fi

  COMPOSE_TOUCHED=true
  log "Pulling production images."
  docker_compose pull
  log "Starting production services."
  docker_compose up -d
}

expected_containers() {
  printf '%s\n' commitgotchi-nginx-prod
  printf '%s\n' commitgotchi-postgres-prod
  printf '%s\n' commitgotchi-springboot-prod
  printf '%s\n' commitgotchi-fastapi-prod
  if worker_profile_enabled; then
    printf '%s\n' commitgotchi-fastapi-report-worker-prod
  fi
}

container_health_line() {
  local container="$1"
  docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}' "$container" 2>/dev/null
}

wait_for_containers() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  local all_ok
  local container
  local line
  local status
  local health

  while (( SECONDS <= deadline )); do
    all_ok=true
    while IFS= read -r container; do
      line="$(container_health_line "$container" || true)"
      status="${line%% *}"
      health="${line#* }"
      if [[ "$status" != "running" || "$health" == "starting" || "$health" == "unhealthy" || -z "$line" ]]; then
        all_ok=false
        log "Waiting for $container (status=${status:-missing}, health=${health:-missing})."
      fi
    done < <(expected_containers)

    if [[ "$all_ok" == "true" ]]; then
      log "Container state/health preflight passed."
      return
    fi
    sleep "$HEALTH_INTERVAL_SECONDS"
  done

  die "Container health check did not pass within ${HEALTH_TIMEOUT_SECONDS}s."
}

http_health_check() {
  if curl -fsS --max-time 8 "$HEALTH_EXTERNAL_URL" >/dev/null; then
    log "External health check passed: $HEALTH_EXTERNAL_URL"
    return
  fi

  warn "External health check failed; trying localhost nginx fallback with --resolve."
  if curl -fsS --max-time 8 --resolve "$DOMAIN:443:127.0.0.1" "$HEALTH_LOCAL_URL" >/dev/null; then
    log "Local nginx health check passed: $HEALTH_LOCAL_URL via 127.0.0.1"
    return
  fi

  die "HTTP health check failed for external and local nginx routes."
}

redact_stream() {
  sed -E 's/((password|secret|token|api[-_]?key|authorization)[^=:{[:space:]]*[=:{][[:space:]]*)[^[:space:],}]+/\1[REDACTED]/Ig'
}

collect_failure_diagnostics() {
  command -v docker >/dev/null 2>&1 || return 0
  [[ -f "$COMPOSE_FILE" ]] || return 0
  log "Container status diagnostics:"
  docker_compose ps 2>&1 | redact_stream >&2 || true
  log "Recent container logs (redacted best-effort):"
  docker_compose logs --no-color --tail "$LOG_TAIL_LINES" 2>&1 | redact_stream >&2 || true
}

on_error() {
  local exit_code=$?
  trap - ERR
  warn "Deploy failed with exit code $exit_code."
  if [[ "$COMPOSE_TOUCHED" == "true" ]] && ! is_non_mutating; then
    collect_failure_diagnostics
  fi
  exit "$exit_code"
}

health_check() {
  if is_non_mutating; then
    log "Would check container states and Dockerfile HEALTHCHECK results for:"
    expected_containers | sed 's/^/[plan]   - /' >&2
    log "Would check HTTP health: $HEALTH_EXTERNAL_URL, then localhost nginx fallback."
    return
  fi

  wait_for_containers
  http_health_check
}

main() {
  parse_args "$@"
  validate_config

  require_command aws
  require_command curl
  if ! is_non_mutating; then
    require_command docker
  fi

  load_caller_identity
  load_env_from_ssm
  write_env_file
  preflight_tls
  preflight_ecr_images
  ecr_login
  compose_pull_up
  health_check

  if [[ ${#PREFLIGHT_WARNINGS[@]} -gt 0 ]]; then
    printf '[deploy:warn] Preflight warnings:\n' >&2
    local warning
    for warning in "${PREFLIGHT_WARNINGS[@]}"; do
      printf '  - %s\n' "$warning" >&2
    done
  fi

  if is_non_mutating; then
    log "Plan completed. No files, Docker services, or AWS resources were changed."
  else
    log "Production deploy completed."
  fi
}

trap on_error ERR
main "$@"
