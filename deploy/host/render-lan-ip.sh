#!/usr/bin/env bash
# Supply the host's LAN IP into the ingress manifests / hosts snippet that ship with a `LAN_IP`
# placeholder. The IP is deliberately NOT committed — this renders it locally at apply time so nothing
# environment-specific lands in git.
#
#   deploy/host/render-lan-ip.sh ip                 # print the detected LAN IP and exit
#   deploy/host/render-lan-ip.sh hosts              # print the /etc/hosts line (IP + *.hestia names)
#   deploy/host/render-lan-ip.sh render stage       # print the stage ingress with LAN_IP substituted
#   deploy/host/render-lan-ip.sh apply  prod        # render + `kubectl apply -f -` (stage|prod|argocd)
#
# IP resolution order: explicit last arg > $LAN_IP env var > auto-detect (macOS `ipconfig getifaddr`,
# then Linux `ip route` / `hostname -I`). Examples:
#   deploy/host/render-lan-ip.sh hosts 192.168.1.42
#   LAN_IP=192.168.1.42 deploy/host/render-lan-ip.sh apply stage
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLACEHOLDER="LAN_IP"

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

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

# Resolve the LAN IP from an explicit arg, then $LAN_IP, then auto-detection.
resolve_ip() {
  local ip="${1:-${LAN_IP:-}}"
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
    echo "pass one explicitly, e.g. '$(basename "$0") hosts 192.168.1.42', or set \$LAN_IP." >&2
    exit 1
  fi
  echo "$ip"
}

render() {  # <manifest> <ip>
  if ! grep -q "$PLACEHOLDER" "$1"; then
    echo "note: no '$PLACEHOLDER' placeholder found in $1 — emitting as-is." >&2
  fi
  sed "s/$PLACEHOLDER/$2/g" "$1"
}

cmd="${1:-}"
case "$cmd" in
  ip)
    resolve_ip "${2:-}"
    ;;
  hosts)
    ip="$(resolve_ip "${2:-}")"
    echo "$ip  stage.hestia stage-api.hestia prod.hestia prod-api.hestia argocd.hestia"
    ;;
  render)
    [ $# -ge 2 ] || usage 2
    file="$(manifest_for "$2")"
    ip="$(resolve_ip "${3:-}")"
    render "$file" "$ip"
    ;;
  apply)
    [ $# -ge 2 ] || usage 2
    command -v kubectl >/dev/null 2>&1 || { echo "kubectl not found on PATH" >&2; exit 1; }
    file="$(manifest_for "$2")"
    ip="$(resolve_ip "${3:-}")"
    echo "applying $2 ingress with LAN_IP=$ip" >&2
    render "$file" "$ip" | kubectl apply -f -
    ;;
  ""|-h|--help|help)
    usage 0
    ;;
  *)
    echo "unknown command: '$cmd'" >&2
    usage 2
    ;;
esac
