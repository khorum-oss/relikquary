# Hestia Systems — Issue backlog

Ready-to-cut tickets discovered during investigations. Newest cluster first.
Severity: **High** = blocks a real workflow / data-loss risk · **Med** = correctness/hardening · **Low** = polish.

Legend: `[ ]` open · `[~]` in progress · `[x]` done

---

## From: Relikquary stage delete-401 (2026-07-07)
Source: [`../investigations/2026-07-07-relikquary-stage-delete-401.md`](../investigations/2026-07-07-relikquary-stage-delete-401.md)

### [ ] HS-1 — ⭐ Unvalidated login accepts anything → phantom "signed-in" session  ·  **High**  ·  area: frontend/auth-UX
- **Problem:** `login()` stores whatever username/password you type **without validating it** (no server round-trip; validation is "lazy, on the next request"). The sidebar then renders you as **signed in as that arbitrary name** (observed: `poser`) with a Sign Out button, while the page still shows "Log in to continue" — two components disagreeing about auth state. Worse: the invalid `Authorization: Basic …` header then rides on **every** request, so previously-anonymous reads (e.g. `GET /api/repositories`) flip **200 → 401** — a bad login breaks browsing too. This is the true root of the reported incident.
- **Repro:** Click Sign In → enter any garbage user/pass → sidebar shows you "logged in"; any API action 401s; screenshot `relikquary-phantom-session.png`.
- **Fix direction:** Validate credentials at login time with a cheap authenticated probe (e.g. `GET /api/repositories` *with* the header, or a dedicated `whoami`) before storing the session; only show a signed-in identity the server actually accepted; on a failed retry, **clear** the session (call `logout()`) instead of leaving a bogus one in place; reconcile the sidebar and page views to a single source of truth.
- **Acceptance:** Entering wrong credentials shows an error and leaves you *not* signed in (no phantom username, no broken anonymous browsing). Only server-accepted credentials produce a "signed in" state.
- **Files:** `frontend/src/lib/auth.svelte.ts` (`login`), `frontend/src/routes/+layout.svelte` (`doLogin`, sidebar), `frontend/src/routes/r/[repo]/[...path]/+page.svelte` (`onLogin`).

### [ ] HS-2 — Login form hints `admin`, but the only account is `publisher`  ·  **High**  ·  area: frontend/UX
- **Problem:** The username field placeholder is `admin`; stage has no `admin` user (only `publisher`). Users type `admin`, and combined with HS-1 the app *looks* logged in while every request 401s. Compounded the incident.
- **Fix options:** (a) remove/neutralize the misleading placeholder; (b) seed an `admin` account in stage; (c) surface the expected username near the form. (a)+(c) is cheapest and truthful.
- **Acceptance:** A first-time user can tell which username to use without reading the cluster secret.
- **Files:** `frontend/src/lib/components/LoginForm.svelte`, stage overlay under `relikqary/deploy`.

### [ ] HS-3 — Stage delete credentials only lived in an ephemeral scratchpad  ·  **High**  ·  area: ops/secrets
- **Problem:** The `publisher` password was only recorded in a session scratchpad (per stage Setup Notes). If lost, deleting/publishing is impossible without `kubectl` access to the secret. No durable, discoverable source of truth.
- **Fix:** Store stage/prod credentials in 1Password (or chosen manager); reference them from the deploy overlay; document retrieval in the runbook.
- **Acceptance:** Creds are retrievable from a known vault entry; rotating them is a documented one-step change.

### [ ] HS-4 — Stage password stored as `{noop}` plaintext  ·  **Med**  ·  area: security/deploy
- **Problem:** `RELIKQUARY_SECURITY_USERS_0_PASSWORD = {noop}…` — unencoded. Acceptable for a throwaway testing ground, but must not carry into any durable/prod overlay.
- **Fix:** Use `{bcrypt}`-encoded passwords in durable overlays; keep `{noop}` only for local-dev. Add a guard/check so prod overlays can't ship `{noop}`.
- **Acceptance:** Prod/durable overlays use `{bcrypt}`; a lint/test fails on `{noop}` outside local.

### [ ] HS-5 — UI doesn't distinguish "not logged in" vs "wrong password" vs "no permission"  ·  **Med**  ·  area: frontend/UX
- **Problem:** The first (anonymous) 401 silently opens the login form with no reason; only the retry shows "Invalid credentials." A 403 (real permission gap) would show a generic forbidden banner. Hard for a user to self-diagnose.
- **Fix:** Message the *why*: prompt "Sign in to delete" on the first 401; keep "Invalid credentials." on a failed retry; keep a distinct 403 message. Optionally note the required role.
- **Acceptance:** Each of the three states yields a distinct, actionable message.
- **Files:** `frontend/src/routes/r/[repo]/[...path]/+page.svelte` (`handle`, `onLogin`).

### [ ] HS-6 — Benign Spring startup WARN (UserDetailsService/AuthenticationProvider)  ·  **Low**  ·  area: backend/noise
- **Problem:** Boot logs a WARN that `UserDetailsService beans will not be used …` because a custom `AuthenticationProvider` is wired. It's intentional (token provider + explicit `ProviderManager`), so the warning is noise that muddies future log reading.
- **Fix:** Follow Spring's guidance to silence it (raise that logger to ERROR, or restructure per the message).
- **Acceptance:** Clean startup logs; no misleading auth WARN.

### [ ] HS-7 — Folder delete cascades with no count in the confirm  ·  **Low**  ·  area: frontend/UX + safety
- **Problem:** Deleting a folder prefix (e.g. `com/`) removes everything beneath it; the confirm only says "Delete com?" with no indication of how many artifacts will be destroyed.
- **Fix:** Show affected count / recursive warning in the confirm for folder deletes.
- **Acceptance:** Folder deletes warn "this deletes N items" (or similar) before proceeding.
- **Files:** `frontend/src/routes/r/[repo]/[...path]/+page.svelte` (`remove`), `FileListing.svelte`.

---

## How to cut these
These map cleanly to GitHub issues or Jira tickets (both MCP integrations are available in this session).
Suggested first PR: **HS-1 + HS-2 + HS-5** together (validate login, fix the misleading `admin` hint, and clarify auth-state messaging — a focused frontend auth-UX pass that would have prevented this incident).
