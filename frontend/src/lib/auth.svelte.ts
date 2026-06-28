// Client-side login session (feature 008). Credentials are held in sessionStorage — they survive a page
// reload within the tab but clear when the tab/window closes — and sent as HTTP Basic on every request
// via authHeader(). The backend stays stateless; "log in" is purely a client affordance.
import { basicAuth } from './api';

const STORAGE_KEY = 'relikquary.auth';

interface Session {
  username: string;
  header: string;
}

function restore(): Session | null {
  if (typeof sessionStorage === 'undefined') return null;
  const raw = sessionStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Session;
    return parsed.username && parsed.header ? parsed : null;
  } catch {
    return null;
  }
}

let session = $state<Session | null>(restore());

/** Store credentials for the session (does not validate them — validation is lazy, on the next request). */
export function login(username: string, password: string): void {
  session = { username, header: basicAuth(username, password) };
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  }
}

/** Clear the session; subsequent requests are anonymous. */
export function logout(): void {
  session = null;
  if (typeof sessionStorage !== 'undefined') sessionStorage.removeItem(STORAGE_KEY);
}

/** The current username, or null when anonymous. Reactive (use in markup/`$derived`). */
export function currentUser(): string | null {
  return session?.username ?? null;
}

/** The `Basic …` Authorization value to attach to requests, or null when anonymous. */
export function authHeader(): string | null {
  return session?.header ?? null;
}
