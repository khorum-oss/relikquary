#!/usr/bin/env bash
# Runs the frontend Playwright e2e against a real backend with AUTH ENABLED (feature 008):
#   1. starts the backend bootJar with scripts/e2e-config.yml (alice publisher; open 'releases',
#      alice-only 'private'),
#   2. seeds artifacts (with credentials) into both repos,
#   3. runs Playwright (which starts `vite dev`, proxying /api + repo paths to the backend),
#   4. tears the backend down.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$ROOT/backend/build/libs/backend.jar"
CONFIG="$ROOT/frontend/scripts/e2e-config.yml"
STORE="$(mktemp -d)"
CURL=(curl -sf --noproxy '*')
ALICE=(-u alice:pw)

[ -f "$JAR" ] || { echo "Build the backend jar first: ./gradlew :backend:bootJar"; exit 1; }

echo "Starting backend (auth on, store=$STORE)..."
java -jar "$JAR" \
  --spring.config.location="file:$CONFIG" \
  --relikquary.storage.filesystem.root="$STORE" \
  --relikquary.persistence.sqlite.path="$STORE/relikquary.db" \
  >/tmp/relikquary-e2e-backend.log 2>&1 &
BPID=$!
trap 'kill "$BPID" 2>/dev/null || true' EXIT

echo "Waiting for backend..."
for _ in $(seq 1 60); do
  "${CURL[@]}" http://127.0.0.1:8080/api/repositories >/dev/null 2>&1 && break
  sleep 1
done

seed() { # repo path-base
  local base="http://127.0.0.1:8080/$1/$2"
  printf 'jar-bytes-here' | "${CURL[@]}" "${ALICE[@]}" -X PUT --data-binary @- "$base.jar"
  printf '<project/>'     | "${CURL[@]}" "${ALICE[@]}" -X PUT --data-binary @- "$base.pom"
  printf 'deadbeef'       | "${CURL[@]}" "${ALICE[@]}" -X PUT --data-binary @- "$base.jar.sha1"
}

echo "Seeding artifacts (open + private)..."
seed releases com/example/widget/1.0.0/widget-1.0.0
seed private com/acme/lib/1.0.0/lib-1.0.0

# A Gradle module coordinate (feature 011): a jar + a real .module with a variant, so the browse UI can
# badge it, render consume snippets, and show the parsed module detail.
seed_module() {
  local base="http://127.0.0.1:8080/releases/com/example/gmodule/2.0.0/gmodule-2.0.0"
  # An explicit content type is required: with curl's default form content type, Tomcat consumes the PUT
  # body as form parameters and the stored file would be empty.
  local octet=(-H 'Content-Type: application/octet-stream')
  printf 'gmodule-jar' | "${CURL[@]}" "${ALICE[@]}" "${octet[@]}" -X PUT --data-binary @- "$base.jar"
  printf '%s' '{"formatVersion":"1.1","component":{"group":"com.example","module":"gmodule","version":"2.0.0"},"variants":[{"name":"apiElements","attributes":{"org.gradle.usage":"java-api","org.gradle.category":"library"},"capabilities":[{"group":"com.example","name":"gmodule","version":"2.0.0"}],"dependencies":[{"group":"com.google.guava","module":"guava","version":{"requires":"33.0.0-jre"}}],"files":[{"name":"gmodule-2.0.0.jar","url":"gmodule-2.0.0.jar","size":11,"sha256":"deadbeef"}]}]}' \
    | "${CURL[@]}" "${ALICE[@]}" "${octet[@]}" -X PUT --data-binary @- "$base.module"
}
seed_module

# A container image (feature 018): a docker-push-shaped upload — monolithic config + layer blobs, then a
# manifest referencing them — so the container browse UI lists the image, its tag, digest, and size.
seed_container() {
  local image="$1" tag="$2"
  local v2="http://127.0.0.1:8080/v2/apps/$image"
  local octet=(-H 'Content-Type: application/octet-stream')
  local config='{"architecture":"amd64","os":"linux"}'
  local layer="fake-layer-$image-$tag"
  local cfg_digest="sha256:$(printf '%s' "$config" | sha256sum | cut -d' ' -f1)"
  local layer_digest="sha256:$(printf '%s' "$layer" | sha256sum | cut -d' ' -f1)"
  printf '%s' "$config" | "${CURL[@]}" "${ALICE[@]}" "${octet[@]}" -X POST --data-binary @- "$v2/blobs/uploads/?digest=$cfg_digest"
  printf '%s' "$layer" | "${CURL[@]}" "${ALICE[@]}" "${octet[@]}" -X POST --data-binary @- "$v2/blobs/uploads/?digest=$layer_digest"
  local manifest
  manifest="$(printf '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"%s","size":%s},"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"%s","size":%s}]}' \
    "$cfg_digest" "$(printf '%s' "$config" | wc -c)" "$layer_digest" "$(printf '%s' "$layer" | wc -c)")"
  printf '%s' "$manifest" | "${CURL[@]}" "${ALICE[@]}" \
    -H 'Content-Type: application/vnd.oci.image.manifest.v1+json' -X PUT --data-binary @- "$v2/manifests/$tag"
}
seed_container team/service 1.0.0

# A single platform's image manifest (config + one layer), pushed by its own digest so an index can
# reference it. Echoes the manifest JSON so the caller can compute its digest and size (feature 020).
push_platform_manifest() {
  local image="$1" arch="$2"
  local v2="http://127.0.0.1:8080/v2/apps/$image"
  local octet=(-H 'Content-Type: application/octet-stream')
  local config="{\"architecture\":\"$arch\",\"os\":\"linux\"}"
  local layer="layer-$image-$arch"
  local cfg_digest="sha256:$(printf '%s' "$config" | sha256sum | cut -d' ' -f1)"
  local layer_digest="sha256:$(printf '%s' "$layer" | sha256sum | cut -d' ' -f1)"
  "${CURL[@]}" "${ALICE[@]}" "${octet[@]}" -X POST --data-binary "$config" "$v2/blobs/uploads/?digest=$cfg_digest" >/dev/null
  "${CURL[@]}" "${ALICE[@]}" "${octet[@]}" -X POST --data-binary "$layer" "$v2/blobs/uploads/?digest=$layer_digest" >/dev/null
  local manifest
  manifest="$(printf '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"%s","size":%s},"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"%s","size":%s}]}' \
    "$cfg_digest" "$(printf '%s' "$config" | wc -c)" "$layer_digest" "$(printf '%s' "$layer" | wc -c)")"
  local m_digest="sha256:$(printf '%s' "$manifest" | sha256sum | cut -d' ' -f1)"
  printf '%s' "$manifest" | "${CURL[@]}" "${ALICE[@]}" \
    -H 'Content-Type: application/vnd.oci.image.manifest.v1+json' -X PUT --data-binary @- "$v2/manifests/$m_digest" >/dev/null
  printf '%s' "$manifest"
}

# A multi-arch image (feature 020): two platform manifests plus an image index referencing them, so the
# manifest detail UI can list platforms and drill into one.
seed_multiarch() {
  local image="$1" tag="$2"
  local v2="http://127.0.0.1:8080/v2/apps/$image"
  local amd arm amd_digest arm_digest
  amd="$(push_platform_manifest "$image" amd64)"
  arm="$(push_platform_manifest "$image" arm64)"
  amd_digest="sha256:$(printf '%s' "$amd" | sha256sum | cut -d' ' -f1)"
  arm_digest="sha256:$(printf '%s' "$arm" | sha256sum | cut -d' ' -f1)"
  local index
  index="$(printf '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"%s","size":%s,"platform":{"os":"linux","architecture":"amd64"}},{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"%s","size":%s,"platform":{"os":"linux","architecture":"arm64","variant":"v8"}}]}' \
    "$amd_digest" "$(printf '%s' "$amd" | wc -c)" "$arm_digest" "$(printf '%s' "$arm" | wc -c)")"
  printf '%s' "$index" | "${CURL[@]}" "${ALICE[@]}" \
    -H 'Content-Type: application/vnd.oci.image.index.v1+json' -X PUT --data-binary @- "$v2/manifests/$tag"
}
seed_multiarch team/multi 1.0.0

echo "Running Playwright..."
cd "$ROOT/frontend"
# Prefer a pre-installed Chromium when present (this environment); otherwise let Playwright use its
# own installed browser (e.g. CI after `npx playwright install`).
if [ -z "${RELIKQUARY_CHROMIUM_PATH:-}" ] && [ -x /opt/pw-browsers/chromium ]; then
  export RELIKQUARY_CHROMIUM_PATH=/opt/pw-browsers/chromium
fi
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm run test:e2e
