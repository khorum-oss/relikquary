# Quickstart & Validation: Container (OCI / Docker) Registry

Runnable validation that container repositories work end-to-end with a real client. Proves the two P1
user stories (Docker Hub pull-through proxy; hosted push/pull) plus auth and error behavior. Assumes the
implementation from `tasks.md` is in place.

## Prerequisites

- Relikquary backend running (JDK 21). For a local, auth-off run:
  `./gradlew :backend:bootRun --args='--spring.profiles.active=local'` (serves on `:8080`).
- A container client on PATH: `docker` (daemon running) or `podman`/`nerdctl`. `skopeo`/`crane` optional.
- Postgres for the relational state (the `local`/dev stack already provisions it; see
  `deploy/docker-compose.dev.yml` / `deploy/k8s/relikquary-dev.yaml`).
- Two configured repositories (see Config below): a hosted container repo and a Docker Hub proxy.

> `docker` refuses plain-HTTP registries unless the host is `localhost` or listed as an insecure
> registry. For a non-localhost host, add it to `/etc/docker/daemon.json` `insecure-registries` or front
> Relikquary with TLS. `localhost:8080` works out of the box.

## Config

Add to the active profile's `relikquary.repositories` (YAML shown; `format` is the new field):

```yaml
relikquary:
  repositories:
    - name: containers          # hosted: push/pull your own images
      kind: HOSTED
      format: CONTAINER
    - name: dockerhub           # proxy: pull-through cache of Docker Hub
      kind: PROXY
      format: CONTAINER
      # remoteUrl defaults to https://registry-1.docker.io when omitted for a CONTAINER proxy
      # remoteUsername / remotePassword optional (higher rate limit / private images)
```

## Scenario A — Pull a public image through the Docker Hub proxy (User Story 1)

Cold cache → fetched from Docker Hub (bearer-token handshake, `library/` normalization), cached by digest.

```bash
docker pull localhost:8080/dockerhub/library/alpine:3.20
# multi-arch: the index + your platform's manifest + its layers are fetched and cached
docker run --rm localhost:8080/dockerhub/library/alpine:3.20 echo ok   # -> ok
```

**Expected**:
- First pull succeeds; server logs show upstream fetches (`recordUpstream(... "found")`).
- Second pull of the same tag serves cached digests (no upstream blob fetches; cache-hit metrics).
- `docker pull localhost:8080/dockerhub/alpine:3.20` (no namespace) also works → normalized to
  `library/alpine`.
- `docker pull localhost:8080/dockerhub/library/nginx:tag-that-does-not-exist` → `manifest unknown`
  (404); nothing cached.
- With the upstream unreachable, re-pulling an **already-cached** digest still succeeds; pulling an
  **uncached** image reports a gateway error (502), not "not found".

## Scenario B — Push and pull your own image to the hosted repo (User Story 2)

```bash
# Build (or tag) a tiny local image
printf 'FROM scratch\nCOPY hello.txt /\n' > Dockerfile && echo hi > hello.txt
docker build -t localhost:8080/containers/team/app:1.4.0 .

docker push localhost:8080/containers/team/app:1.4.0

# Prove a true round-trip from a clean daemon (no local layers)
docker rmi localhost:8080/containers/team/app:1.4.0
docker pull localhost:8080/containers/team/app:1.4.0
```

**Expected**:
- Push uploads each blob (monolithic or chunked) then the manifest; each digest is verified server-side.
- The pushed and pulled image digests match (`docker inspect --format '{{.Id}}'` / `docker buildx
  imagetools inspect` agree).
- `curl -s localhost:8080/v2/containers/team/app/tags/list` → `{"name":"team/app","tags":["1.4.0"]}`.
- Re-pushing `:1.4.0` to a new build re-points the tag; the old manifest is still retrievable by its
  digest.

## Scenario C — Auth gate (User Story 3), run with auth enabled

With `relikquary.security.enabled=true` and the `containers` repo's `access.publish` set to `@PUBLISH`:

```bash
docker logout localhost:8080
docker push localhost:8080/containers/team/app:1.4.0     # -> denied: 401/authentication required
docker login localhost:8080 -u publisher -p "$RELIKQUARY_PUBLISHER_PASSWORD"
docker push localhost:8080/containers/team/app:1.4.0     # -> succeeds
```

**Expected**: anonymous push is challenged (`WWW-Authenticate: Basic`); an authenticated publisher (or an
`rlq_…` API token used as the password) succeeds. Open-read repos still `docker pull` without login.

## Scenario D — Storage parity (S3)

Point storage at the S3/MinIO backend (`relikquary.storage.*`, no code change) and repeat Scenario B.
The same image round-trips byte-for-byte identically to the filesystem backend.

## Automated validation (the authoritative proof)

`./gradlew :backend:test` runs, offline and deterministic:

- `ContainerHostedRoundTripIT` — push→pull an image over the V2 wire; digests match; filesystem + MinIO.
- `ContainerProxyIT` — pull through the proxy against a `registry:2` Testcontainer stub upstream; second
  pull is a cache hit.
- `ContainerProxyDockerHubIT` — real `library/alpine` pull through the proxy, **auto-skipped when
  offline**.
- `ContainerAuthIT` — anonymous push to a role-gated repo challenged; authenticated push succeeds.
- `ContainerErrorsIT` — digest mismatch (400), manifest→missing blob (400), proxy 404 vs 502, push to a
  proxy (405).
- `ImageReferenceTest`, `DigestTest` — name/reference grammar and digest compute/verify.

## Success mapping

| Spec Success Criterion | Validated by |
|------------------------|--------------|
| SC-001 (proxy cold pull + cached second pull) | Scenario A; `ContainerProxyIT` / `…DockerHubIT` |
| SC-002 (cached bytes digest-identical) | `ContainerProxyIT` digest assertions |
| SC-003 (hosted push→pull identical) | Scenario B; `ContainerHostedRoundTripIT` |
| SC-004 (digest mismatch / missing blob rejected) | `ContainerErrorsIT` |
| SC-005 (fs + S3 parity) | Scenario D; `ContainerHostedRoundTripIT` (both backends) |
| SC-006 (auth gate; Maven suites still pass) | Scenario C; `ContainerAuthIT` + existing suites |
| SC-007 (proxy 404 vs 502) | `ContainerErrorsIT` |
