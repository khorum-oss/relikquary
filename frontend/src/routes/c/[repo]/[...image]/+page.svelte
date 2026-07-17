<script lang="ts">
  import { page } from '$app/stores';
  import { listContainerTags, ApiError, type ContainerTagsResponse } from '$lib/api';
  import { login } from '$lib/auth.svelte';
  import DockerPullSnippet from '$lib/components/DockerPullSnippet.svelte';
  import ManifestDetail from '$lib/components/ManifestDetail.svelte';
  import EmptyState from '$lib/components/EmptyState.svelte';
  import ErrorBanner from '$lib/components/ErrorBanner.svelte';
  import LoginForm from '$lib/components/LoginForm.svelte';

  // The tags of one image in a container repository (feature 018): each tag with the digest it points at,
  // its manifest size, when it was pushed, and a copy-paste `docker pull` command.
  let repo = $derived($page.params.repo ?? '');
  let image = $derived($page.params.image ?? '');
  // The registry host in a docker reference is host[:port] with no scheme.
  let host = $derived(typeof window !== 'undefined' ? window.location.host : '');

  let data = $state<ContainerTagsResponse | null>(null);
  // The tag whose manifest detail is expanded below the table (feature 020), or null when collapsed.
  let openTag = $state<{ tag: string; digest: string } | null>(null);
  let error = $state('');
  let forbidden = $state('');
  let needLogin = $state(false);
  let loginError = $state('');
  let loaded = $state(false);

  function handle(e: unknown) {
    if (e instanceof ApiError) {
      if (e.status === 401) needLogin = true;
      else if (e.status === 403) forbidden = 'You are not permitted to access this repository.';
      else if (e.status === 404) error = 'No such repository.';
      else error = `Failed (${e.status}).`;
    } else {
      error = `Failed (${e}).`;
    }
  }

  function toggle(t: { tag: string; digest: string }) {
    openTag = openTag?.tag === t.tag ? null : { tag: t.tag, digest: t.digest };
  }

  async function load(r: string, img: string) {
    error = '';
    forbidden = '';
    openTag = null;
    try {
      data = await listContainerTags(r, img);
    } catch (e) {
      data = null;
      handle(e);
    } finally {
      loaded = true;
    }
  }

  $effect(() => {
    load(repo, image);
  });

  async function onLogin(username: string, password: string) {
    login(username, password);
    needLogin = false;
    loginError = '';
    try {
      await load(repo, image);
    } catch {
      needLogin = true;
      loginError = 'Invalid credentials.';
    }
  }

  function fmtDate(iso?: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleString();
  }

  function fmtSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB'];
    let value = bytes / 1024;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    return `${value.toFixed(1)} ${units[unit]}`;
  }

  function shortDigest(digest: string): string {
    const hex = digest.startsWith('sha256:') ? digest.slice('sha256:'.length) : digest;
    return `sha256:${hex.slice(0, 12)}`;
  }
</script>

<nav class="crumbs" data-testid="breadcrumbs">
  <a href="/">repositories</a>
  <span>›</span>
  <a href={`/c/${repo}`}>{repo}</a>
  <span>›</span>
  <span class="here">{image}</span>
</nav>

{#if needLogin}
  <div class="login-inline rq-panel">
    <LoginForm onSubmit={onLogin} onCancel={() => (needLogin = false)} error={loginError} title="Log in to continue" />
  </div>
{/if}

{#if forbidden}
  <ErrorBanner message={forbidden} />
{/if}
{#if error}
  <ErrorBanner message={error} />
{/if}

{#if data}
  <div class="head">
    <h1 data-testid="image-title">{image}</h1>
  </div>
  <DockerPullSnippet reference={`${host}/${repo}/${image}:${openTag?.tag ?? data.tags[0]?.tag ?? 'latest'}`} />

  {#if data.tags.length === 0}
    <EmptyState testid="no-tags"
      message="No tags recorded for this image. On a proxy repository, tags are resolved live from the upstream and are not listed here." />
  {:else}
    <table class="tags" data-testid="tag-table">
      <thead>
        <tr>
          <th>Tag</th>
          <th>Digest</th>
          <th>Size</th>
          <th>Pushed</th>
        </tr>
      </thead>
      <tbody>
        {#each data.tags as t (t.tag)}
          <tr data-testid="tag-row" class:open={openTag?.tag === t.tag}>
            <td class="tag">
              <button class="tag-btn" data-testid="tag-open" onclick={() => toggle(t)}>{t.tag}</button>
            </td>
            <td class="digest" title={t.digest} data-testid="tag-digest">{shortDigest(t.digest)}</td>
            <td class="size">{fmtSize(t.size)}</td>
            <td class="pushed">{fmtDate(t.pushedAt)}</td>
          </tr>
        {/each}
      </tbody>
    </table>
    {#if openTag}
      <ManifestDetail {repo} digest={openTag.digest} />
    {/if}
  {/if}
{:else if loaded && !needLogin && !forbidden && !error}
  <EmptyState message="No such image." />
{/if}

<style>
  .crumbs {
    margin-bottom: 16px;
    font-family: var(--rq-mono);
    font-size: 12px;
  }
  .crumbs a {
    color: var(--rq-muted);
  }
  .crumbs a:hover {
    color: var(--rq-gold);
  }
  .crumbs span {
    color: var(--rq-dim);
    margin: 0 0.3rem;
  }
  .crumbs .here {
    color: var(--rq-text);
  }
  .head {
    margin-bottom: 0.6rem;
  }
  .head h1 {
    font-family: var(--rq-mono);
    font-size: 18px;
    color: var(--rq-gold);
    margin: 0;
  }
  .tags {
    width: 100%;
    border-collapse: collapse;
    margin-top: 1rem;
    font-size: 13px;
  }
  .tags th {
    text-align: left;
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--rq-muted);
    border-bottom: 1px solid var(--rq-border);
    padding: 0.5rem 0.6rem;
  }
  .tags td {
    padding: 0.55rem 0.6rem;
    border-bottom: 1px solid var(--rq-border-subtle);
  }
  .tag-btn {
    font-family: var(--rq-mono);
    color: var(--rq-gold);
    background: none;
    border: none;
    padding: 0;
    font-size: 13px;
    cursor: pointer;
  }
  .tag-btn:hover {
    text-decoration: underline;
  }
  tr.open .tag-btn {
    font-weight: 600;
  }
  tr.open td {
    background: var(--rq-row-hover);
  }
  .digest {
    font-family: var(--rq-mono);
    color: var(--rq-muted);
  }
  .size,
  .pushed {
    color: var(--rq-dim);
    white-space: nowrap;
  }
  .login-inline {
    max-width: 24rem;
    margin-bottom: 1rem;
  }
</style>
