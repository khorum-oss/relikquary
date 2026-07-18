# Phase 0 Research: Container Image Signature Verification (cosign, advisory)

Decisions resolved from the cosign/sigstore signature spec and the existing container layer (018/020/022);
no open NEEDS CLARIFICATION remained.

## D1. Where a cosign signature lives and how it is laid out

- **Decision**: For an image whose manifest digest is `sha256:<hex>`, cosign stores its signature as a
  companion **image manifest** in the **same repository and image name**, tagged `sha256-<hex>` (the digest
  with `:`→`-`). That `.sig` manifest's `layers[]` each represent one signature:
  - layer `mediaType` = `application/vnd.dev.cosign.simplesigning.v1+json`;
  - the layer **blob** (addressed by the layer digest) is the *simple-signing payload* JSON:
    `{"critical":{"identity":{"docker-reference":"…"},"image":{"docker-manifest-digest":"sha256:…"},"type":"cosign container image signature"},"optional":{…}}`;
  - the layer **annotation** `dev.cosignproject.cosign/signature` = base64 of the raw signature over the
    payload blob bytes. (Keyless-only annotations `dev.sigstore.cosign/certificate` and `…/bundle` are
    ignored — out of scope.)
- **Rationale**: This is the documented cosign storage convention; Relikquary already stores the `.sig` tag,
  its manifest, and its payload blob exactly like any other pushed content (features 018), so verification is
  a pure read + crypto check over already-stored bytes.
- **Alternatives considered**: The OCI 1.1 Referrers API (`subject`-linked signatures) — out of scope; the
  `.sig`-tag convention is what cosign uses by default and what feature 018 already stores. Referrers can be
  a later enhancement.

## D2. Crypto — verify with the JDK, no new dependency

- **Decision**: Parse the configured PEM public key (`-----BEGIN PUBLIC KEY-----`, a DER SubjectPublicKeyInfo)
  with `KeyFactory` + `X509EncodedKeySpec`, and verify each signature with `java.security.Signature`,
  choosing the algorithm from the key type: **EC → `SHA256withECDSA`** (cosign's default, prime256v1), **RSA →
  `SHA256withRSA`**, **EdDSA → `Ed25519`**. The signed data is the payload blob bytes verbatim; the signature
  bytes are the base64-decoded annotation (cosign emits ASN.1/DER ECDSA signatures, which `SHA256withECDSA`
  consumes directly).
- **Rationale**: JDK 21 covers EC/RSA/Ed25519 natively — no BouncyCastle — keeping the dependency set (and
  `verification-metadata.xml`) untouched (Principle III). ECDSA-P256 is cosign's default, so the common case
  works out of the box.
- **Alternatives considered**: BouncyCastle / the sigstore-java library — rejected: a new (and heavy)
  dependency for functionality the JDK already provides for key-based verification. The sigstore library is
  oriented at keyless, which is out of scope.

## D3. Trust-status semantics (fail closed)

- **Decision**: Compute one status per image digest:
  - **unknown** — no key resolved for the repo (no per-repo key and no global default). Short-circuit before
    any storage read.
  - **verified** — the `.sig` exists and **at least one** signature layer verifies against the resolved key
    **and** its payload's `docker-manifest-digest` equals the image digest.
  - **signed-but-unverified** — the `.sig` exists but no layer both verifies and matches the digest (wrong
    key, digest mismatch, or unusable/keyless-only content).
  - **unsigned** — no `.sig` tag for the digest.
  - A **malformed/unreadable key** resolves as *invalid* → behaves like "verification always fails": a `.sig`
    ⇒ signed-but-unverified, none ⇒ unsigned (never **verified**, never **unknown**). A malformed manifest/
    payload for one layer is skipped; the page never errors.
- **Rationale**: Never fabricating **verified** is the safety property (SC-002/SC-004); distinguishing
  **unknown** (no judgment possible) from a negative judgment keeps the badge honest (SC-003).
- **Alternatives considered**: Treating a malformed configured key as **unknown** — rejected: a key *was*
  configured, so silently downgrading to "no judgment" hides an operator error; failing closed is safer.

## D4. Resolving the key (per-repo + global default; PEM or file)

- **Decision**: A `CosignKeys` component resolves, for a repo: its own `cosignPublicKey` if set, else the
  global `relikquary.cosign.default-public-key`. The value is either an inline PEM (`-----BEGIN PUBLIC
  KEY-----…`) or a filesystem path to a PEM file (chosen by whether the value starts with the PEM header).
  Resolved keys are parsed once and cached per (repo → key), yielding `None` / `Key(publicKey)` / `Invalid`.
- **Rationale**: Matches the project's "secrets via env/file, never committed" rule (Principle IV / FR-012) —
  an operator points at a mounted key file or injects the PEM via env. Per-repo with a global default
  (FR-005) mirrors how proxy upstream credentials are already configured per repo.
- **Alternatives considered**: A single global key only — rejected: FR-005 requires per-repo keys with a
  default. A keystore/secret-manager integration — out of scope; env/file matches existing conventions.

## D5. Where the status is surfaced (and kept cheap)

- **Decision**: Add a `trust` field to each tag in the tags response and to the manifest-detail response
  (features 018/020). The tag view badges each tag; the manifest panel badges the drilled manifest. When no
  key is configured the verifier returns **unknown** immediately (no per-tag storage reads), so the common
  unconfigured case adds no cost.
- **Rationale**: FR-008 asks for the badge on both the tag view and the manifest detail; both already fetch
  per-image data, so attaching `trust` there avoids a new endpoint. The short-circuit keeps the default path
  free.
- **Alternatives considered**: A separate `/verify` endpoint the UI calls per image — rejected: an extra
  round-trip for data the tag/manifest responses can carry; the browse responses are the natural home.

## D6. Hermetic testing without the cosign binary

- **Decision**: The backend test generates an EC (P-256) key pair with `KeyPairGenerator`, builds the
  simple-signing payload for the pushed image digest, signs the payload bytes with `SHA256withECDSA`, and
  pushes a `.sig` artifact (payload blob + a manifest whose layer carries the simplesigning mediaType and the
  base64 signature annotation) under `sha256-<hex>` — a faithful cosign artifact produced entirely in-JVM.
  The public key (PEM) is configured; the test asserts each status. The e2e seed uses `openssl` to the same
  end for a real-browser badge. A real-`cosign` IT is optional and gated on the binary.
- **Rationale**: Reproduces the exact bytes cosign would store and verifies with the real JDK crypto path, so
  the test proves the production verifier — hermetic, no external tool, no network (mirrors how feature 021's
  `OciStubUpstream` avoids external services).
- **Alternatives considered**: Requiring the `cosign` CLI in tests — rejected: non-hermetic and unavailable
  in constrained environments; the in-JVM artifact is byte-faithful for key-based verification.
