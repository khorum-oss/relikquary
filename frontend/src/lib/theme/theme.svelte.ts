// The active web theme (feature 019). A module-level rune holds the current choice; components read it via
// currentTheme() and mutate it via setTheme(). The choice persists two ways: always to localStorage (so it
// applies instantly on the next load, and works for anonymous visitors), and — when signed in — to the
// server (so it follows the user across devices). On login, the server copy is authoritative.
import { browser } from '$app/environment';
import { authHeader } from '../auth.svelte';
import { getMyPreferences, saveMyPreferences } from '../api';
import { DEFAULT_PRESET, isHexColor, isPreset, resolveVars, type ThemeChoice } from './presets';

const CHOICE_KEY = 'relikquary.theme';
/** The pre-resolved `--rq-*` map, read by the inline boot script in app.html to avoid a flash of the default theme. */
const VARS_KEY = 'relikquary.theme.vars';

function sanitize(raw: unknown): ThemeChoice {
  const candidate = raw as Partial<ThemeChoice> | null;
  const preset = candidate && isPreset(candidate.preset) ? (candidate.preset as string) : DEFAULT_PRESET;
  const accent = candidate && isHexColor(candidate.accent) ? candidate.accent : null;
  return { preset, accent };
}

function restore(): ThemeChoice {
  if (!browser) return { preset: DEFAULT_PRESET, accent: null };
  try {
    const raw = localStorage.getItem(CHOICE_KEY);
    if (raw) return sanitize(JSON.parse(raw));
  } catch {
    // Corrupt/absent value → fall through to the default.
  }
  return { preset: DEFAULT_PRESET, accent: null };
}

let choice = $state<ThemeChoice>(restore());

/** The current theme choice. Reactive — safe to read in markup / `$derived`. */
export function currentTheme(): ThemeChoice {
  return choice;
}

/** Applies the current choice to the document root by setting the `--rq-*` custom properties. */
export function applyTheme(): void {
  if (!browser) return;
  const root = document.documentElement;
  for (const [prop, value] of Object.entries(resolveVars(choice))) root.style.setProperty(prop, value);
}

/**
 * Sets and applies a new theme, persisting to localStorage always and to the server when signed in. Pass
 * `persist: false` when the value just came from the server (so it is not immediately written back).
 */
export function setTheme(next: ThemeChoice, opts: { persist?: boolean } = {}): void {
  choice = sanitize(next);
  applyTheme();
  if (browser) {
    localStorage.setItem(CHOICE_KEY, JSON.stringify(choice));
    localStorage.setItem(VARS_KEY, JSON.stringify(resolveVars(choice)));
  }
  if (opts.persist !== false && authHeader()) {
    void saveMyPreferences(choice).catch(() => {
      // Best-effort: a failed server save still leaves the local choice applied and stored.
    });
  }
}

/**
 * Reconciles with the server for a signed-in user: adopts the server's saved theme when present (server
 * wins), otherwise seeds the server from the local choice. A no-op (keeps the local theme) when anonymous
 * or offline.
 */
export async function syncTheme(): Promise<void> {
  if (!browser || !authHeader()) return;
  try {
    const server = await getMyPreferences();
    if (server) setTheme(server, { persist: false });
    else void saveMyPreferences(choice).catch(() => {});
  } catch {
    // 401 / network error → keep the locally stored theme.
  }
}
