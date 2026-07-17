# Feature Specification: Container Registry Integration & Round-Trip Verification

**Feature Branch**: `021-container-registry-roundtrip`

**Created**: 2026-07-17

**Status**: Draft

**Input**: User description: "Container registry integration & round-trip verification. Feature 018 (the
OCI/Docker container registry served under /v2) shipped with only unit tests (image-reference parsing and
digest math) and no integration test — there is no proof that a real push→pull round-trip works end to
end, which the project's test-first, integration-verified discipline requires of every serving/publish
path (as the Maven features already have). Add integration coverage that proves, against a running server
with real storage, that a hosted container repository accepts a docker-style push, serves the exact same
bytes and digests back on pull, lists tags, re-points a mutable tag on re-push while the old digest stays
retrievable, deletes a tag and a manifest by digest, and rejects push to a proxy repository; and that a
proxy container repository serves a pull-through from an upstream on a cache miss and from local cache on a
hit, preserving bytes and digests, without a push path. Preserve stored bytes and digests exactly. Where a
real container client or a stubbed upstream can stand in for hand-built requests, prefer the more realistic
client. This is test/verification work only — it changes no product behavior, wire protocol, or
configuration."

## Clarifications

### Session 2026-07-17

- Q: What stands in as the "client" for the round-trip? → A: Prefer a real container client
  (`docker`/`podman`/`skopeo`/`crane`) driving the registry over its published wire protocol when one is
  available in the test environment; otherwise fall back to a faithful protocol-level client that performs
  the exact same request sequence (blob uploads → manifest PUT → manifest/blob GET → tags list → delete).
  Either way the assertions are on observed bytes and digests, not on internal state.
- Q: What is the source of truth the proxy pulls from? → A: A controlled, in-test upstream stand-in that
  serves fixed content (mirroring how the Maven proxy round-trips use a stub upstream), so the test is
  deterministic and needs no live external registry. A real public upstream (Docker Hub) MAY additionally
  be exercised in a separate, network-gated test but is not required for the core guarantee.
- Q: Which storage backends must the round-trip cover? → A: The default real filesystem backend is
  required (a real temporary directory, not a mock). Exercising the S3-compatible backend as well is
  desirable for parity with the Maven suite but is a secondary, environment-gated scenario, not a blocker.
- Q: Does this change any product code? → A: No. This is verification-only. If a genuine defect is
  uncovered, fixing it is in scope (the test must end green), but no behavior, wire contract, or
  configuration is changed to accommodate the tests.

## User Scenarios & Testing *(mandatory)*

The "user" of this feature is the project's engineering team and its release gate: the value is trustworthy
proof that the container registry actually round-trips, so a change to it cannot silently break the
`docker push`/`docker pull` contract the way an untested path can.

### User Story 1 - Prove a hosted push→pull→delete round-trip (Priority: P1)

Against a running server backed by real storage, a client pushes a container image to a hosted repository
(uploads the config and layer blobs, then puts the image manifest under a tag), pulls it back, and gets the
byte-identical manifest and blobs with the same digests the client computed. The client lists the image's
tags and sees the pushed tag; re-pushes a different image to the same tag and confirms the tag now resolves
to the new digest while the previous manifest is still retrievable by its digest (tags are mutable, digests
are immutable); then deletes the tag and deletes a manifest by digest and confirms they are gone.

**Why this priority**: This is the headline guarantee and the missing coverage — the hosted registry's
entire reason to exist is that an image pushed to it comes back exactly. Everything else is secondary to
proving this one path end to end. It is independently valuable even if the proxy scenario is never added.

**Independent Test**: Stand up the server with a hosted container repository on real filesystem storage,
run the push→pull→tags→re-tag→delete sequence with a container client (or its protocol-faithful stand-in),
and assert byte-and-digest identity at each pull plus the expected tag/deletion outcomes.

**Acceptance Scenarios**:

1. **Given** a hosted container repository, **When** a client pushes an image (config + layers + manifest
   under a tag), **Then** pulling that tag returns the manifest with the same media type and a digest equal
   to what the client computed, and each referenced blob is returned byte-for-byte with its digest.
2. **Given** a pushed image, **When** the client requests the image's tag list, **Then** the pushed tag is
   present.
3. **Given** a tag that already resolves to one image, **When** the client pushes a different image to the
   same tag, **Then** the tag resolves to the new digest and the previously tagged manifest is still
   retrievable by its original digest.
4. **Given** a pushed image, **When** the client deletes the tag and deletes a manifest by digest, **Then**
   subsequent pulls of the deleted references report "not found".
5. **Given** a proxy container repository, **When** a client attempts to push to it, **Then** the push is
   rejected as unsupported (no push path on a pull-through cache).

---

### User Story 2 - Prove a proxy pull-through round-trip with a stubbed upstream (Priority: P2)

Against a running server, a proxy container repository is pointed at a controlled upstream stand-in serving
fixed content. A client pulls an image tag that is not yet cached: the server resolves it from the upstream,
serves it to the client, and caches the exact bytes locally by digest. A second pull of the same digest is
served from the local cache — byte-identical — and does not depend on the upstream being reachable.

**Why this priority**: The proxy is the second half of feature 018's parity-with-Maven promise (a
pull-through cache). Proving cache-miss-then-hit with faithful bytes closes the other untested serving path,
but it builds on the same harness as the hosted round-trip and is secondary to it.

**Independent Test**: Configure a proxy container repository against an in-test upstream serving a known
image, pull on a cold cache and assert the client receives the correct bytes/digests, then make the upstream
unavailable and pull the same digest again, asserting it is still served from cache byte-for-byte.

**Acceptance Scenarios**:

1. **Given** a proxy container repository over an upstream serving a known image, **When** a client pulls an
   uncached tag, **Then** the manifest and blobs are served with digests matching the upstream's, and the
   bytes are cached locally.
2. **Given** an image already pulled once through the proxy, **When** the client pulls the same digest again
   with the upstream made unavailable, **Then** the content is still served from the local cache,
   byte-for-byte.

---

### Edge Cases

- **Multi-blob / larger content**: a manifest referencing more than one layer round-trips with every blob
  intact, not just a single trivial blob.
- **Reference by digest vs. tag**: pulling a manifest by its `sha256:…` digest returns the same bytes as
  pulling by tag.
- **Cross-repository isolation**: an image in one hosted repository is not retrievable through a different
  repository's path.
- **Faithful storage under re-tag**: re-pointing a tag never mutates or re-checksums the previously stored
  manifest or blobs.
- **Environment without a real client**: when no container client binary is available, the protocol-faithful
  stand-in still exercises the identical request sequence so the guarantee is not skipped.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The verification MUST run against a real running server instance with a real storage backend
  (a real temporary filesystem location), not against mocked storage or a mocked server.
- **FR-002**: The verification MUST prove a hosted push→pull round-trip in which the pulled manifest and each
  referenced blob are byte-identical to what was pushed and carry the digests the client computed.
- **FR-003**: The verification MUST prove that an image's tag listing includes a pushed tag.
- **FR-004**: The verification MUST prove that re-pushing to an existing tag re-points the tag to the new
  digest while the previously tagged manifest remains retrievable by its digest.
- **FR-005**: The verification MUST prove that deleting a tag and deleting a manifest by digest make the
  corresponding references unretrievable afterward.
- **FR-006**: The verification MUST prove that a push to a proxy container repository is rejected as
  unsupported.
- **FR-007**: The verification MUST prove a proxy pull-through: an uncached pull is resolved from a
  controlled upstream and served with faithful bytes/digests, and a subsequent pull of the same digest is
  served from local cache without requiring the upstream.
- **FR-008**: The verification MUST assert exact byte and digest fidelity (faithful storage) at every pull —
  no silent alteration or re-checksumming of stored content.
- **FR-009**: The verification MUST prefer a real container client where one is available in the environment
  and otherwise use a protocol-faithful stand-in performing the identical request sequence; in neither case
  may the guarantee be skipped.
- **FR-010**: The verification MUST use a controlled, in-test upstream for the proxy scenario so it is
  deterministic and needs no live external registry; any test against a live public upstream MUST be
  separately gated so the core suite stays hermetic.
- **FR-011**: The work MUST NOT change any product behavior, wire protocol, or configuration; it is
  verification-only. A genuine defect uncovered by the new coverage MUST be fixed so the suite ends green.
- **FR-012**: The new coverage MUST run within the project's existing automated test gate (the same gate the
  Maven round-trip tests run in), so a future regression in the container path fails the build.

### Key Entities

- **Container image**: a config object plus one or more ordered layer blobs, described by a manifest with a
  media type; addressed by tag (mutable) and by digest (immutable).
- **Round-trip artifact set**: the exact bytes pushed (config, layers, manifest) that must be returned
  unchanged on pull, keyed by their `sha256:…` digests.
- **Upstream stand-in**: a controlled source of fixed image content the proxy scenario resolves from, in
  place of a live external registry.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A hosted container image pushed in the test is pulled back with 100% byte-and-digest identity
  for the manifest and every blob (zero tolerated differences).
- **SC-002**: The full hosted lifecycle — push, pull, tag list, re-tag with old-digest retention, tag
  delete, manifest-by-digest delete, and proxy-push rejection — is covered by passing assertions.
- **SC-003**: A proxy pull-through is proven for both cache-miss (resolved from the upstream stand-in) and
  cache-hit (served locally with the upstream unavailable), with faithful bytes/digests in both.
- **SC-004**: The core verification is hermetic and deterministic — it requires no live external registry and
  passes repeatably in the standard test gate.
- **SC-005**: Feature 018's container serving/publish path moves from zero integration coverage to a passing
  end-to-end round-trip in the automated gate, matching the Maven features' integration-verified standard.

## Assumptions

- The container registry's wire behavior is already implemented (feature 018); this feature adds proof, not
  new product behavior. Any fix required to make the proof pass is a bug fix, not a feature change.
- The real filesystem backend is the required target for the core round-trip; the S3-compatible backend is a
  desirable-but-secondary, environment-gated parity scenario (mirroring the Maven suite's backend coverage).
- A controlled in-test upstream can stand in for Docker Hub for the proxy scenario, matching how the Maven
  proxy round-trips already use a stub upstream; a live-Docker-Hub variant, if added, is separately gated so
  the default suite stays offline-safe.
- The test environment may or may not provide a real container client binary; the coverage is designed to
  use one when present and a protocol-faithful stand-in otherwise, so it never silently degrades to "no
  coverage".
- Authorization behavior for container repositories is already covered by the existing per-repository
  authorization tests (feature 007) and is not re-proven here beyond the push-rejection-on-proxy case.
