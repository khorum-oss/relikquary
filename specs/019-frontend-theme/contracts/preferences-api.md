# Contract: Current-User Preferences API (`/api/me/preferences`)

The signed-in user's own UI preferences (feature 019) — currently just the web theme. Scoped to the
authenticated principal (resolved from the security context), so a user can only read/write their own
choice. Distinct from the `/api/admin` surface: **any** authenticated principal may use it — no publish/admin
role required.

## Authorization

- When security is enabled: the request MUST be authenticated (HTTP Basic, exactly like the rest of the API,
  including an `rlq_…` API token as the password). Any authenticated role (or none) is permitted.
- An anonymous request ⇒ **401 Unauthorized** (the standard challenge; browsers get a challenge-less 401 via
  the existing `X-Requested-With` handling so the web UI shows its own login).
- When security is disabled (local dev): the endpoint has no principal to key on; the web client keeps its
  theme in local storage and does not depend on the server.

## `GET /api/me/preferences`

Read the current user's saved theme.

**Response 200** — `application/json`:

```json
{ "theme": { "preset": "emerald", "accent": "#112233" } }
```

- `theme` is `null` when the user has not chosen one (or a stored value was unreadable):

```json
{ "theme": null }
```

**Response 401** — anonymous request.

## `PUT /api/me/preferences`

Create or replace the current user's theme (idempotent upsert).

**Request** — `application/json`:

```json
{ "preset": "emerald", "accent": "#112233" }
```

- `preset` (required) — one of `vault-gold`, `emerald`, `crimson`, `slate` (case-insensitive; stored
  lowercase).
- `accent` (optional) — a `#rrggbb` hex, or `null`/omitted to use the preset's own accent.

**Response 200** — the stored theme, echoed in the same envelope as `GET`:

```json
{ "theme": { "preset": "emerald", "accent": "#112233" } }
```

**Response 400** — malformed theme: an unknown `preset`, or an `accent` that is not `#rrggbb`. Body:

```json
{ "error": "unknown theme preset: 'neon-pink' (expected one of [vault-gold, emerald, crimson, slate])" }
```

**Response 401** — anonymous request. Nothing is stored.

## Semantics

- **Per-principal**: the row is keyed by the authenticated username; one user never reads or writes
  another's theme. There is no id in the path.
- **Upsert**: `PUT` replaces the whole theme for the user and stamps `updated_at`.
- **Validation before storage**: a malformed theme is rejected (400) and nothing is written.
- **Graceful read**: an unreadable stored value is returned as `{ "theme": null }`, not an error.
- **Additive**: this endpoint is new; it does not alter any existing `/api` route, the Maven wire protocol,
  or the container `/v2` surface.
