<script lang="ts">
  import { onMount } from 'svelte';
  import { getCatalog, type CatalogEntry } from '$lib/api';
  import { searchQuery } from '$lib/search.svelte';
  import EmptyState from '../EmptyState.svelte';
  import ErrorBanner from '../ErrorBanner.svelte';

  // The cross-repo artifact catalog (feature 016, Phase 2): one row per group:artifact, filtered live by
  // the topbar search. A row opens the artifact's raw folder view (its versions). Bounded by the server's
  // page cap; `truncated` is disclosed rather than silently hidden.
  let all = $state<CatalogEntry[]>([]);
  let truncated = $state(false);
  let loaded = $state(false);
  let error = $state('');

  onMount(async () => {
    try {
      const res = await getCatalog();
      all = res.entries;
      truncated = res.truncated;
    } catch (e) {
      error = `Failed to load the catalog (${e})`;
    } finally {
      loaded = true;
    }
  });

  // A container entry's display name is its image; a Maven entry's is group:artifact (feature 023).
  function displayName(e: CatalogEntry): string {
    return e.type === 'container' ? e.artifact : `${e.group}:${e.artifact}`;
  }

  let q = $derived(searchQuery().trim().toLowerCase());
  let rows = $derived(q ? all.filter((e) => displayName(e).toLowerCase().includes(q)) : all);

  // A container row opens the image's tag view; a Maven row opens its raw folder view.
  function href(e: CatalogEntry): string {
    return e.type === 'container'
      ? `/c/${e.repository}/${e.artifact}`
      : `/r/${e.repository}/${e.group.replace(/\./g, '/')}/${e.artifact}`;
  }

  function fmtBytes(n: number): string {
    if (n < 1024) return `${n} B`;
    const u = ['KB', 'MB', 'GB', 'TB'];
    let v = n / 1024;
    let i = 0;
    while (v >= 1024 && i < u.length - 1) {
      v /= 1024;
      i++;
    }
    return `${v.toFixed(1)} ${u[i]}`;
  }
</script>

{#if error}
  <ErrorBanner message={error} />
{:else if loaded && all.length === 0}
  <EmptyState message="No artifacts published yet." />
{:else if loaded && rows.length === 0}
  <EmptyState message={`No artifacts match “${searchQuery()}”.`} />
{:else}
  <div class="rq-scroll-x" data-testid="catalog-scroll">
    <table class="rq-panel" data-testid="catalog">
      <thead>
        <tr>
          <th>Name</th>
          <th>Latest</th>
          <th class="right">Versions</th>
          <th class="right">Size</th>
        </tr>
      </thead>
      <tbody>
        {#each rows as e (`${e.repository}:${e.type}:${e.group}:${e.artifact}`)}
          <tr data-testid="catalog-row">
            <td class="coord">
              <span class="type-badge {e.type}" data-testid="catalog-type">{e.type}</span>
              <a href={href(e)} data-testid="catalog-link">{displayName(e)}</a>
            </td>
            <td>{#if e.latestVersion}<span class="version">{e.latestVersion}</span>{:else}<span class="dim">—</span>{/if}</td>
            <td class="right dim">{e.versionCount}</td>
            <td class="right dim">{fmtBytes(e.sizeBytes)}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
  {#if truncated}
    <p class="truncated">Showing the first {all.length} artifacts — refine your search to narrow the list.</p>
  {/if}
{/if}

<style>
  table {
    width: 100%;
    border-collapse: collapse;
    overflow: hidden;
  }
  /* Feature 025: keep the columns legible on a phone — the table scrolls inside .rq-scroll-x, not the page. */
  @media (max-width: 768px) {
    table {
      min-width: 460px;
    }
  }
  th {
    text-align: left;
    padding: 7px 18px;
    border-bottom: 1px solid var(--rq-border);
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--rq-dim);
  }
  td {
    padding: 12px 18px;
    border-bottom: 1px solid var(--rq-border-subtle);
    font-size: 13px;
  }
  tr:last-child td {
    border-bottom: none;
  }
  tbody tr:hover {
    background: var(--rq-row-hover);
  }
  .coord a {
    font-family: var(--rq-mono);
    color: var(--rq-gold);
  }
  .type-badge {
    display: inline-block;
    font-family: var(--rq-serif);
    font-size: 9px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    padding: 1px 6px;
    margin-right: 8px;
    border: 1px solid currentColor;
    border-radius: 999px;
    vertical-align: middle;
  }
  .type-badge.container {
    color: var(--rq-gold);
  }
  .type-badge.maven {
    color: var(--rq-muted);
  }
  .version {
    font-family: var(--rq-mono);
    font-size: 11px;
    color: var(--rq-gold);
    border: 1px solid var(--rq-border-strong);
    border-radius: var(--rq-radius);
    padding: 2px 8px;
  }
  .right {
    text-align: right;
  }
  .dim {
    color: var(--rq-dim);
  }
  .truncated {
    color: var(--rq-muted);
    font-size: 11px;
    margin: 10px 2px 0;
  }
</style>
