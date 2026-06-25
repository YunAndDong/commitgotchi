#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_NAME="$(basename "$0")"
PROJECT="commitgotchi"
ENVIRONMENT="prod"
DEFAULT_PROFILE="commitgotchi-bootstrap"
DEFAULT_REGION="ap-northeast-2"
CONFIRM_TOKEN="commitgotchi-prod-bootstrap"

AWS_PROFILE_NAME="${AWS_PROFILE_NAME:-$DEFAULT_PROFILE}"
AWS_REGION_NAME="${AWS_REGION:-${AWS_DEFAULT_REGION:-$DEFAULT_REGION}}"
APPLY=false
CONFIRM_APPLY=""

ECR_REPOSITORIES=("commitgotchi-springboot" "commitgotchi-fastapi")
SQS_QUEUE_NAME="${SQS_QUEUE_NAME:-commitgotchi-prod-report-request}"
SQS_DLQ_NAME="${SQS_DLQ_NAME:-commitgotchi-prod-report-request-dlq}"
SQS_VISIBILITY_TIMEOUT="${SQS_VISIBILITY_TIMEOUT:-900}"
SQS_MESSAGE_RETENTION_SECONDS="${SQS_MESSAGE_RETENTION_SECONDS:-1209600}"
SQS_RECEIVE_WAIT_TIME_SECONDS="${SQS_RECEIVE_WAIT_TIME_SECONDS:-20}"
SQS_MAX_RECEIVE_COUNT="${SQS_MAX_RECEIVE_COUNT:-3}"

S3_BUCKET_NAME="${S3_BUCKET_NAME:-}"
S3_OBJECT_PREFIX="${S3_OBJECT_PREFIX:-prod/}"

EC2_ROLE_NAME="${EC2_ROLE_NAME:-commitgotchi-prod-ec2-role}"
EC2_INSTANCE_PROFILE_NAME="${EC2_INSTANCE_PROFILE_NAME:-commitgotchi-prod-ec2-profile}"
EC2_INLINE_POLICY_NAME="${EC2_INLINE_POLICY_NAME:-commitgotchi-prod-ec2-runtime}"
EC2_SECURITY_GROUP_NAME="${EC2_SECURITY_GROUP_NAME:-commitgotchi-prod-sg}"
EC2_INSTANCE_NAME="${EC2_INSTANCE_NAME:-commitgotchi-prod-app}"
EC2_EIP_NAME="${EC2_EIP_NAME:-commitgotchi-prod-eip}"
EC2_INSTANCE_TYPE="${EC2_INSTANCE_TYPE:-t3.small}"
EC2_VOLUME_SIZE_GB="${EC2_VOLUME_SIZE_GB:-30}"
EC2_AMI_PARAMETER="${EC2_AMI_PARAMETER:-/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64}"
EC2_DOCKER_COMPOSE_VERSION="${EC2_DOCKER_COMPOSE_VERSION:-v2.29.2}"
EC2_KEY_NAME="${EC2_KEY_NAME:-}"
VPC_ID="${VPC_ID:-}"
SUBNET_ID="${SUBNET_ID:-}"
SSH_ALLOWED_CIDR="${SSH_ALLOWED_CIDR:-}"

SSM_PREFIX="${SSM_PREFIX:-/commitgotchi/prod}"
SSM_KMS_KEY_ID="${SSM_KMS_KEY_ID:-}"
DB_USER_VALUE="${DB_USER:-commitgotchi}"
SPRING_DB_NAME_VALUE="${SPRING_DB_NAME:-commitgotchi}"
FASTAPI_DB_NAME_VALUE="${FASTAPI_DB_NAME:-commitgotchi_ai}"
CORS_ALLOWED_ORIGINS_VALUE="${CORS_ALLOWED_ORIGINS:-https://commitgotchi.store}"
CHARACTER_IMAGE_ENABLED_VALUE="${CHARACTER_IMAGE_ENABLED:-true}"
REPORT_REQUEST_QUEUE_ENABLED_VALUE="${REPORT_REQUEST_QUEUE_ENABLED:-false}"
REPORT_REQUEST_DISPATCHER_ENABLED_VALUE="${REPORT_REQUEST_DISPATCHER_ENABLED:-$REPORT_REQUEST_QUEUE_ENABLED_VALUE}"

TMP_FILES=()

usage() {
  cat <<USAGE
Usage:
  $SCRIPT_NAME [--profile commitgotchi-bootstrap] [--region ap-northeast-2]
  $SCRIPT_NAME --apply --confirm-apply $CONFIRM_TOKEN

Default mode is a non-mutating plan: it may run read-only describe/get calls,
but it does not create or update AWS resources.

Options:
  --apply                 Create/update resources idempotently.
  --confirm-apply TOKEN   Required with --apply. Token: $CONFIRM_TOKEN
  --profile NAME          AWS CLI named profile. "default" is rejected.
  --region REGION         AWS region. Default: ap-northeast-2
  -h, --help              Show this help.

Important environment overrides:
  S3_BUCKET_NAME, S3_OBJECT_PREFIX
  SQS_QUEUE_NAME, SQS_DLQ_NAME, SQS_VISIBILITY_TIMEOUT
  VPC_ID, SUBNET_ID, EC2_INSTANCE_TYPE, EC2_DOCKER_COMPOSE_VERSION
  EC2_KEY_NAME, SSH_ALLOWED_CIDR
  SSM_KMS_KEY_ID

Required for --apply SSM SecureString values:
  DB_PASSWORD
  JWT_SECRET_BASE64
  SPRING_INTERNAL_API_SECRET
  GEMINI_API_KEY
USAGE
}

log() {
  printf '[infra-2] %s\n' "$*" >&2
}

plan() {
  printf '[plan] %s\n' "$*" >&2
}

die() {
  printf '[infra-2:error] %s\n' "$*" >&2
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

mutate() {
  local description="$1"
  shift
  if [[ "$APPLY" == "true" ]]; then
    log "$description"
    aws_cli "$@"
  else
    plan "$description"
    quote_aws_cmd "$@" >&2
  fi
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

require_numeric() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || die "$name must be numeric: $value"
}

validate_config() {
  [[ -n "$AWS_PROFILE_NAME" ]] || die "AWS profile is required."
  [[ "$AWS_PROFILE_NAME" != "default" ]] || die "Refusing to use AWS CLI default profile."
  [[ "${AWS_PROFILE:-}" != "default" ]] || die "AWS_PROFILE=default is not allowed for this script."
  [[ "${AWS_DEFAULT_PROFILE:-}" != "default" ]] || die "AWS_DEFAULT_PROFILE=default is not allowed for this script."
  [[ -n "$AWS_REGION_NAME" ]] || die "AWS region is required."
  [[ "$AWS_REGION_NAME" == "$DEFAULT_REGION" ]] || log "Region override in use: $AWS_REGION_NAME"
  [[ "$S3_OBJECT_PREFIX" == */ ]] || die "S3_OBJECT_PREFIX must end with '/': $S3_OBJECT_PREFIX"
  [[ "$SSM_PREFIX" == /* ]] || die "SSM_PREFIX must start with '/': $SSM_PREFIX"
  [[ "$EC2_VOLUME_SIZE_GB" =~ ^[0-9]+$ ]] || die "EC2_VOLUME_SIZE_GB must be numeric."

  require_numeric SQS_VISIBILITY_TIMEOUT "$SQS_VISIBILITY_TIMEOUT"
  require_numeric SQS_MESSAGE_RETENTION_SECONDS "$SQS_MESSAGE_RETENTION_SECONDS"
  require_numeric SQS_RECEIVE_WAIT_TIME_SECONDS "$SQS_RECEIVE_WAIT_TIME_SECONDS"
  require_numeric SQS_MAX_RECEIVE_COUNT "$SQS_MAX_RECEIVE_COUNT"

  if [[ "$APPLY" == "true" && "$CONFIRM_APPLY" != "$CONFIRM_TOKEN" ]]; then
    die "--apply requires --confirm-apply $CONFIRM_TOKEN"
  fi
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

get_text_or_empty() {
  local output
  if ! output="$("$@" 2>/dev/null)"; then
    return 1
  fi
  [[ "$output" == "None" ]] && output=""
  printf '%s' "$output"
}

account_id() {
  aws_cli sts get-caller-identity --query Account --output text
}

account_arn() {
  aws_cli sts get-caller-identity --query Arn --output text
}

resolve_account_scoped_defaults() {
  local acct="$1"
  if [[ -z "$S3_BUCKET_NAME" ]]; then
    S3_BUCKET_NAME="commitgotchi-character-images-$acct"
    log "S3_BUCKET_NAME not set; using account-scoped default: $S3_BUCKET_NAME"
  fi
}

ensure_ecr_repository() {
  local repo="$1"
  local repo_uri
  repo_uri="$(get_text_or_empty aws_cli ecr describe-repositories \
    --repository-names "$repo" \
    --query 'repositories[0].repositoryUri' \
    --output text || true)"

  if [[ -n "$repo_uri" ]]; then
    log "ECR repository exists: $repo"
    printf '%s' "$repo_uri"
    return
  fi

  if [[ "$APPLY" == "true" ]]; then
    repo_uri="$(aws_cli ecr create-repository \
      --repository-name "$repo" \
      --image-scanning-configuration scanOnPush=true \
      --encryption-configuration encryptionType=AES256 \
      --tags "Key=Project,Value=$PROJECT" "Key=Environment,Value=$ENVIRONMENT" \
      --query 'repository.repositoryUri' \
      --output text)"
    log "ECR repository created: $repo"
    printf '%s' "$repo_uri"
  else
    local acct
    acct="$(account_id)"
    plan "create ECR repository: $repo"
    quote_aws_cmd ecr create-repository \
      --repository-name "$repo" \
      --image-scanning-configuration scanOnPush=true \
      --encryption-configuration encryptionType=AES256 \
      --tags "Key=Project,Value=$PROJECT" "Key=Environment,Value=$ENVIRONMENT" >&2
    printf '%s.dkr.ecr.%s.amazonaws.com/%s' "$acct" "$AWS_REGION_NAME" "$repo"
  fi
}

ensure_sqs_queue() {
  local queue_name="$1"
  local attributes_file="$2"
  local queue_url
  queue_url="$(get_text_or_empty aws_cli sqs get-queue-url \
    --queue-name "$queue_name" \
    --query QueueUrl \
    --output text || true)"

  if [[ -n "$queue_url" ]]; then
    log "SQS queue exists: $queue_name"
  elif [[ "$APPLY" == "true" ]]; then
    queue_url="$(aws_cli sqs create-queue \
      --queue-name "$queue_name" \
      --attributes "file://$attributes_file" \
      --tags "Project=$PROJECT,Environment=$ENVIRONMENT" \
      --query QueueUrl \
      --output text)"
    log "SQS queue created: $queue_name"
  else
    local acct
    acct="$(account_id)"
    plan "create SQS queue: $queue_name"
    quote_aws_cmd sqs create-queue \
      --queue-name "$queue_name" \
      --attributes "file://$attributes_file" \
      --tags "Project=$PROJECT,Environment=$ENVIRONMENT" >&2
    queue_url="https://sqs.${AWS_REGION_NAME}.amazonaws.com/${acct}/${queue_name}"
  fi

  mutate_quiet "ensure SQS attributes for $queue_name" \
    sqs set-queue-attributes \
    --queue-url "$queue_url" \
    --attributes "file://$attributes_file"

  printf '%s' "$queue_url"
}

queue_arn_for_url() {
  local queue_url="$1"
  local queue_name="$2"
  local arn
  arn="$(get_text_or_empty aws_cli sqs get-queue-attributes \
    --queue-url "$queue_url" \
    --attribute-names QueueArn \
    --query 'Attributes.QueueArn' \
    --output text || true)"
  if [[ -n "$arn" ]]; then
    printf '%s' "$arn"
    return
  fi

  local acct
  acct="$(account_id)"
  printf 'arn:aws:sqs:%s:%s:%s' "$AWS_REGION_NAME" "$acct" "$queue_name"
}

ensure_sqs() {
  local dlq_attrs
  dlq_attrs="$(make_tmp_file)"
  cat >"$dlq_attrs" <<JSON
{
  "VisibilityTimeout": "$SQS_VISIBILITY_TIMEOUT",
  "MessageRetentionPeriod": "$SQS_MESSAGE_RETENTION_SECONDS",
  "ReceiveMessageWaitTimeSeconds": "$SQS_RECEIVE_WAIT_TIME_SECONDS"
}
JSON

  local dlq_url
  local dlq_arn
  dlq_url="$(ensure_sqs_queue "$SQS_DLQ_NAME" "$dlq_attrs")"
  dlq_arn="$(queue_arn_for_url "$dlq_url" "$SQS_DLQ_NAME")"

  local queue_attrs
  queue_attrs="$(make_tmp_file)"
  cat >"$queue_attrs" <<JSON
{
  "VisibilityTimeout": "$SQS_VISIBILITY_TIMEOUT",
  "MessageRetentionPeriod": "$SQS_MESSAGE_RETENTION_SECONDS",
  "ReceiveMessageWaitTimeSeconds": "$SQS_RECEIVE_WAIT_TIME_SECONDS",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$dlq_arn\",\"maxReceiveCount\":\"$SQS_MAX_RECEIVE_COUNT\"}"
}
JSON

  local queue_url
  local queue_arn
  queue_url="$(ensure_sqs_queue "$SQS_QUEUE_NAME" "$queue_attrs")"
  queue_arn="$(queue_arn_for_url "$queue_url" "$SQS_QUEUE_NAME")"

  SQS_QUEUE_URL_RESULT="$queue_url"
  SQS_QUEUE_ARN_RESULT="$queue_arn"
  SQS_DLQ_URL_RESULT="$dlq_url"
  SQS_DLQ_ARN_RESULT="$dlq_arn"
}

ensure_s3_bucket() {
  if aws_cli s3api head-bucket --bucket "$S3_BUCKET_NAME" >/dev/null 2>&1; then
    log "S3 bucket exists and is accessible: $S3_BUCKET_NAME"
  elif [[ "$APPLY" == "true" ]]; then
    aws_cli s3api create-bucket \
      --bucket "$S3_BUCKET_NAME" \
      --create-bucket-configuration "LocationConstraint=$AWS_REGION_NAME" >/dev/null
    log "S3 bucket created: $S3_BUCKET_NAME"
  else
    plan "create S3 bucket: $S3_BUCKET_NAME"
    quote_aws_cmd s3api create-bucket \
      --bucket "$S3_BUCKET_NAME" \
      --create-bucket-configuration "LocationConstraint=$AWS_REGION_NAME" >&2
  fi

  mutate_quiet "block all public access on S3 bucket $S3_BUCKET_NAME" \
    s3api put-public-access-block \
    --bucket "$S3_BUCKET_NAME" \
    --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

  mutate_quiet "tag S3 bucket $S3_BUCKET_NAME" \
    s3api put-bucket-tagging \
    --bucket "$S3_BUCKET_NAME" \
    --tagging "TagSet=[{Key=Project,Value=$PROJECT},{Key=Environment,Value=shared}]"
}

ensure_ec2_role_and_profile() {
  local acct="$1"
  local queue_arn="$2"
  local dlq_arn="$3"
  local ssm_path="${SSM_PREFIX#/}"
  local trust_policy
  trust_policy="$(make_tmp_file)"
  cat >"$trust_policy" <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
JSON

  if aws_cli iam get-role --role-name "$EC2_ROLE_NAME" >/dev/null 2>&1; then
    log "IAM role exists: $EC2_ROLE_NAME"
  else
    mutate_quiet "create IAM role $EC2_ROLE_NAME" \
      iam create-role \
      --role-name "$EC2_ROLE_NAME" \
      --assume-role-policy-document "file://$trust_policy" \
      --tags "Key=Project,Value=$PROJECT" "Key=Environment,Value=$ENVIRONMENT"
  fi

  mutate_quiet "attach AmazonSSMManagedInstanceCore to $EC2_ROLE_NAME" \
    iam attach-role-policy \
    --role-name "$EC2_ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore

  mutate_quiet "attach AmazonEC2ContainerRegistryReadOnly to $EC2_ROLE_NAME" \
    iam attach-role-policy \
    --role-name "$EC2_ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly

  local inline_policy
  inline_policy="$(make_tmp_file)"
  cat >"$inline_policy" <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadCommitgotchiProdParameters",
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameter",
        "ssm:GetParameters",
        "ssm:GetParametersByPath"
      ],
      "Resource": "arn:aws:ssm:$AWS_REGION_NAME:$acct:parameter/$ssm_path/*"
    },
    {
      "Sid": "DecryptSsmSecureStrings",
      "Effect": "Allow",
      "Action": "kms:Decrypt",
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "kms:ViaService": "ssm.$AWS_REGION_NAME.amazonaws.com",
          "kms:CallerAccount": "$acct"
        }
      }
    },
    {
      "Sid": "UseReportQueues",
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:ChangeMessageVisibility",
        "sqs:GetQueueAttributes",
        "sqs:GetQueueUrl"
      ],
      "Resource": [
        "$queue_arn",
        "$dlq_arn"
      ]
    },
    {
      "Sid": "ListCharacterImageBucketProdPrefix",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::$S3_BUCKET_NAME",
      "Condition": {
        "StringLike": {
          "s3:prefix": [
            "$S3_OBJECT_PREFIX*"
          ]
        }
      }
    },
    {
      "Sid": "WriteCharacterImagesProdPrefix",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::$S3_BUCKET_NAME/$S3_OBJECT_PREFIX*"
    }
  ]
}
JSON

  mutate_quiet "put inline runtime policy $EC2_INLINE_POLICY_NAME on $EC2_ROLE_NAME" \
    iam put-role-policy \
    --role-name "$EC2_ROLE_NAME" \
    --policy-name "$EC2_INLINE_POLICY_NAME" \
    --policy-document "file://$inline_policy"

  if aws_cli iam get-instance-profile --instance-profile-name "$EC2_INSTANCE_PROFILE_NAME" >/dev/null 2>&1; then
    log "IAM instance profile exists: $EC2_INSTANCE_PROFILE_NAME"
  else
    mutate_quiet "create IAM instance profile $EC2_INSTANCE_PROFILE_NAME" \
      iam create-instance-profile \
      --instance-profile-name "$EC2_INSTANCE_PROFILE_NAME" \
      --tags "Key=Project,Value=$PROJECT" "Key=Environment,Value=$ENVIRONMENT"
  fi

  local profile_roles
  profile_roles="$(get_text_or_empty aws_cli iam get-instance-profile \
    --instance-profile-name "$EC2_INSTANCE_PROFILE_NAME" \
    --query 'InstanceProfile.Roles[].RoleName' \
    --output text || true)"
  if [[ " $profile_roles " == *" $EC2_ROLE_NAME "* ]]; then
    log "IAM role is already attached to instance profile: $EC2_ROLE_NAME"
  else
    mutate_quiet "add $EC2_ROLE_NAME to instance profile $EC2_INSTANCE_PROFILE_NAME" \
      iam add-role-to-instance-profile \
      --instance-profile-name "$EC2_INSTANCE_PROFILE_NAME" \
      --role-name "$EC2_ROLE_NAME"
    if [[ "$APPLY" == "true" ]]; then
      log "Waiting briefly for IAM instance profile propagation."
      sleep 10
    fi
  fi
}

default_vpc_id() {
  if [[ -n "$VPC_ID" ]]; then
    printf '%s' "$VPC_ID"
    return
  fi

  aws_cli ec2 describe-vpcs \
    --filters Name=is-default,Values=true \
    --query 'Vpcs[0].VpcId' \
    --output text
}

default_subnet_id() {
  if [[ -n "$SUBNET_ID" ]]; then
    printf '%s' "$SUBNET_ID"
    return
  fi

  local vpc_id="$1"
  aws_cli ec2 describe-subnets \
    --filters "Name=vpc-id,Values=$vpc_id" Name=default-for-az,Values=true \
    --query 'Subnets | sort_by(@, &AvailabilityZone)[0].SubnetId' \
    --output text
}

ensure_security_group() {
  local vpc_id="$1"
  local sg_id
  sg_id="$(get_text_or_empty aws_cli ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=$vpc_id" "Name=group-name,Values=$EC2_SECURITY_GROUP_NAME" \
    --query 'SecurityGroups[0].GroupId' \
    --output text || true)"

  if [[ -n "$sg_id" ]]; then
    log "EC2 security group exists: $EC2_SECURITY_GROUP_NAME ($sg_id)"
  elif [[ "$APPLY" == "true" ]]; then
    sg_id="$(aws_cli ec2 create-security-group \
      --group-name "$EC2_SECURITY_GROUP_NAME" \
      --description "Commitgotchi prod API ingress" \
      --vpc-id "$vpc_id" \
      --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=$EC2_SECURITY_GROUP_NAME},{Key=Project,Value=$PROJECT},{Key=Environment,Value=$ENVIRONMENT}]" \
      --query GroupId \
      --output text)"
    log "EC2 security group created: $EC2_SECURITY_GROUP_NAME ($sg_id)"
  else
    plan "create EC2 security group: $EC2_SECURITY_GROUP_NAME"
    quote_aws_cmd ec2 create-security-group \
      --group-name "$EC2_SECURITY_GROUP_NAME" \
      --description "Commitgotchi prod API ingress" \
      --vpc-id "$vpc_id" \
      --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=$EC2_SECURITY_GROUP_NAME},{Key=Project,Value=$PROJECT},{Key=Environment,Value=$ENVIRONMENT}]" >&2
    sg_id="sg-created-by-apply"
  fi

  ensure_ingress_rule "$sg_id" 80 0.0.0.0/0 "HTTP public ingress for Certbot and redirect"
  ensure_ingress_rule "$sg_id" 443 0.0.0.0/0 "HTTPS public ingress"
  if [[ -n "$SSH_ALLOWED_CIDR" ]]; then
    ensure_ingress_rule "$sg_id" 22 "$SSH_ALLOWED_CIDR" "Optional SSH ingress"
  else
    log "SSH ingress remains closed. Use SSM Session Manager unless SSH_ALLOWED_CIDR is set."
  fi

  printf '%s' "$sg_id"
}

ensure_ingress_rule() {
  local sg_id="$1"
  local port="$2"
  local cidr="$3"
  local description="$4"

  local exists
  exists="$(get_text_or_empty aws_cli ec2 describe-security-groups \
    --group-ids "$sg_id" \
    --query "SecurityGroups[0].IpPermissions[?FromPort==\`${port}\` && ToPort==\`${port}\` && IpProtocol=='tcp'].IpRanges[?CidrIp=='${cidr}'] | [0][0].CidrIp" \
    --output text || true)"
  if [[ "$exists" == "$cidr" ]]; then
    log "Security group ingress exists: tcp/$port $cidr"
    return
  fi

  local ip_permissions
  ip_permissions="$(make_tmp_file)"
  cat >"$ip_permissions" <<JSON
[
  {
    "IpProtocol": "tcp",
    "FromPort": $port,
    "ToPort": $port,
    "IpRanges": [
      {
        "CidrIp": "$cidr",
        "Description": "$description"
      }
    ]
  }
]
JSON

  mutate_quiet "authorize security group ingress tcp/$port $cidr" \
    ec2 authorize-security-group-ingress \
    --group-id "$sg_id" \
    --ip-permissions "file://$ip_permissions"
}

resolve_ami_id() {
  aws_cli ssm get-parameter \
    --name "$EC2_AMI_PARAMETER" \
    --query 'Parameter.Value' \
    --output text
}

user_data_file() {
  local file
  file="$(make_tmp_file)"
  cat >"$file" <<USERDATA
#!/usr/bin/env bash
set -Eeuo pipefail
dnf update -y
dnf install -y docker git amazon-ssm-agent curl
systemctl enable --now amazon-ssm-agent
systemctl enable --now docker

if ! docker compose version >/dev/null 2>&1; then
  if ! dnf install -y docker-compose-plugin; then
    compose_version="$EC2_DOCKER_COMPOSE_VERSION"
    arch="\$(uname -m)"
    case "\$arch" in
      x86_64)
        compose_arch="x86_64"
        ;;
      aarch64|arm64)
        compose_arch="aarch64"
        ;;
      *)
        echo "Unsupported architecture for docker compose plugin: \$arch" >&2
        exit 1
        ;;
    esac
    install -d /usr/local/lib/docker/cli-plugins
    curl -fsSL \
      "https://github.com/docker/compose/releases/download/\${compose_version}/docker-compose-linux-\${compose_arch}" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
    ln -sf /usr/local/lib/docker/cli-plugins/docker-compose /usr/bin/docker-compose
  fi
fi
docker compose version

usermod -aG docker ec2-user || true
mkdir -p /opt/commitgotchi
chown ec2-user:ec2-user /opt/commitgotchi
USERDATA
  printf '%s' "$file"
}

ensure_ec2_instance() {
  local subnet_id="$1"
  local sg_id="$2"
  local ami_id="$3"
  local instance_id
  instance_id="$(get_text_or_empty aws_cli ec2 describe-instances \
    --filters \
      "Name=tag:Name,Values=$EC2_INSTANCE_NAME" \
      "Name=tag:Project,Values=$PROJECT" \
      "Name=tag:Environment,Values=$ENVIRONMENT" \
      "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[0].Instances[0].InstanceId' \
    --output text || true)"

  if [[ -n "$instance_id" ]]; then
    log "EC2 instance exists: $EC2_INSTANCE_NAME ($instance_id)"
    printf '%s' "$instance_id"
    return
  fi

  local user_data
  user_data="$(user_data_file)"
  local block_device
  block_device="DeviceName=/dev/xvda,Ebs={VolumeSize=$EC2_VOLUME_SIZE_GB,VolumeType=gp3,DeleteOnTermination=true,Encrypted=true}"
  local run_args=(
    ec2 run-instances
    --image-id "$ami_id"
    --instance-type "$EC2_INSTANCE_TYPE"
    --iam-instance-profile "Name=$EC2_INSTANCE_PROFILE_NAME"
    --subnet-id "$subnet_id"
    --security-group-ids "$sg_id"
    --block-device-mappings "$block_device"
    --user-data "file://$user_data"
    --tag-specifications
    "ResourceType=instance,Tags=[{Key=Name,Value=$EC2_INSTANCE_NAME},{Key=Project,Value=$PROJECT},{Key=Environment,Value=$ENVIRONMENT}]"
    "ResourceType=volume,Tags=[{Key=Name,Value=$EC2_INSTANCE_NAME-root},{Key=Project,Value=$PROJECT},{Key=Environment,Value=$ENVIRONMENT}]"
    --query 'Instances[0].InstanceId'
    --output text
  )

  if [[ -n "$EC2_KEY_NAME" ]]; then
    run_args+=(--key-name "$EC2_KEY_NAME")
  fi

  if [[ "$APPLY" == "true" ]]; then
    instance_id="$(aws_cli "${run_args[@]}")"
    log "EC2 instance created: $EC2_INSTANCE_NAME ($instance_id)"
    aws_cli ec2 wait instance-running --instance-ids "$instance_id"
    printf '%s' "$instance_id"
  else
    plan "run EC2 instance: $EC2_INSTANCE_NAME"
    quote_aws_cmd "${run_args[@]}" >&2
    printf '%s' "i-created-by-apply"
  fi
}

ensure_eip() {
  local instance_id="$1"
  local allocation_id
  local public_ip
  allocation_id="$(get_text_or_empty aws_cli ec2 describe-addresses \
    --filters "Name=tag:Name,Values=$EC2_EIP_NAME" "Name=domain,Values=vpc" \
    --query 'Addresses[0].AllocationId' \
    --output text || true)"

  if [[ -n "$allocation_id" ]]; then
    log "Elastic IP allocation exists: $EC2_EIP_NAME ($allocation_id)"
  elif [[ "$APPLY" == "true" ]]; then
    allocation_id="$(aws_cli ec2 allocate-address \
      --domain vpc \
      --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$EC2_EIP_NAME},{Key=Project,Value=$PROJECT},{Key=Environment,Value=$ENVIRONMENT}]" \
      --query AllocationId \
      --output text)"
    log "Elastic IP allocated: $EC2_EIP_NAME ($allocation_id)"
  else
    plan "allocate Elastic IP: $EC2_EIP_NAME"
    quote_aws_cmd ec2 allocate-address \
      --domain vpc \
      --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$EC2_EIP_NAME},{Key=Project,Value=$PROJECT},{Key=Environment,Value=$ENVIRONMENT}]" >&2
    allocation_id="eipalloc-created-by-apply"
  fi

  local associated_instance
  associated_instance="$(get_text_or_empty aws_cli ec2 describe-addresses \
    --allocation-ids "$allocation_id" \
    --query 'Addresses[0].InstanceId' \
    --output text || true)"
  if [[ "$associated_instance" == "$instance_id" ]]; then
    log "Elastic IP is already associated with instance: $instance_id"
  else
    mutate_quiet "associate Elastic IP $allocation_id to $instance_id" \
      ec2 associate-address \
      --allocation-id "$allocation_id" \
      --instance-id "$instance_id" \
      --allow-reassociation
  fi

  public_ip="$(get_text_or_empty aws_cli ec2 describe-addresses \
    --allocation-ids "$allocation_id" \
    --query 'Addresses[0].PublicIp' \
    --output text || true)"
  if [[ -z "$public_ip" ]]; then
    public_ip="allocated-by-apply"
  fi

  EC2_EIP_ALLOCATION_ID_RESULT="$allocation_id"
  EC2_EIP_PUBLIC_IP_RESULT="$public_ip"
}

secret_value() {
  local env_name="$1"
  local current_value="${!env_name:-}"
  if [[ -n "$current_value" ]]; then
    printf '%s' "$current_value"
    return
  fi

  if [[ "$APPLY" != "true" ]]; then
    printf '<redacted:%s>' "$env_name"
    return
  fi

  if [[ -t 0 ]]; then
    printf '%s: ' "$env_name" >&2
    stty -echo
    IFS= read -r current_value
    stty echo
    printf '\n' >&2
    [[ -n "$current_value" ]] || die "$env_name is required for --apply."
    printf '%s' "$current_value"
    return
  fi

  die "$env_name is required for --apply."
}

put_ssm_parameter() {
  local name="$1"
  local type="$2"
  local value="$3"

  if [[ "$APPLY" != "true" ]]; then
    if [[ "$type" == "SecureString" ]]; then
      plan "put SSM $type parameter $name with redacted value"
    else
      plan "put SSM $type parameter $name=$value"
    fi
    return
  fi

  local args=(
    ssm put-parameter
    --name "$name"
    --type "$type"
    --value "$value"
    --overwrite
  )
  if [[ "$type" == "SecureString" && -n "$SSM_KMS_KEY_ID" ]]; then
    args+=(--key-id "$SSM_KMS_KEY_ID")
  fi

  log "put SSM $type parameter $name"
  aws_cli "${args[@]}" >/dev/null
}

put_ssm_parameters() {
  local account="$1"
  local springboot_repo_uri="$2"
  local fastapi_repo_uri="$3"
  local instance_id="$4"
  local sg_id="$5"
  local vpc_id="$6"
  local subnet_id="$7"

  put_ssm_parameter "$SSM_PREFIX/db/DB_USER" String "$DB_USER_VALUE"
  put_ssm_parameter "$SSM_PREFIX/db/SPRING_DB_NAME" String "$SPRING_DB_NAME_VALUE"
  put_ssm_parameter "$SSM_PREFIX/db/FASTAPI_DB_NAME" String "$FASTAPI_DB_NAME_VALUE"
  put_ssm_parameter "$SSM_PREFIX/db/DB_PASSWORD" SecureString "$(secret_value DB_PASSWORD)"

  put_ssm_parameter "$SSM_PREFIX/spring/JWT_SECRET_BASE64" SecureString "$(secret_value JWT_SECRET_BASE64)"
  put_ssm_parameter "$SSM_PREFIX/spring/JWT_ISSUER" String "commitgotchi-springboot"
  put_ssm_parameter "$SSM_PREFIX/spring/CORS_ALLOWED_ORIGINS" String "$CORS_ALLOWED_ORIGINS_VALUE"
  put_ssm_parameter "$SSM_PREFIX/spring/SPRING_INTERNAL_API_SECRET" SecureString "$(secret_value SPRING_INTERNAL_API_SECRET)"
  put_ssm_parameter "$SSM_PREFIX/spring/CHARACTER_IMAGE_ENABLED" String "$CHARACTER_IMAGE_ENABLED_VALUE"
  put_ssm_parameter "$SSM_PREFIX/spring/REPORT_REQUEST_QUEUE_ENABLED" String "$REPORT_REQUEST_QUEUE_ENABLED_VALUE"
  put_ssm_parameter "$SSM_PREFIX/spring/REPORT_REQUEST_DISPATCHER_ENABLED" String "$REPORT_REQUEST_DISPATCHER_ENABLED_VALUE"

  put_ssm_parameter "$SSM_PREFIX/fastapi/GEMINI_API_KEY" SecureString "$(secret_value GEMINI_API_KEY)"
  put_ssm_parameter "$SSM_PREFIX/fastapi/AWS_REGION" String "$AWS_REGION_NAME"

  put_ssm_parameter "$SSM_PREFIX/shared/AWS_ACCOUNT_ID" String "$account"
  put_ssm_parameter "$SSM_PREFIX/shared/AWS_REGION" String "$AWS_REGION_NAME"
  put_ssm_parameter "$SSM_PREFIX/shared/S3_BUCKET_NAME" String "$S3_BUCKET_NAME"
  put_ssm_parameter "$SSM_PREFIX/shared/S3_OBJECT_PREFIX" String "$S3_OBJECT_PREFIX"
  put_ssm_parameter "$SSM_PREFIX/shared/REPORT_REQUEST_QUEUE_NAME" String "$SQS_QUEUE_NAME"
  put_ssm_parameter "$SSM_PREFIX/shared/REPORT_REQUEST_QUEUE_URL" String "$SQS_QUEUE_URL_RESULT"
  put_ssm_parameter "$SSM_PREFIX/shared/REPORT_REQUEST_QUEUE_ARN" String "$SQS_QUEUE_ARN_RESULT"
  put_ssm_parameter "$SSM_PREFIX/shared/REPORT_REQUEST_DLQ_NAME" String "$SQS_DLQ_NAME"
  put_ssm_parameter "$SSM_PREFIX/shared/REPORT_REQUEST_DLQ_URL" String "$SQS_DLQ_URL_RESULT"
  put_ssm_parameter "$SSM_PREFIX/shared/REPORT_REQUEST_DLQ_ARN" String "$SQS_DLQ_ARN_RESULT"

  put_ssm_parameter "$SSM_PREFIX/deploy/SPRINGBOOT_IMAGE" String "$springboot_repo_uri:prod"
  put_ssm_parameter "$SSM_PREFIX/deploy/FASTAPI_IMAGE" String "$fastapi_repo_uri:prod"

  put_ssm_parameter "$SSM_PREFIX/infra/EC2_INSTANCE_ID" String "$instance_id"
  put_ssm_parameter "$SSM_PREFIX/infra/EC2_SECURITY_GROUP_ID" String "$sg_id"
  put_ssm_parameter "$SSM_PREFIX/infra/EC2_IAM_ROLE_NAME" String "$EC2_ROLE_NAME"
  put_ssm_parameter "$SSM_PREFIX/infra/EC2_INSTANCE_PROFILE_NAME" String "$EC2_INSTANCE_PROFILE_NAME"
  put_ssm_parameter "$SSM_PREFIX/infra/EC2_EIP_ALLOCATION_ID" String "$EC2_EIP_ALLOCATION_ID_RESULT"
  put_ssm_parameter "$SSM_PREFIX/infra/EC2_PUBLIC_IP" String "$EC2_EIP_PUBLIC_IP_RESULT"
  put_ssm_parameter "$SSM_PREFIX/infra/VPC_ID" String "$vpc_id"
  put_ssm_parameter "$SSM_PREFIX/infra/SUBNET_ID" String "$subnet_id"
}

main() {
  parse_args "$@"
  validate_config
  require_command aws

  log "Mode: $([[ "$APPLY" == "true" ]] && printf 'apply' || printf 'plan')"
  log "AWS profile: $AWS_PROFILE_NAME"
  log "AWS region: $AWS_REGION_NAME"
  log "Caller ARN: $(account_arn)"

  local account
  account="$(account_id)"
  resolve_account_scoped_defaults "$account"

  local springboot_repo_uri=""
  local fastapi_repo_uri=""
  local repo
  for repo in "${ECR_REPOSITORIES[@]}"; do
    case "$repo" in
      commitgotchi-springboot)
        springboot_repo_uri="$(ensure_ecr_repository "$repo")"
        ;;
      commitgotchi-fastapi)
        fastapi_repo_uri="$(ensure_ecr_repository "$repo")"
        ;;
      *)
        ensure_ecr_repository "$repo" >/dev/null
        ;;
    esac
  done

  ensure_sqs
  ensure_s3_bucket
  ensure_ec2_role_and_profile "$account" "$SQS_QUEUE_ARN_RESULT" "$SQS_DLQ_ARN_RESULT"

  local vpc_id
  local subnet_id
  local sg_id
  local ami_id
  local instance_id
  vpc_id="$(default_vpc_id)"
  [[ -n "$vpc_id" && "$vpc_id" != "None" ]] || die "No VPC_ID provided and no default VPC found."
  subnet_id="$(default_subnet_id "$vpc_id")"
  [[ -n "$subnet_id" && "$subnet_id" != "None" ]] || die "No SUBNET_ID provided and no default subnet found for $vpc_id."
  sg_id="$(ensure_security_group "$vpc_id")"
  ami_id="$(resolve_ami_id)"
  instance_id="$(ensure_ec2_instance "$subnet_id" "$sg_id" "$ami_id")"
  ensure_eip "$instance_id"

  put_ssm_parameters "$account" "$springboot_repo_uri" "$fastapi_repo_uri" "$instance_id" "$sg_id" "$vpc_id" "$subnet_id"

  log "Completed $([[ "$APPLY" == "true" ]] && printf 'apply' || printf 'plan') for INFRA-2."
  if [[ "$APPLY" != "true" ]]; then
    log "No AWS resources were created or changed. Re-run with --apply --confirm-apply $CONFIRM_TOKEN only after final approval."
  else
    log "Register commitgotchi.store A record in Gabia DNS with EIP: $EC2_EIP_PUBLIC_IP_RESULT"
  fi
}

main "$@"
