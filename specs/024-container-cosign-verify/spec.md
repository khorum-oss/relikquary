# Feature Specification: Container Image Signature Verification (cosign, advisory)

**Feature Branch**: `024-container-cosign-verify`

**Created**: 2026-07-18

**Status**: Draft

**Input**: User description: "Container image signature verification (cosign, key-based, advisory). Let
Relikquary tell a user whether a hosted container image is signed and whether that signature is trusted, by
verifying cosign signatures against a configured public key, and surface the trust status in the web UI …
advisory and additive: it never blocks or alters a docker pull/push … Out of scope: keyless/Fulcio/Rekor,
enforcement/blocking, Relikquary signing images, Maven artifacts, and proxy repositories."

## Clarifications

### Session 2026-07-18

- Q: What is verified, and against what? → A: A hosted container image's **cosign signature artifact** — the
  companion object cosign stores in the same repository under the `sha256-<hex>.sig` convention — is verified
  against a **public key configured for the repository**. Verification confirms the key signed the signature
  payload and that the payload references the image's digest. This is **key-based** cosign verification.
- Q: What trust statuses are surfaced? → A: **verified** (a signature validates against the configured key
  and matches the image digest), **signed-but-unverified** (a signature artifact exists but none validate
  against the key), **unsigned** (no signature artifact present), and **unknown** (no key configured for the
  repository, so no judgment is made).
- Q: Does verification affect pulls or pushes? → A: No. It is **advisory and additive** — it never blocks or
  alters a `docker pull`/`push`, changes no `/v2` wire behavior, and stores nothing new. It reads the
  already-stored signature artifact and reports a status.
- Q: How is the key configured? → A: A cosign **public key per repository**, with an optional **global
  default** key. Keys are configuration supplied by the operator (e.g. via file/env), never committed as a
  secret. A repository with no key (and no global default) yields **unknown**.
- Q: What is explicitly excluded? → A: Keyless/Fulcio/Rekor (transparency-log and OIDC-identity)
  verification; enforcement/blocking of unsigned or invalid pulls; Relikquary signing images itself;
  signature verification for Maven artifacts; and proxy repositories.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See that a signed image is trusted (Priority: P1)

An operator has configured a cosign public key for a hosted container repository and pushed a signed image
(the image plus its cosign signature artifact). Browsing that image in the web UI, they see a clear **trust
badge** indicating the image is **verified** — its signature validates against the configured key and covers
this exact image. Browsing an image that was pushed without a signature, they see **unsigned**; an image
whose signature does not validate against the key shows **signed-but-unverified**.

**Why this priority**: Knowing an image is authentically signed by a trusted key is the entire point —
it turns Relikquary from a store of bytes into one that can vouch for provenance, directly serving the
project's supply-chain-integrity principle. Surfacing a correct verified/unsigned distinction is a complete,
useful feature on its own.

**Independent Test**: With a public key configured for a hosted repo, push a signed image and confirm the UI
shows **verified**; push an unsigned image and confirm **unsigned**; present a signature that does not match
the key and confirm **signed-but-unverified**.

**Acceptance Scenarios**:

1. **Given** a hosted repo with a configured cosign public key and an image signed by the matching private
   key, **When** the user views the image, **Then** its trust status is **verified**.
2. **Given** the same repo and an image pushed with no signature artifact, **When** the user views it,
   **Then** its trust status is **unsigned**.
3. **Given** the same repo and an image whose signature was made by a different key, **When** the user views
   it, **Then** its trust status is **signed-but-unverified**.
4. **Given** any of the above, **When** the status is shown, **Then** a `docker pull` of the image is
   unaffected (the same bytes are served whether or not the image is verified).

---

### User Story 2 - Configuration and honest unknowns (Priority: P2)

The trust judgment is only as meaningful as the key behind it, so the feature is explicit about when it
cannot judge. A repository with no configured key (and no global default) reports **unknown** rather than
implying trust or distrust. A configured global default key applies to repositories that do not set their
own. A malformed or unreadable key, or a malformed signature artifact, degrades to a safe status (never
falsely **verified**) and never errors the page.

**Why this priority**: A verification feature that silently implies trust where none was checked is worse
than none. Getting the "unknown" and failure semantics right is what makes the badge trustworthy; it builds
directly on User Story 1.

**Independent Test**: With no key configured, confirm images report **unknown**; set a global default key and
confirm repositories without their own key use it; present a malformed key or signature and confirm the
status is a safe non-verified value with no page error.

**Acceptance Scenarios**:

1. **Given** a hosted repo with no configured key and no global default, **When** the user views any image,
   **Then** its trust status is **unknown**.
2. **Given** a configured global default key and a repo that sets no key of its own, **When** the user views a
   signed image in that repo, **Then** it is verified against the global default key.
3. **Given** a repo whose configured key is malformed, or an image whose signature artifact is malformed,
   **When** the user views the image, **Then** the status is a safe non-**verified** value and the page still
   renders.

---

### Edge Cases

- **Multiple signatures**: an image with more than one signature (e.g. signed by two keys) is **verified** if
  at least one signature validates against the configured key.
- **Signature covers a different image**: a signature whose payload references a different digest than the
  image being viewed does not count as verification (it is not **verified** for this image).
- **Signature artifact present but empty/!signatures**: a `sha256-<hex>.sig` object with no usable signature
  content is treated as **signed-but-unverified**, not **verified**.
- **Proxy / Maven / non-container**: the trust badge is not shown for proxy container repositories, Maven
  repositories, or non-image entries — verification applies only to hosted container images.
- **Unauthorized viewer**: the trust status is only computed and shown to a user permitted to READ the
  repository (it never leaks a private repo's images or their status).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST compute a trust status for a hosted container image by locating its cosign
  signature artifact (the `sha256-<hex>.sig` companion in the same repository) and verifying it against the
  repository's configured public key.
- **FR-002**: Verification MUST confirm both that the configured public key produced the signature over the
  signature payload AND that the payload references the digest of the image being judged.
- **FR-003**: The trust status MUST be one of **verified**, **signed-but-unverified**, **unsigned**, or
  **unknown**, with the meanings defined in the clarifications.
- **FR-004**: The system MUST return **unknown** when no public key is configured for the repository and no
  global default key is configured — it MUST NOT imply trust or distrust without a key.
- **FR-005**: A configured **global default** public key MUST apply to repositories that do not configure
  their own; a repository's own key MUST take precedence over the default.
- **FR-006**: An image with **multiple** signatures MUST be **verified** if at least one validates against the
  configured key and matches the image digest.
- **FR-007**: A malformed/unreadable key, or a malformed/absent signature payload, MUST degrade to a safe
  non-**verified** status and MUST NOT surface an error to the user or fail the page.
- **FR-008**: The trust status MUST be surfaced as a badge on the image's tag view and on its manifest detail,
  consistent with the vault-themed frontend.
- **FR-009**: The feature MUST be advisory and additive — it MUST NOT block, delay, or alter any `docker`
  pull/push, MUST NOT change the `/v2` wire behavior, and MUST NOT store any new artifact bytes (it reads the
  already-stored signature artifact).
- **FR-010**: Verification MUST apply only to **hosted container images**; it MUST NOT be offered for proxy
  container repositories, Maven repositories, or non-image entries.
- **FR-011**: Trust status MUST respect per-repository READ authorization — it is computed/shown only for a
  user permitted to read the repository.
- **FR-012**: Configured keys MUST be treated as operator-supplied configuration and MUST NOT be committed as
  secrets in the repository.

### Key Entities

- **Cosign signature artifact**: the companion object stored under `sha256-<hex>.sig` for an image digest,
  whose content carries a signed payload and the signature over it.
- **Signature payload**: the signed statement that references the image digest the signature attests to.
- **Configured public key**: the operator-supplied cosign public key for a repository (or the global
  default) against which signatures are verified.
- **Trust status**: the computed verdict for an image — verified / signed-but-unverified / unsigned /
  unknown.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For an image signed by the private key matching a repository's configured public key, the UI
  reports **verified** 100% of the time; for an unsigned image, **unsigned**; for a signature made by a
  non-matching key, **signed-but-unverified**.
- **SC-002**: A signature that does not cover the viewed image's digest never yields **verified** for that
  image (0% false-verified across the digest-mismatch cases).
- **SC-003**: With no key configured, the status is **unknown** — the system never displays **verified** or
  **unsigned** as if a judgment were made.
- **SC-004**: A malformed key or signature never produces **verified** and never errors the page (100%
  graceful degradation).
- **SC-005**: Enabling verification changes no `docker pull`/`push` outcome — the exact same bytes are served
  and no push is rejected because of signature status.
- **SC-006**: Trust status is never shown to a user who cannot READ the repository (zero authorization
  leakage).

## Assumptions

- The signature artifact is already present in the repository when signed — cosign (or an equivalent client)
  pushed it alongside the image using the standard `sha256-<hex>.sig` convention. Relikquary reads it; it does
  not create signatures.
- "Key-based" cosign verification is the scope: a configured public key. Keyless verification (Fulcio
  certificates + Rekor transparency log + OIDC identity) is explicitly out of scope and, when a signature is
  keyless-only, it simply will not validate against a configured public key (→ signed-but-unverified).
- The cosign simple-signing format (a payload referencing the image digest plus a detached signature carried
  by the signature artifact) is the format verified; other/legacy signing schemes are out of scope.
- Public keys are provided as operator configuration (per repository, plus an optional global default) via
  the existing configuration mechanism, supplied as files/environment and never committed.
- Verification is advisory: it reuses the stored bytes and the existing per-repository READ authorization; it
  introduces no new stored state and no new authorization model, and it does not touch the `/v2` pull/push
  path.
- Proxy repositories are excluded because a proxy caches an upstream's bytes and is not the signing
  authority; verifying cached signatures there is out of scope for this feature.
