import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

// During `vite dev`, proxy the backend API and any Maven repo path (so downloads/uploads to ANY
// repository work, not just releases/snapshots) to the running backend. The SPA's own client routes
// live at `/`, `/r/...`, and the top-level sections `/dashboard`, `/publish`, `/users`, `/settings`
// (feature 016), and Vite's dev assets under `/@…`, `/_app`, `/src`, `/node_modules`, `/.svelte-kit`;
// everything else with a path segment is a backend request. Override with RELIKQUARY_BACKEND. In the
// bundled (production) build the UI is same-origin with the backend (adapter-static fallback serves
// these routes), so no proxy is involved.
const backend = process.env.RELIKQUARY_BACKEND ?? 'http://localhost:8080';

// Single source of truth for the displayed product version is the repo-root VERSION file. It sits at
// ../VERSION for local `npm run build` and the combined image (whole-repo context), and at ./VERSION inside
// the split frontend image (see deploy/frontend.Dockerfile, which COPYs it in). First existing wins.
const __configDir = dirname(fileURLToPath(import.meta.url));
const __versionFile = [resolve(__configDir, '../VERSION'), resolve(__configDir, 'VERSION')].find(existsSync);
const appVersion = __versionFile ? readFileSync(__versionFile, 'utf-8').trim() : '0.0.0';

export default defineConfig({
  plugins: [sveltekit()],
  define: {
    __APP_VERSION__: JSON.stringify(appVersion),
  },
  server: {
    proxy: {
      '^/(?!r(?:/|$)|(?:dashboard|publish|users|settings)(?:/|$)|_app/|@|src/|node_modules/|\\.svelte-kit/|favicon)[^/].*':
        backend,
    },
  },
});
