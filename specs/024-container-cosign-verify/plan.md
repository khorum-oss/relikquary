# Implementation Plan: Container Image Signature Verification (cosign, advisory)

**Branch**: `024-container-cosign-verify` | **Date**: 2026-07-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/024-container-cosign-verify/spec.md`

## Summary

Compute an advisory **trust status** for a hosted container image by verifying its cosign signature artifact
against an operator-configured public key, and surface it as a badge in the web UI. Cosign stores an image's
signature in the same repository under the tag `sha256-<hex>` (the image digest with `:`→`-`); that `.sig`
manifest's layers carry a *simple-signing* payload blob (referencing the signed image digest) and a detached
signature in the `dev.cosignproject.cosign/signature` annotation. A new `CosignVerifier` resolves the
repository's public key (per-repo `cosignPublicKey`, else a global default), locates the `.sig` tag for a
digest, reads each signature layer's payload + annotation, and verifies the signature over the payload bytes
with the JDK's `Signature` (cosign's default keys are ECDSA-P256 → `SHA256withECDSA`; RSA and Ed25519 also
supported) while checking the payload's `docker-manifest-digest` equals the image. The verdict is one of
**verified / signed-but-unverified / unsigned / unknown** (unknown ⇒ no key configured; a malformed key or
signature fails closed, never falsely verified). The status is added to the tags response and the manifest
detail (feature 018/020), rendered as a `TrustBadge` on the tag view and the manifest panel. Purely advisory
and additive: no `/v2` wire change, no new stored bytes (it reads the already-pushed `.sig`), reuses the
per-repo READ authorization, and never blocks a pull/push. **No new dependency** — JDK crypto only.

## Technical Context

**Language/Version**: Backend — Kotlin on the JDK 21 toolchain (unchanged; uses `java.security` for key
parsing + signature verification). Frontend — SvelteKit 5 (runes) + TypeScript (unchanged).

**Primary Dependencies**: Backend — Spring Boot Web, the existing container persistence/storage
(`ContainerStorage`, `ContainerTagRepository`) and browse layer (018/020), Jackson (parse the `.sig`
manifest + simple-signing payload), and the **JDK `java.security`** stack (`KeyFactory`,
`X509EncodedKeySpec`, `Signature`) for PEM public-key parsing and ECDSA/RSA/Ed25519 verification. Frontend —
the existing SvelteKit app. **No new dependency** (no BouncyCastle), so `gradle/verification-metadata.xml`
is unchanged.

**Storage**: No schema change and no new stored bytes. Verification reads the already-stored `.sig` manifest
+ its payload blob by digest via `ContainerStorage`. Public keys are operator configuration (per-repo +
global default), supplied as a PEM string or a file path; never persisted by this feature.

**Testing**: A `@SpringBootTest` HTTP round-trip against real storage that generates an EC key pair in-test,
constructs a faithful cosign `.sig` artifact (payload referencing the image digest + a `SHA256withECDSA`
signature in the annotation), pushes the image and the `.sig` via `/v2`, configures the public key, and
asserts **verified**; plus **unsigned** (no `.sig`), **signed-but-unverified** (signature by a different
key / digest mismatch), **unknown** (no key), and malformed-key/payload graceful degradation. Plus a real
Playwright round-trip surfacing the trust badge. (An optional real-`cosign` IT is gated on the binary being
present.)

**Target Platform**: The existing Spring Boot backend + the SvelteKit static UI served at `/ui`.

**Project Type**: Additive change across the existing `backend` and `frontend` modules — no new module.

**Performance Goals**: A verification reads one small manifest + one small payload blob and does one
signature check per signature layer — negligible. When no key is configured (the default), it short-circuits
to **unknown** with no storage read.

**Constraints**: Advisory and additive — no `/v2` wire/behavior change, no pull/push blocking, no new stored
state; reuses the per-repo READ authorization (018/020). Fails **closed** (never falsely **verified**) on a
malformed key/signature/payload and never errors the page. New config keys (`cosignPublicKey`,
`relikquary.cosign.default-public-key`) are additive/optional. Additive capability ⇒ MINOR `VERSION` bump
(1.6.0 → 1.7.0).

**Scale/Scope**: One verifier service + a key resolver + two config additions; a `trust` field on the tags
and manifest-detail responses; a frontend `TrustBadge` wired into the tag view and manifest panel. One
backend integration test (all four statuses + degradation), one Playwright test, and the e2e seed of a signed
image.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS (additive). No change to the container `/v2` or
  Maven wire protocol, repository layout, or resolution/publish behavior. The new `cosignPublicKey` (per
  repo) and `relikquary.cosign.default-public-key` (global) are **new optional** configuration keys — adding
  keys does not break an existing config contract. The `trust` field on the browse responses is additive.
  Adding a capability ⇒ **MINOR** bump (1.6.0 → 1.7.0).
- **II. Test-First & Integration-Verified Discipline** — PASS. The change touches container browse/serving,
  so it ships a real `@SpringBootTest` round-trip against real storage that constructs and verifies a
  faithful cosign artifact (verified / unsigned / signed-but-unverified / unknown / degradation), plus a real
  Playwright round-trip for the badge. No mocked store, no mocked crypto (real JDK verification of a real
  signature).
- **III. Quality Gates Are Non-Negotiable** — PASS. New Kotlin satisfies detekt; the frontend passes
  `svelte-check` and the build. **No dependency added** (JDK crypto), so `gradle/verification-metadata.xml`
  is unchanged; no gate weakened.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS (this feature *is* supply-chain integrity). It
  verifies third-party image signatures and reports provenance without altering any stored byte,
  re-checksumming, or storing anything new. Keys are operator config supplied via env/file and never
  committed (FR-012); a verification failure never fabricates trust (fails closed).

**Result**: PASS. No deviations required (see Complexity Tracking — empty).

## Project Structure

### Documentation (this feature)

```text
specs/024-container-cosign-verify/
├── plan.md              # This file
├── research.md          # Phase 0 — cosign .sig layout, crypto choice, key config, status semantics
├── data-model.md        # Phase 1 — trust status + key resolution (no persistence change)
├── quickstart.md        # Phase 1 — runnable validation guide (incl. building a signed image)
├── contracts/
│   └── cosign-verify.md            # the trust-status projection on the browse responses + config keys
└── checklists/
    └── requirements.md  # spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── container/cosign/CosignVerifier.kt       # locate .sig, parse simple-signing layers, verify → TrustStatus
├── container/cosign/CosignKeys.kt           # resolve per-repo/global public key (PEM or file) → parsed key | none | invalid
├── config/CosignProperties.kt               # @ConfigurationProperties("relikquary.cosign") default-public-key
├── config/RepositoryProperties.kt           # + Repo.cosignPublicKey (PEM or file path)
├── container/ContainerBrowseService.kt       # tags(...) carries trust per tag; manifestDetail(...) carries trust
├── container/ContainerBrowseController.kt    # tags/manifest responses include trust
└── RelikquaryApplication.kt                 # + CosignProperties in @EnableConfigurationProperties

backend/src/test/kotlin/org/khorum/oss/relikquary/integration/
└── ContainerCosignVerifyApiTest.kt          # verified / unsigned / signed-but-unverified / unknown / degradation

backend/src/test/resources/
└── application-cosign-it.yml                # a hosted container repo with a configured cosign public key

frontend/src/
├── lib/api.ts                              # + `trust` on ContainerTagSummary + ManifestDetail
├── lib/components/TrustBadge.svelte         # verified / signed / unsigned / unknown badge
├── routes/c/[repo]/[...image]/+page.svelte  # trust badge per tag row
└── lib/components/ManifestDetail.svelte     # trust badge in the manifest panel

frontend/tests/
├── container.spec.ts                       # + the trust badge renders for an image
└── scripts/e2e.sh + e2e-config.yml         # configure a key + seed a signed image (openssl) so the badge is 'verified'

VERSION                                     # 1.6.0 → 1.7.0 (additive capability)
```

**Structure Decision**: Additive change to the existing `backend` and `frontend` modules — no new module.
Verification lives in a new `container/cosign/` package (the verifier + key resolver), surfaced through the
existing feature-018/020 browse layer; the frontend adds one `TrustBadge` wired into the two existing
container views. Crypto is JDK-only.

## Complexity Tracking

Constitution Check passed with no violations; no deviation to justify. (Table intentionally empty.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 design (data-model, contract, quickstart): unchanged — PASS on all four
principles, no new violations. The design adds an advisory read-only verifier plus additive config keys and
response fields, changes no wire/resolution behavior, stores nothing, fails closed on bad input, adds no
dependency, and proves itself with a real HTTP round-trip verifying a real signature plus a real browser
round-trip.
