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

response="$(curl -sS --fail-with-body -X POST "${KONTINUANCE_URL}/api/ci/dispatch" \
  "${auth[@]}" -H 'Content-Type: application/json' -d "$body")"

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
  || echo "log stream dropped; falling back to polling for the verdict" >&2
echo "::endgroup::"

# The stream's terminal `end` event carries no verdict, so read the record. Also covers the case where the
# stream dropped early: polling continues until the run actually settles.
for _ in $(seq 1 120); do
  record="$(curl -sS "${auth[@]}" "${KONTINUANCE_URL}/api/runs/${run_id}")"
  case "$(printf '%s' "$record" | jq -r '.status')" in
    Success)
      echo "kontinuance: Success"
      exit 0
      ;;
    Failed | Cancelled | Error)
      echo "kontinuance: $(printf '%s' "$record" | jq -r '.status')" >&2
      printf '%s' "$record" | jq -r '.failingStep // empty, .reason // empty' >&2
      echo "full logs: ${KONTINUANCE_URL}/runs/${run_id}" >&2
      exit 1
      ;;
  esac
  sleep 15
done

echo "timed out waiting for ${run_id}" >&2
exit 1
