#!/usr/bin/env bash
# Supply the host-specific values into the ingress manifests / hosts snippet that ship with placeholders.
# Two things are deliberately NOT committed and get rendered locally at apply time, so nothing
# environment-specific lands in git:
#   * lan-ip      -> the host's LAN IP (used by the *.nip.io host rules)
#   * lan-domain  -> your chosen host suffix for the *.<domain> names (e.g. home, lan, local)
#
#   deploy/host/render-lan-ip.sh ip                          # print the detected LAN IP and exit
#   deploy/host/render-lan-ip.sh hosts   -d home           # print the /etc/hosts line, filled in
#   deploy/host/render-lan-ip.sh render  stage -d home     # print the ingress with placeholders filled
#   deploy/host/render-lan-ip.sh apply   prod  -d home     # render + `kubectl apply -f -`
#
# The domain has no sane default, so pass it with -d/--domain or the $LAN_DOMAIN env var. The IP is
# auto-detected (macOS `ipconfig getifaddr`, then Linux `ip route` / `hostname -I`); override with
# -i/--ip or $LAN_IP. Envs for render/apply: stage | prod | argocd. Examples:
#   LAN_DOMAIN=home deploy/host/render-lan-ip.sh apply stage
#   deploy/host/render-lan-ip.sh render prod --domain home --ip 192.168.1.42
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IP_PLACEHOLDER="lan-ip"
DOMAIN_PLACEHOLDER="lan-domain"

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

# Map an environment name to the manifest that carries the placeholder host rules.
manifest_for() {
  case "$1" in
    stage)  echo "$ROOT/deploy/k8s/overlays/stage/ingress.yaml" ;;
    prod)   echo "$ROOT/deploy/k8s/overlays/prod/ingress.yaml" ;;
    argocd) echo "$ROOT/deploy/argocd/ingress.yaml" ;;
    *) echo "unknown env: '$1' (expected stage|prod|argocd)" >&2; exit 2 ;;
  esac
}

is_ipv4() { [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; }

# Resolve the LAN IP from the -i flag, then $LAN_IP, then auto-detection.
resolve_ip() {
  local ip="${IP_OPT:-${LAN_IP:-}}"
  if [ -z "$ip" ]; then
    if command -v ipconfig >/dev/null 2>&1; then          # macOS
      ip="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
    fi
    if [ -z "$ip" ] && command -v ip >/dev/null 2>&1; then # Linux
      ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
    fi
    if [ -z "$ip" ] && command -v hostname >/dev/null 2>&1; then
      ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    fi
  fi
  if ! is_ipv4 "$ip"; then
    echo "could not determine a valid LAN IP (got: '${ip:-<empty>}')." >&2
    echo "pass one with -i/--ip, or set \$LAN_IP." >&2
    exit 1
  fi
  echo "$ip"
}

# Resolve the host domain from the -d flag, then $LAN_DOMAIN. No default — it must be chosen.
resolve_domain() {
  local d="${DOMAIN_OPT:-${LAN_DOMAIN:-}}"
  if [ -z "$d" ]; then
    echo "no host domain set — pass it with -d/--domain (e.g. -d home) or set \$LAN_DOMAIN." >&2
    exit 1
  fi
  # RFC 1123 label(s): lowercase alnum + hyphen, dot-separated — same rule an ingress host must satisfy.
  if ! [[ "$d" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$ ]]; then
    echo "invalid domain '$d' — must be lowercase letters, digits, hyphens and dots." >&2
    exit 1
  fi
  echo "$d"
}

render() {  # <manifest> <ip> <domain>
  for ph in "$IP_PLACEHOLDER" "$DOMAIN_PLACEHOLDER"; do
    grep -q "$ph" "$1" || echo "note: no '$ph' placeholder found in $1." >&2
  done
  sed -e "s/$IP_PLACEHOLDER/$2/g" -e "s/$DOMAIN_PLACEHOLDER/$3/g" "$1"
}

# --- parse: <command> [env] with -i/--ip and -d/--domain flags anywhere ---
IP_OPT="" DOMAIN_OPT="" POS=()
[ $# -gt 0 ] || usage 0
cmd="$1"; shift
while [ $# -gt 0 ]; do
  case "$1" in
    -i|--ip)      IP_OPT="${2:-}"; shift 2 ;;
    -d|--domain)  DOMAIN_OPT="${2:-}"; shift 2 ;;
    -h|--help)    usage 0 ;;
    -*) echo "unknown option: $1" >&2; usage 2 ;;
    *)  POS+=("$1"); shift ;;
  esac
done

case "$cmd" in
  ip)
    resolve_ip
    ;;
  hosts)
    ip="$(resolve_ip)"; domain="$(resolve_domain)"
    echo "$ip  stage.$domain stage-api.$domain prod.$domain prod-api.$domain argocd.$domain"
    ;;
  render)
    [ "${#POS[@]}" -ge 1 ] || { echo "render needs an env (stage|prod|argocd)" >&2; usage 2; }
    file="$(manifest_for "${POS[0]}")"
    ip="$(resolve_ip)"; domain="$(resolve_domain)"
    render "$file" "$ip" "$domain"
    ;;
  apply)
    [ "${#POS[@]}" -ge 1 ] || { echo "apply needs an env (stage|prod|argocd)" >&2; usage 2; }
    command -v kubectl >/dev/null 2>&1 || { echo "kubectl not found on PATH" >&2; exit 1; }
    file="$(manifest_for "${POS[0]}")"
    ip="$(resolve_ip)"; domain="$(resolve_domain)"
    echo "applying ${POS[0]} ingress with lan-ip=$ip lan-domain=$domain" >&2
    render "$file" "$ip" "$domain" | kubectl apply -f -
    ;;
  help|-h|--help)
    usage 0
    ;;
  *)
    echo "unknown command: '$cmd'" >&2
    usage 2
    ;;
esac
