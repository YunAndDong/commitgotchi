#!/usr/bin/env bash
set +o xtrace
set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_NAME="$(basename "$0")"
DOMAIN="${DOMAIN:-commitgotchi.store}"
EMAIL="${LETSENCRYPT_EMAIL:-}"
CERTBOT_BIN="${CERTBOT_BIN:-certbot}"
EXPECTED_PUBLIC_IP="${EXPECTED_PUBLIC_IP:-}"
LETSENCRYPT_DIR="${LETSENCRYPT_DIR:-/etc/letsencrypt}"
APPLY=false
NO_EMAIL=false
USE_STAGING=false
FORCE=false

usage() {
  cat <<USAGE
Usage:
  $SCRIPT_NAME [--plan|--dry-run]
  $SCRIPT_NAME --apply --email ops@example.com

Bootstraps the first Let's Encrypt certificate on the EC2 host before nginx
compose starts. Default mode is read-only and never runs certbot.

Options:
  --apply                 Actually run certbot certonly --standalone.
  --plan, --dry-run       Read-only preview. This is the default.
  --domain DOMAIN         Domain to issue. Default: commitgotchi.store.
  --email EMAIL           Let's Encrypt registration email.
  --no-email              Pass --register-unsafely-without-email.
  --staging               Use Let's Encrypt staging endpoint.
  --force                 Continue even if a cert already exists.
  --expected-ip IP        Warn if DNS does not currently resolve to this IP.
  -h, --help              Show this help.

Environment overrides:
  DOMAIN, LETSENCRYPT_EMAIL, CERTBOT_BIN, LETSENCRYPT_DIR, EXPECTED_PUBLIC_IP

This script does not edit crontab or systemd timers. Renewal should be reviewed
and enabled separately after the initial certificate is verified.
USAGE
}

log() {
  printf '[tls] %s\n' "$*" >&2
}

warn() {
  printf '[tls:warn] %s\n' "$*" >&2
}

die() {
  printf '[tls:error] %s\n' "$*" >&2
  exit 1
}

is_non_mutating() {
  [[ "$APPLY" != "true" ]]
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
      --plan|--dry-run)
        APPLY=false
        shift
        ;;
      --domain)
        [[ $# -ge 2 ]] || die "--domain requires a value."
        DOMAIN="$2"
        shift 2
        ;;
      --email)
        [[ $# -ge 2 ]] || die "--email requires a value."
        EMAIL="$2"
        shift 2
        ;;
      --no-email)
        NO_EMAIL=true
        shift
        ;;
      --staging)
        USE_STAGING=true
        shift
        ;;
      --force)
        FORCE=true
        shift
        ;;
      --expected-ip)
        [[ $# -ge 2 ]] || die "--expected-ip requires a value."
        EXPECTED_PUBLIC_IP="$2"
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
  [[ -n "$DOMAIN" ]] || die "DOMAIN is required."
  if [[ "$APPLY" == "true" ]]; then
    if [[ "$NO_EMAIL" != "true" && -z "$EMAIL" ]]; then
      die "--apply requires --email EMAIL, or explicit --no-email."
    fi
    require_command "$CERTBOT_BIN"
  fi
}

file_exists_maybe_sudo() {
  local path="$1"
  [[ -f "$path" ]] && return 0
  if command -v sudo >/dev/null 2>&1; then
    sudo -n test -f "$path" >/dev/null 2>&1 && return 0
  fi
  return 1
}

cert_paths() {
  printf '%s/live/%s/fullchain.pem\n' "$LETSENCRYPT_DIR" "$DOMAIN"
  printf '%s/live/%s/privkey.pem\n' "$LETSENCRYPT_DIR" "$DOMAIN"
}

cert_exists() {
  local fullchain="$LETSENCRYPT_DIR/live/$DOMAIN/fullchain.pem"
  local privkey="$LETSENCRYPT_DIR/live/$DOMAIN/privkey.pem"
  file_exists_maybe_sudo "$fullchain" && file_exists_maybe_sudo "$privkey"
}

port_80_listeners() {
  if command -v ss >/dev/null 2>&1; then
    ss -H -ltn 'sport = :80' 2>/dev/null || true
    return
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:80 -sTCP:LISTEN 2>/dev/null || true
    return
  fi
  if command -v netstat >/dev/null 2>&1; then
    netstat -an 2>/dev/null | grep -E '(^tcp|LISTEN).*[:.]80[[:space:]].*LISTEN' || true
  fi
}

check_port_80_available() {
  local listeners
  listeners="$(port_80_listeners)"
  if [[ -n "$listeners" ]]; then
    warn "Port 80 appears to be in use. certbot --standalone needs it free."
    printf '%s\n' "$listeners" | sed 's/^/[tls:port80] /' >&2
    warn "Stop nginx/compose first, then rerun. This script will not stop services automatically."
    is_non_mutating || die "Port 80 is busy."
  else
    log "Port 80 appears available."
  fi
}

check_compose_not_running() {
  command -v docker >/dev/null 2>&1 || return 0
  local names
  names="$(docker ps --format '{{.Names}}' 2>/dev/null | grep -E '^commitgotchi-(nginx|springboot|fastapi|postgres)-prod$' || true)"
  if [[ -n "$names" ]]; then
    warn "Commit-Gotchi prod containers appear to be running:"
    printf '%s\n' "$names" | sed 's/^/[tls:container] /' >&2
    warn "Stop compose before initial standalone certbot issuance. No stop is performed automatically."
    is_non_mutating || die "Commit-Gotchi compose is running."
  fi
}

resolve_domain_ips() {
  if command -v dig >/dev/null 2>&1; then
    dig +short A "$DOMAIN" 2>/dev/null || true
    return
  fi
  if command -v getent >/dev/null 2>&1; then
    getent ahostsv4 "$DOMAIN" 2>/dev/null | awk '{print $1}' | sort -u || true
    return
  fi
  if command -v host >/dev/null 2>&1; then
    host "$DOMAIN" 2>/dev/null | awk '/has address/ {print $4}' || true
  fi
}

check_dns() {
  local ips
  ips="$(resolve_domain_ips)"
  if [[ -z "$ips" ]]; then
    warn "Could not resolve A record for $DOMAIN from this host."
    return
  fi

  log "$DOMAIN resolves to:"
  printf '%s\n' "$ips" | sed 's/^/[tls:dns]   /' >&2

  if [[ -n "$EXPECTED_PUBLIC_IP" ]] && ! printf '%s\n' "$ips" | grep -Fxq "$EXPECTED_PUBLIC_IP"; then
    warn "$DOMAIN does not resolve to expected IP $EXPECTED_PUBLIC_IP from this resolver."
  fi
}

sudo_prefix() {
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    return
  fi
  require_command sudo
  printf '%s\n' sudo
  printf '%s\n' -n
}

certbot_args() {
  printf '%s\n' certonly
  printf '%s\n' --standalone
  printf '%s\n' --non-interactive
  printf '%s\n' --agree-tos
  printf '%s\n' --preferred-challenges
  printf '%s\n' http
  printf '%s\n' --keep-until-expiring
  printf '%s\n' -d
  printf '%s\n' "$DOMAIN"

  if [[ "$NO_EMAIL" == "true" ]]; then
    printf '%s\n' --register-unsafely-without-email
  else
    printf '%s\n' --email
    if [[ -n "$EMAIL" ]]; then
      printf '%s\n' "$EMAIL"
    else
      printf '%s\n' '<set-with---email>'
    fi
  fi

  if [[ "$USE_STAGING" == "true" ]]; then
    printf '%s\n' --staging
  fi
}

print_certbot_command() {
  local arg
  while IFS= read -r arg; do
    printf '%q ' "$arg"
  done < <(sudo_prefix)
  printf '%q' "$CERTBOT_BIN"
  while IFS= read -r arg; do
    printf ' %q' "$arg"
  done < <(certbot_args)
  printf '\n'
}

run_certbot() {
  local command_args=()
  local arg
  while IFS= read -r arg; do
    command_args+=("$arg")
  done < <(sudo_prefix)
  command_args+=("$CERTBOT_BIN")
  while IFS= read -r arg; do
    command_args+=("$arg")
  done < <(certbot_args)

  "${command_args[@]}"
}

print_renewal_notes() {
  cat >&2 <<NOTES
[tls] Renewal review commands (not installed by this script):
  sudo certbot renew --dry-run
  sudo certbot renew

After renewal is approved, decide separately whether to enable a systemd timer
or cron job and whether nginx needs an automatic reload hook.
NOTES
}

main() {
  parse_args "$@"
  validate_config

  check_dns
  check_compose_not_running
  check_port_80_available

  if cert_exists; then
    log "Certificate files already exist for $DOMAIN:"
    cert_paths | sed 's/^/[tls:cert]   /' >&2
    if [[ "$FORCE" != "true" ]]; then
      log "No certbot run needed. Use --force with --apply only after reviewing renewal/reissue impact."
      print_renewal_notes
      exit 0
    fi
  fi

  if is_non_mutating; then
    log "Would run certbot standalone command:"
    printf '[plan] ' >&2
    print_certbot_command >&2
    log "Plan completed. certbot was not executed."
  else
    log "Running certbot standalone for $DOMAIN."
    run_certbot
    log "TLS bootstrap completed for $DOMAIN."
  fi

  print_renewal_notes
}

main "$@"
