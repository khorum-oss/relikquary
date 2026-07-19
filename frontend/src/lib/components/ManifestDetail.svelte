<script lang="ts">
  import { getContainerManifest, ApiError, type ManifestDetail, type ManifestDescriptor } from '$lib/api';
  import TrustBadge from './TrustBadge.svelte';

  // Inline manifest detail panel (feature 020): given a repo and the digest a tag points at, show the
  // image's config + ordered layers, or — for a multi-arch image index — its platforms, each drillable
  // into that platform's own image manifest. Read-only; every digest shown is what a client would pull.
  let { repo, digest }: { repo: string; digest: string } = $props();

  // A navigation stack of digests: [tag digest, …drilled platform sub-manifests]. Reset when the tag changes.
  let stack = $state<string[]>([]);
  let detail = $state<ManifestDetail | null>(null);
  let error = $state('');

  $effect(() => {
    // Re-root whenever the selected tag digest changes.
    stack = [digest];
  });

  let current = $derived(stack[stack.length - 1] ?? digest);

  $effect(() => {
    void load(current);
  });

  async function load(d: string) {
    detail = null;
    error = '';
    try {
      detail = await getContainerManifest(repo, d);
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) error = 'This manifest is no longer stored.';
      else if (e instanceof ApiError) error = `Failed to load manifest (${e.status}).`;
      else error = `Failed to load manifest (${e}).`;
    }
  }

  function openPlatform(d: string) {
    stack = [...stack, d];
  }

  function back() {
    if (stack.length > 1) stack = stack.slice(0, -1);
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

  function shortDigest(d: string): string {
    const hex = d.startsWith('sha256:') ? d.slice('sha256:'.length) : d;
    return `sha256:${hex.slice(0, 12)}`;
  }

  function platformLabel(d: ManifestDescriptor): string {
    const p = d.platform;
    if (!p) return 'unknown platform';
    return p.variant ? `${p.os}/${p.architecture}/${p.variant}` : `${p.os}/${p.architecture}`;
  }
</script>

<section class="detail" data-testid="manifest-detail">
  {#if stack.length > 1}
    <button class="back" onclick={back} data-testid="manifest-back">‹ platforms</button>
  {/if}

  {#if detail}
    <div class="trust-row"><span class="k">trust</span><TrustBadge trust={detail.trust} /></div>
  {/if}

  {#if error}
    <p class="error" data-testid="manifest-error">{error}</p>
  {:else if !detail}
    <p class="loading">Loading manifest…</p>
  {:else if detail.kind === 'image'}
    <div class="summary">
      <span class="k">config</span>
      <span class="mono" data-testid="manifest-config">{shortDigest(detail.config?.digest ?? '')}</span>
      <span class="k">total</span>
      <span data-testid="manifest-total">{fmtSize(detail.totalSize ?? 0)}</span>
    </div>
    {#if detail.layers && detail.layers.length > 0}
      <div class="rq-scroll-x" data-testid="layer-table-scroll">
        <table class="layers" data-testid="layer-table">
          <thead>
            <tr><th>Layer</th><th>Media type</th><th>Size</th></tr>
          </thead>
          <tbody>
            {#each detail.layers as layer, i (layer.digest + i)}
              <tr data-testid="layer-row">
                <td class="mono" title={layer.digest}>{shortDigest(layer.digest)}{#if !layer.present}<span class="absent" title="not stored locally"> ·not stored</span>{/if}</td>
                <td class="mt">{layer.mediaType}</td>
                <td class="size">{fmtSize(layer.size)}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {:else}
      <p class="loading">This manifest has a config but no layers.</p>
    {/if}
  {:else if detail.kind === 'index'}
    <p class="index-note">Multi-architecture image — {detail.manifests?.length ?? 0} platform(s). Select one to see its layers.</p>
    <ul class="platforms" data-testid="platform-list">
      {#each detail.manifests ?? [] as m (m.digest)}
        <li>
          <button class="platform" data-testid="platform-row" onclick={() => openPlatform(m.digest)} disabled={!m.present}>
            <span class="plat">{platformLabel(m)}</span>
            <span class="mono">{shortDigest(m.digest)}</span>
            <span class="size">{fmtSize(m.size)}</span>
            {#if !m.present}<span class="absent">not stored</span>{/if}
          </button>
        </li>
      {/each}
    </ul>
  {:else}
    <p class="loading" data-testid="manifest-unknown">
      No detailed breakdown available for this manifest ({detail.mediaType || 'unknown media type'}).
    </p>
  {/if}
</section>

<style>
  .detail {
    border: 1px solid var(--rq-border);
    border-radius: var(--rq-radius);
    background: var(--rq-panel);
    padding: 0.8rem;
    margin-top: 0.8rem;
  }
  .back {
    background: none;
    border: none;
    color: var(--rq-muted);
    font-family: var(--rq-serif);
    font-size: 11px;
    letter-spacing: 1px;
    cursor: pointer;
    padding: 0 0 0.4rem;
  }
  .back:hover {
    color: var(--rq-gold);
  }
  .summary {
    display: flex;
    align-items: baseline;
    gap: 0.5rem;
    flex-wrap: wrap;
    margin-bottom: 0.6rem;
  }
  .summary .k,
  .trust-row .k {
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--rq-muted);
  }
  .trust-row {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-bottom: 0.6rem;
  }
  .mono {
    font-family: var(--rq-mono);
    color: var(--rq-muted);
    font-size: 12px;
  }
  .layers,
  .platforms {
    width: 100%;
  }
  .layers {
    border-collapse: collapse;
    font-size: 12px;
  }
  /* Feature 025: the layer table scrolls inside .rq-scroll-x on a phone, not the page. */
  @media (max-width: 768px) {
    .layers {
      min-width: 420px;
    }
  }
  .layers th {
    text-align: left;
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--rq-muted);
    border-bottom: 1px solid var(--rq-border);
    padding: 0.4rem 0.5rem;
  }
  .layers td {
    padding: 0.45rem 0.5rem;
    border-bottom: 1px solid var(--rq-border-subtle);
  }
  .mt {
    color: var(--rq-dim);
    font-family: var(--rq-mono);
    font-size: 11px;
  }
  .size {
    color: var(--rq-dim);
    white-space: nowrap;
  }
  .absent {
    color: var(--rq-danger);
    font-size: 10px;
    margin-left: 0.4rem;
  }
  .index-note {
    color: var(--rq-muted);
    font-size: 12px;
    margin: 0 0 0.6rem;
  }
  .platforms {
    list-style: none;
    padding: 0;
    margin: 0;
  }
  .platform {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 0.7rem;
    background: var(--rq-inset);
    border: 1px solid var(--rq-border);
    border-radius: var(--rq-radius);
    padding: 0.5rem 0.7rem;
    margin-bottom: 0.4rem;
    cursor: pointer;
    text-align: left;
  }
  .platform:hover:not(:disabled) {
    border-color: var(--rq-gold);
  }
  .platform:disabled {
    cursor: default;
    opacity: 0.6;
  }
  .plat {
    font-family: var(--rq-mono);
    color: var(--rq-gold);
    font-size: 13px;
  }
  .loading,
  .error {
    color: var(--rq-muted);
    font-size: 12px;
    margin: 0.2rem 0;
  }
  .error {
    color: var(--rq-danger);
  }
</style>
