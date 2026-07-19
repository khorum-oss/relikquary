<script lang="ts">
  import { page } from '$app/stores';
  import { listContainerImages, ApiError, type ContainerImagesResponse } from '$lib/api';
  import { login } from '$lib/auth.svelte';
  import EmptyState from '$lib/components/EmptyState.svelte';
  import ErrorBanner from '$lib/components/ErrorBanner.svelte';
  import LoginForm from '$lib/components/LoginForm.svelte';

  // The image listing of a container repository (feature 018): every image name with its tag/manifest
  // counts and most-recent push, each linking to its tag view. A PROXY repo is a pull-through cache, so
  // it lists only what has been pulled-and-cached and resolves tags live upstream — noted inline.
  let repo = $derived($page.params.repo ?? '');

  let data = $state<ContainerImagesResponse | null>(null);
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

  async function load(r: string) {
    error = '';
    forbidden = '';
    try {
      data = await listContainerImages(r);
    } catch (e) {
      data = null;
      handle(e);
    } finally {
      loaded = true;
    }
  }

  $effect(() => {
    load(repo);
  });

  async function onLogin(username: string, password: string) {
    login(username, password);
    needLogin = false;
    loginError = '';
    try {
      await load(repo);
    } catch {
      needLogin = true;
      loginError = 'Invalid credentials.';
    }
  }

  function fmtDate(iso?: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleString();
  }
</script>

<nav class="crumbs" data-testid="breadcrumbs">
  <a href="/">repositories</a>
  <span>›</span>
  <span class="here">{repo}</span>
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
    <h1 data-testid="container-repo-title">{repo}</h1>
    <span class="kind" data-testid="container-repo-kind">{data.kind.toLowerCase()} container registry</span>
  </div>

  {#if data.kind === 'PROXY'}
    <p class="proxy-note" data-testid="proxy-note">
      This is a pull-through cache. It lists images that have already been pulled and cached; tags are
      resolved live from the upstream registry, so they are not shown here.
    </p>
  {/if}

  {#if data.images.length === 0}
    <EmptyState testid="no-images"
      message={data.kind === 'PROXY'
        ? 'Nothing cached yet. Pull an image through this repository and it will appear here.'
        : 'No images yet. Push one with `docker push` to see it here.'} />
  {:else}
    <ul class="images" data-testid="image-list">
      {#each data.images as img (img.name)}
        <li class="row" data-testid="image-row">
          <a href={`/c/${repo}/${img.name}`} data-testid="image-link">{img.name}</a>
          <span class="meta">
            <span class="count">{img.tagCount} {img.tagCount === 1 ? 'tag' : 'tags'}</span>
            <span class="dot">·</span>
            <span class="count">{img.manifestCount} {img.manifestCount === 1 ? 'manifest' : 'manifests'}</span>
            <span class="pushed" title="Last pushed">{fmtDate(img.lastPushed)}</span>
          </span>
        </li>
      {/each}
    </ul>
  {/if}
{:else if loaded && !needLogin && !forbidden && !error}
  <EmptyState message="No such repository." />
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
    display: flex;
    align-items: baseline;
    gap: 0.8rem;
    margin-bottom: 0.4rem;
  }
  .head h1 {
    font-family: var(--rq-mono);
    font-size: 18px;
    color: var(--rq-gold);
    margin: 0;
  }
  .kind {
    font-family: var(--rq-serif);
    font-size: 11px;
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--rq-muted);
  }
  .proxy-note {
    color: var(--rq-muted);
    font-size: 13px;
    line-height: 1.5;
    margin: 0.3rem 0 1rem;
  }
  .images {
    list-style: none;
    padding: 0;
    margin: 1rem 0 0;
  }
  .row {
    padding: 12px 18px;
    background: var(--rq-panel);
    border: 1px solid var(--rq-border);
    border-radius: var(--rq-radius);
    margin-bottom: 8px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
  }
  .row:hover {
    background: var(--rq-row-hover);
  }
  .row a {
    font-family: var(--rq-mono);
    font-size: 13px;
    color: var(--rq-gold);
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .meta {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    color: var(--rq-dim);
    font-size: 0.8rem;
  }
  /* Feature 025: on a phone let the metadata wrap under the image name instead of overflowing the row. */
  @media (max-width: 768px) {
    .row {
      flex-wrap: wrap;
      padding: 14px 16px;
    }
    .meta {
      flex-wrap: wrap;
    }
  }
  .dot {
    color: var(--rq-border-strong);
  }
  .pushed {
    margin-left: 0.4rem;
    font-family: var(--rq-mono);
    color: var(--rq-muted);
  }
  .login-inline {
    max-width: 24rem;
    margin-bottom: 1rem;
  }
</style>
