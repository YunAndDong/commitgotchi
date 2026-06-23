#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

OUTPUT_PATH="${OUTPUT_PATH:-$PROJECT_ROOT/deploy-bundle.tar.gz}"
BUNDLE_COMMIT="${BUNDLE_COMMIT:-}"
BUNDLE_BUCKET="${BUNDLE_BUCKET:-commitgotchi-character-images-491013322019}"
BUNDLE_PREFIX="${BUNDLE_PREFIX:-prod/deploy-bundles}"

usage() {
  cat <<USAGE
Usage:
  $SCRIPT_NAME [--output deploy-bundle.tar.gz] [--commit COMMIT_SHA]

Creates a minimal production deploy bundle for extraction at /opt/commitgotchi.
The script does not upload to S3 or mutate AWS resources.

Options:
  --output PATH      Bundle output path. Default: ./deploy-bundle.tar.gz.
  --commit SHA      Commit path segment for the printed S3 URI. Default: HEAD.
  -h, --help        Show this help.

Environment overrides:
  OUTPUT_PATH, BUNDLE_COMMIT, BUNDLE_BUCKET, BUNDLE_PREFIX
USAGE
}

log() {
  printf '[bundle] %s\n' "$*" >&2
}

warn() {
  printf '[bundle:warn] %s\n' "$*" >&2
}

die() {
  printf '[bundle:error] %s\n' "$*" >&2
  exit 1
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --output)
        [[ $# -ge 2 ]] || die "--output requires a value."
        OUTPUT_PATH="$2"
        shift 2
        ;;
      --commit)
        [[ $# -ge 2 ]] || die "--commit requires a value."
        BUNDLE_COMMIT="$2"
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

require_file() {
  local path="$1"
  [[ -f "$PROJECT_ROOT/$path" ]] || die "Required bundle file not found: $path"
}

require_dir() {
  local path="$1"
  [[ -d "$PROJECT_ROOT/$path" ]] || die "Required bundle directory not found: $path"
}

resolve_commit() {
  if [[ -n "$BUNDLE_COMMIT" ]]; then
    return
  fi

  command -v git >/dev/null 2>&1 || die "git is required to resolve the bundle commit."
  BUNDLE_COMMIT="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"

  if [[ -n "$(git -C "$PROJECT_ROOT" status --porcelain)" ]]; then
    warn "Workspace has uncommitted changes; the printed S3 URI uses HEAD: $BUNDLE_COMMIT"
  fi
}

validate_bundle_inputs() {
  require_file "docker-compose.prod.yml"
  require_file "nginx/api-only.conf"
  require_dir "postgres/init"
  require_file "scripts/deploy.sh"
  require_file "scripts/bootstrap-tls.sh"

  [[ "$BUNDLE_PREFIX" == prod/* ]] || warn "BUNDLE_PREFIX is outside prod/: $BUNDLE_PREFIX"
  [[ -n "$BUNDLE_BUCKET" ]] || die "BUNDLE_BUCKET is required."
  [[ -n "$BUNDLE_COMMIT" ]] || die "BUNDLE_COMMIT is required."
}

make_bundle() {
  local output_dir
  output_dir="$(dirname "$OUTPUT_PATH")"
  mkdir -p "$output_dir"

  local bundle_paths=(
    "docker-compose.prod.yml"
    "nginx/api-only.conf"
    "postgres/init"
    "scripts/deploy.sh"
    "scripts/bootstrap-tls.sh"
  )

  rm -f "$OUTPUT_PATH"
  COPYFILE_DISABLE=1 tar -czf "$OUTPUT_PATH" -C "$PROJECT_ROOT" "${bundle_paths[@]}"
}

double_quote() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '"%s"' "$value"
}

print_handoff() {
  local s3_uri="s3://$BUNDLE_BUCKET/$BUNDLE_PREFIX/$BUNDLE_COMMIT/deploy-bundle.tar.gz"

  log "Wrote bundle: $OUTPUT_PATH"
  log "Intended S3 location: $s3_uri"
  log "Upload command, after operator approval:"
  printf '  aws s3 cp ' >&2
  double_quote "$OUTPUT_PATH" >&2
  printf ' ' >&2
  double_quote "$s3_uri" >&2
  printf '\n' >&2
  log "EC2 extraction target: /opt/commitgotchi"
}

main() {
  parse_args "$@"
  resolve_commit
  validate_bundle_inputs
  make_bundle
  print_handoff
}

main "$@"
