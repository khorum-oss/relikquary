#!/usr/bin/env bash
# Provision the Relikquary secrets in 1Password for a given environment, with freshly generated
# passwords. The 1Password Kubernetes Operator then syncs the item into a `relikquary-secrets` Secret in
# the matching namespace (see this directory's README.md).
#
#   deploy/k8s/onepassword/create-items.sh stage            # create vault (if missing) + item
#   deploy/k8s/onepassword/create-items.sh prod
#   deploy/k8s/onepassword/create-items.sh stage --rotate   # regenerate values on an existing item
#   deploy/k8s/onepassword/create-items.sh prod  --noop     # store publisher pw as {noop} (NOT for prod)
#
# Requires: the `op` CLI, signed in (`op signin`). bcrypt hashing of the publisher password uses
# `htpasswd` (apache2-utils / httpd-tools) when present; otherwise the script tells you how to finish by
# hand. Nothing is written to disk — generated values go straight into 1Password.
set -euo pipefail

ENV="${1:-}"
ROTATE=false
ENCODER="bcrypt"   # bcrypt (default) or noop

for arg in "${@:2}"; do
  case "$arg" in
    --rotate) ROTATE=true ;;
    --noop)   ENCODER="noop" ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

case "$ENV" in
  stage|prod) ;;
  *) echo "usage: $0 <stage|prod> [--rotate] [--noop]" >&2; exit 2 ;;
esac

VAULT="relikquary-${ENV}"
ITEM="relikquary-secrets"

command -v op >/dev/null 2>&1 || { echo "error: the 1Password CLI 'op' is not on PATH." >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "error: 'openssl' is not on PATH (needed to generate passwords)." >&2; exit 1; }
op whoami >/dev/null 2>&1 || { echo "error: not signed in — run 'op signin' first." >&2; exit 1; }

# Refuse {noop} for prod unless the caller is very explicit (they'd have to edit this line).
if [[ "$ENV" == "prod" && "$ENCODER" == "noop" ]]; then
  echo "refusing to store a {noop} (plaintext) publisher password for prod. Use bcrypt." >&2
  exit 1
fi

# --- generate raw secrets -----------------------------------------------------------------------------
# A 24-char alphanumeric password (drop base64's +/= so it's shell/URL-safe wherever it ends up).
gen() { openssl rand -base64 32 | tr -d '/+=' | cut -c1-24; }
DB_PASSWORD="$(gen)"
PUBLISHER_RAW="$(gen)"

# --- encode the publisher password for Spring Security ------------------------------------------------
if [[ "$ENCODER" == "noop" ]]; then
  PUBLISHER_FIELD="{noop}${PUBLISHER_RAW}"
elif command -v htpasswd >/dev/null 2>&1; then
  HASH="$(htpasswd -bnBC 12 "" "$PUBLISHER_RAW" | tr -d ':\n' | sed 's/^\$2y/\$2a/')"
  PUBLISHER_FIELD="{bcrypt}${HASH}"
else
  echo "note: 'htpasswd' not found — cannot bcrypt-hash the publisher password here." >&2
  echo "      Install apache2-utils/httpd-tools, or set the field by hand to {bcrypt}<hash> of your" >&2
  echo "      chosen password. Storing a placeholder you MUST replace before exposing the server." >&2
  PUBLISHER_FIELD="{bcrypt}REPLACE_ME"
fi

# --- ensure the vault exists --------------------------------------------------------------------------
if ! op vault get "$VAULT" >/dev/null 2>&1; then
  echo "creating vault '$VAULT' ..."
  op vault create "$VAULT" >/dev/null
fi

FIELDS=(
  "RELIKQUARY_SECURITY_USERS_0_PASSWORD[password]=${PUBLISHER_FIELD}"
  "RELIKQUARY_DB_PASSWORD[password]=${DB_PASSWORD}"
)

if op item get "$ITEM" --vault "$VAULT" >/dev/null 2>&1; then
  if [[ "$ROTATE" == true ]]; then
    echo "rotating values on existing item '$ITEM' in vault '$VAULT' ..."
    op item edit "$ITEM" --vault "$VAULT" "${FIELDS[@]}" >/dev/null
  else
    echo "item '$ITEM' already exists in vault '$VAULT'. Pass --rotate to regenerate its values." >&2
    exit 1
  fi
else
  echo "creating item '$ITEM' in vault '$VAULT' ..."
  op item create --category "Server" --title "$ITEM" --vault "$VAULT" "${FIELDS[@]}" >/dev/null
fi

cat <<EOF

Done. Vault '$VAULT' now holds item '$ITEM' with:
  RELIKQUARY_SECURITY_USERS_0_PASSWORD  (${ENCODER})
  RELIKQUARY_DB_PASSWORD

The overlay references it at: vaults/${VAULT}/items/${ITEM}
Apply the environment when the operator is installed:
  kubectl apply -k deploy/k8s/overlays/${ENV}
EOF
