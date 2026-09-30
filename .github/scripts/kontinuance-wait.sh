#!/usr/bin/env bash
# Dispatch a Kontinuance run for this PR's head commit, stream its logs, and exit with its verdict.
#
# The run happens on the self-hosted Kontinuance server; this script is only the client. The SSE stream
# sets no event ids, so a dropped connection cannot resume without replaying from the beginning — live
# logs are therefore best-effort, and the verdict always comes from the run record, never from the stream.
set -euo pipefail

: "${KONTINUANCE_URL:?KONTINUANCE_URL is not set}"
: "${KONTINUANCE_CI_TOKEN:?KONTINUANCE_CI_TOKEN is not set}"
: "${CF_ACCESS_CLIENT_ID:?CF_ACCESS_CLIENT_ID is not set}"
: "${CF_ACCESS_CLIENT_SECRET:?CF_ACCESS_CLIENT_SECRET is not set}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
: "${HEAD_SHA:?HEAD_SHA is not set}"

auth=(
  -H "Authorization: Bearer ${KONTINUANCE_CI_TOKEN}"
  -H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}"
  -H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}"
)

run_id=""

# Captures what the live stream managed to print, so the polling fallback can resume from that point
# instead of replaying the whole log.
streamed="$(mktemp)"
trap 'rm -f "$streamed"' EXIT

# Cancelling the Actions job must not leave a ~14-minute build occupying the runner host.
cancel_run() {
  if [ -n "$run_id" ]; then
    curl -sS -X POST "${auth[@]}" "${KONTINUANCE_URL}/api/runs/${run_id}/cancel" >/dev/null 2>&1 || true
    echo "cancelled kontinuance run ${run_id}" >&2
  fi
  exit 143
}
trap cancel_run INT TERM

body="$(jq -nc \
  --arg repo "$GITHUB_REPOSITORY" \
  --arg sha "$HEAD_SHA" \
  --arg ref "${GITHUB_REF:-}" \
  --argjson pr "${PR_NUMBER:-null}" \
  '{repo: $repo, sha: $sha, ref: $ref, prNumber: $pr}')"

# Capture body and status separately. `--fail-with-body` writes the body to stdout and exits nonzero, but
# under command substitution + `set -e` the script dies before anything is printed — so a 403 from the edge
# arrives as a bare "curl: (22)" with no indication of which layer refused. Diagnose here instead.
http_code=""
if ! response="$(curl -sS -w '\n%{http_code}' -X POST "${KONTINUANCE_URL}/api/ci/dispatch" \
  "${auth[@]}" -H 'Content-Type: application/json' -d "$body")"; then
  echo "dispatch request failed to complete: $response" >&2
  exit 1
fi
http_code="${response##*$'\n'}"
response="${response%$'\n'*}"

if [ "$http_code" != "200" ] && [ "$http_code" != "202" ]; then
  echo "dispatch returned HTTP $http_code" >&2
  # A bot challenge and an Access denial are both 403. Only the body separates them, which is why it is
  # printed below — "Just a moment..." is Cloudflare's interstitial, served BEFORE Access policies run.
  case "$http_code" in
    403)
      if printf '%s' "$response" | grep -qiE "just a moment|cf-browser-verification|challenge-platform"; then
        echo "  Cloudflare served a BOT CHALLENGE, not an Access decision. A datacentre IP (this runner)" >&2
        echo "  tripped Bot Fight Mode / a managed challenge before Access was consulted." >&2
        echo "  Fix: WAF -> Custom rules -> Skip for this hostname's /api/ paths (or exempt the token)." >&2
      else
        echo "  Cloudflare Access authenticated the service token but DENIED it — the policy on this" >&2
        echo "  application does not admit it (check its action is 'Service Auth', and any Require rules)" >&2
      fi ;;
    302) echo "  bounced to the Access login — the service token was not recognised at all" >&2 ;;
    401) echo "  reached Kontinuance; the bearer token (KONTINUANCE_CI_TOKEN) was refused" >&2 ;;
    400) echo "  reached Kontinuance and authenticated; the request body was rejected" >&2 ;;
  esac
  echo "  body: $(printf '%s' "$response" | head -c 500)" >&2
  exit 1
fi

run_id="$(printf '%s' "$response" | jq -r '.runId // empty')"
if [ -z "$run_id" ]; then
  echo "dispatch returned no runId: $response" >&2
  exit 1
fi
# `AlreadyRunning` means this commit was already building — we attached to it rather than starting a second.
echo "kontinuance run ${run_id} ($(printf '%s' "$response" | jq -r '.status'))"

echo "::group::build output"
curl -sS --no-buffer "${auth[@]}" "${KONTINUANCE_URL}/api/runs/${run_id}/logs/stream" \
  | sed -u -n 's/^data://p' \
  | tee "$streamed" \
  || echo "log stream dropped; polling the log endpoint instead (see note below)" >&2
echo "::endgroup::"

# How many lines the stream already showed, so the fallback resumes rather than replaying from the top.
# The stream emits one `log` event per recorded line, so this indexes `.lines` directly. A log line
# containing a newline is split across `data:` frames and would shift this by one or two — the cost is a
# repeated or skipped line in the fallback, never a wrong verdict.
printed="$(wc -l < "$streamed" | tr -d ' ')"

# The stream's terminal `end` event carries no verdict, so read the record. This also covers the common
# case where the stream dropped early.
#
# ── Why the stream drops, and why this loop prints ────────────────────────────────────────────────────
# The SSE producer emits only on new log lines — there is no heartbeat. `:backend:test` runs for minutes
# with no output, and Cloudflare terminates a streamed response that transmits nothing for ~100s. Measured
# 2026-09-29: last line 02:19:54, reset 02:22:18 — 143s of silence, then
# `curl: (92) HTTP/2 stream 1 was not closed cleanly: INTERNAL_ERROR (err 2)`. It is the proxy resetting an
# idle stream, not the build stalling, and it happens at the same place every run.
#
# This loop therefore keeps printing: new log lines when there are any, and a liveness line when there are
# not. Previously it polled silently until the run settled, so a healthy build looked identical to a hung
# one for ten minutes. The durable fix is a heartbeat on the producer; this keeps the job legible either way.
quiet=0
for poll in $(seq 1 120); do
  record="$(curl -sS "${auth[@]}" "${KONTINUANCE_URL}/api/runs/${run_id}")"
  status="$(printf '%s' "$record" | jq -r '.status')"

  # Drain whatever the stream missed. Failures here are cosmetic — never let them end the run.
  if logs="$(curl -sS "${auth[@]}" "${KONTINUANCE_URL}/api/runs/${run_id}/logs" 2>/dev/null)"; then
    if new="$(printf '%s' "$logs" | jq -r --argjson n "$printed" '.lines[$n:][]' 2>/dev/null)" \
      && [ -n "$new" ]; then
      printf '%s\n' "$new"
      printed="$(printf '%s' "$logs" | jq -r '.lines | length' 2>/dev/null || echo "$printed")"
      quiet=0
    else
      quiet=$((quiet + 1))
    fi
  fi

  case "$status" in
    Success)
      echo "kontinuance: Success"
      exit 0
      ;;
    Failed | Cancelled | Error)
      echo "kontinuance: $status" >&2
      printf '%s' "$record" | jq -r '.failingStep // empty, .reason // empty' >&2
      echo "full logs: ${KONTINUANCE_URL}/runs/${run_id}" >&2
      exit 1
      ;;
  esac

  # A quiet build is the normal case during `:backend:test`. Say so every ~minute rather than every 15s,
  # so the job reads as alive without burying the real output.
  if [ "$quiet" -gt 0 ] && [ $((poll % 4)) -eq 0 ]; then
    echo "… still ${status} (${poll} polls, no new output — a long quiet task, e.g. :backend:test)"
  fi
  sleep 15
done

echo "timed out waiting for ${run_id}" >&2
exit 1
