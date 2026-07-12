# Quickstart & Validation: Frontend Theme

Runnable validation that a user can change the web theme and that it persists — locally always, and per user
on the server when signed in. Assumes the implementation from `tasks.md` is in place.

## Prerequisites

- Backend running (JDK 21). For a local auth-on run, e.g. the e2e config used elsewhere
  (`frontend/scripts/e2e-config.yml`: user `alice`/`pw`), or any config with a user.
- Frontend dev server: `cd frontend && npm ci && npm run dev` (proxies `/api` to the backend on `:8080`).
- A browser.

## Scenario A — pick a palette (anonymous, local persistence)

1. Open the app, go to **Settings**.
2. Under **Palette**, click **Emerald**.
   - ✅ The whole UI re-skins immediately (accents/borders/text turn green).
   - ✅ The Emerald card is marked active.
3. Reload the page.
   - ✅ The app opens in Emerald with **no flash** of the default gold first (the `app.html` boot step
     applies the saved tokens before first paint).

## Scenario B — custom accent + reset

1. On **Settings**, under **Accent colour**, open the colour picker and choose e.g. `#3366cc`.
   - ✅ The accent/text tones update live to the chosen blue; the value shows `#3366cc`.
2. Reload — ✅ the custom accent persists.
3. Click **Use preset default**.
   - ✅ The accent reverts to the preset's own accent; the reset control disappears.

## Scenario C — per-user, cross-browser (server persistence)

1. Sign in (e.g. `alice` / `pw`).
2. On **Settings**, choose **Crimson** with accent `#d24b4b`.
   - ✅ The hint reads "Saved to your account — it follows you across devices."
3. Open a different browser (or a private window), sign in as **alice**.
   - ✅ The UI adopts Crimson (the server copy is authoritative on sign-in).
4. Sign in as a **different** user in that window.
   - ✅ They do NOT see alice's theme (per-user isolation).

## Scenario D — API + validation (curl)

```bash
BASE=http://127.0.0.1:8080
# Anonymous → 401
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/api/me/preferences"                       # 401

# Save + read back (authenticated)
curl -s -u alice:pw -H 'Content-Type: application/json' \
  -X PUT "$BASE/api/me/preferences" -d '{"preset":"emerald","accent":"#112233"}'           # {"theme":{...}}
curl -s -u alice:pw "$BASE/api/me/preferences"                                             # {"theme":{"preset":"emerald","accent":"#112233"}}

# Malformed → 400 (nothing stored)
curl -s -o /dev/null -w '%{http_code}\n' -u alice:pw -H 'Content-Type: application/json' \
  -X PUT "$BASE/api/me/preferences" -d '{"preset":"neon-pink"}'                            # 400
```

## Automated equivalents

- **Server round-trip**: `./gradlew :backend:test --tests '*PreferenceApiTest'` — save/read, per-user
  isolation, `400` on a malformed theme, `401` when anonymous (real `@SpringBootTest` against the datastore).
- **UI round-trip**: `cd frontend && npx playwright test tests/theme.spec.ts` — preset + custom accent
  re-skin the app, persist across reload, and reset falls back (real Chromium against `vite dev`).
