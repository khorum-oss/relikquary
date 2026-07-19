<script lang="ts">
  import KindBadge from './KindBadge.svelte';
  import type { RepositorySummary } from '$lib/api';
  // One repository in the list: a link to browse it plus its kind badge (feature 008; restyled 016).
  // A container repository (feature 018) links to the OCI image browser and shows a `container` badge;
  // its Maven `type` is meaningless, so it is hidden.
  let { repo }: { repo: RepositorySummary } = $props();
  let isContainer = $derived(repo.format === 'CONTAINER');
  let href = $derived(isContainer ? `/c/${repo.name}` : `/r/${repo.name}/`);
</script>

<li class="row" data-testid="repo-row">
  <a {href} data-testid="repo-link">{repo.name}</a>
  <span class="meta">
    {#if isContainer}
      <span class="format" data-testid="repo-format">container</span>
    {:else}
      <span class="type">{repo.type.toLowerCase()}</span>
    {/if}
    <KindBadge kind={repo.kind} />
  </span>
</li>

<style>
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
    gap: 0.7rem;
    flex-shrink: 0;
  }
  /* Feature 025: a comfortable tap height for the row link on touch screens. */
  @media (max-width: 768px) {
    .row {
      padding: 14px 16px;
    }
  }
  .type {
    color: var(--rq-dim);
    font-size: 0.8rem;
  }
  .format {
    color: var(--rq-dim);
    font-size: 0.8rem;
    font-family: var(--rq-mono);
  }
</style>
