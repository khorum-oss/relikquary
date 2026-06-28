# Quickstart: Web UI Catch-up

Validates feature 008. See [contracts/ui-behavior.md](contracts/ui-behavior.md) and
[data-model.md](data-model.md) for the full contract.

## Prerequisites

- Backend running with auth enabled, a publisher user, an open `releases` repo, and a private repo:

```bash
java -jar backend/build/libs/backend.jar \
  --relikquary.security.users[0].username=alice \
  --relikquary.security.users[0].password='{noop}pw' \
  --relikquary.security.users[0].roles[0]=PUBLISH \
  --relikquary.repositories[0].name=releases --relikquary.repositories[0].type=mixed \
  --relikquary.repositories[1].name=private --relikquary.repositories[1].type=mixed \
  --relikquary.repositories[1].access.read[0]=alice \
  --relikquary.repositories[1].access.publish[0]=alice \
  --relikquary.repositories[1].access.delete[0]=alice
```

- UI in dev: `cd frontend && npm run dev` (proxies `/api` and repo paths to the backend).

## Scenario 1 — Repository kinds

Open `/`. Expected: each repository row shows its kind (hosted / proxy / group). Opening a proxy shows
its cached contents; opening a group shows an aggregate summary (not an empty folder).

## Scenario 2 — Login & private browse

1. While anonymous, open the `private` repo → the UI prompts to log in (not a raw error).
2. Log in as `alice` / `pw` → the private repo's contents load.
3. Browsing, downloading, and other actions now carry credentials with no re-prompt.
4. Log out → the private repo prompts login again; open `releases` still browses anonymously.

## Scenario 3 — Upload

1. Logged in as `alice`, open a folder in a hosted repo and choose **Upload**.
2. Pick a file; confirm the prefilled target path; submit → on success it appears in the listing.
3. Uploading over an existing immutable release shows the `409` conflict clearly.
4. A proxy/group repo offers no upload.

## Scenario 4 — Component catalog

```bash
cd frontend
npm run storybook        # dev catalog
npm run build-storybook  # static catalog (CI verifies this builds)
```

Expected: each reusable component renders in isolation with its representative states (a repository row
per kind, error/forbidden banners, empty/group states, the login and upload forms).

## Automated verification

```bash
cd frontend
npm run check            # svelte-check (types)
npm run build            # production SPA build
npm run build-storybook  # component catalog builds
bash scripts/e2e.sh      # real-browser e2e against an auth-enabled backend:
                         #   anonymous browse/download (browse.spec) +
                         #   login → private browse → upload → delete (authz.spec)
```

Expected: all green; the e2e proves the login/upload/delete round-trip and anonymous open browsing.
