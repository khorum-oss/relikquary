// Typed client for the Relikquary browse/manage API + Maven download/upload (features 005–008).
// Credentials from the login session (auth.svelte.ts) are attached to every request; failures throw
// ApiError carrying the HTTP status so callers can drive 401→login / 403→forbidden / 409→conflict UX.
import { authHeader } from './auth.svelte';

/** Thrown on a non-OK response; `status` is the HTTP status code. */
export class ApiError extends Error {
  constructor(public readonly status: number, message?: string) {
    super(message ?? `HTTP ${status}`);
    this.name = 'ApiError';
  }
}

export interface RepositorySummary {
  name: string;
  /** Hosted acceptance/mutability policy (release/snapshot/mixed). */
  type: string;
  /** How the repository resolves: HOSTED, PROXY, or GROUP (feature 006). */
  kind: string;
}

export interface ListingEntry {
  name: string;
  kind: 'folder' | 'file';
  size?: number;
  lastModified?: string;
}

export interface ContentsResponse {
  repository: string;
  path: string;
  entries: ListingEntry[];
}

export interface FileDetails {
  repository: string;
  path: string;
  size: number;
  lastModified?: string;
  checksums: Record<string, string>;
  downloadUrl: string;
}

/** Merge the session Authorization header into a request init. The X-Requested-With marker tells the
 * backend to omit the Basic-auth challenge on 401, so the browser shows our login form instead of its
 * native auth dialog. */
function authed(init: RequestInit = {}): RequestInit {
  const headers = new Headers(init.headers);
  headers.set('X-Requested-With', 'XMLHttpRequest');
  const header = authHeader();
  if (header) headers.set('Authorization', header);
  return { ...init, headers };
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, authed());
  if (!res.ok) throw new ApiError(res.status);
  return (await res.json()) as T;
}

export function listRepositories(): Promise<RepositorySummary[]> {
  return getJson('/api/repositories');
}

export function listContents(repo: string, path: string): Promise<ContentsResponse> {
  const suffix = path ? `/${path}` : '';
  return getJson(`/api/repositories/${repo}/contents${suffix}`);
}

export function fileDetails(repo: string, path: string): Promise<FileDetails> {
  return getJson(`/api/repositories/${repo}/file/${path}`);
}

/** Deletes a file or folder prefix using the session credentials. Throws [ApiError] on failure. */
export async function deleteEntry(repo: string, path: string): Promise<void> {
  const res = await fetch(`/api/repositories/${repo}/${path}`, authed({ method: 'DELETE' }));
  if (!res.ok) throw new ApiError(res.status);
}

/** Downloads an artifact's bytes with the session credentials (so private repos work). */
export async function download(repo: string, path: string): Promise<Blob> {
  const res = await fetch(`/${repo}/${path}`, authed());
  if (!res.ok) throw new ApiError(res.status);
  return res.blob();
}

/** Uploads a file to a hosted repo path (Maven PUT). Returns the HTTP status for the caller to branch. */
export async function upload(repo: string, path: string, file: File): Promise<number> {
  const res = await fetch(`/${repo}/${path}`, authed({ method: 'PUT', body: file }));
  return res.status;
}

export function basicAuth(user: string, password: string): string {
  return 'Basic ' + btoa(`${user}:${password}`);
}
