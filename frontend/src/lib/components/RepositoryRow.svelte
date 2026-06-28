<script lang="ts">
  import KindBadge from './KindBadge.svelte';
  import type { RepositorySummary } from '$lib/api';
  // One repository in the list: a link to browse it plus its kind badge (feature 008).
  let { repo }: { repo: RepositorySummary } = $props();
</script>

<li class="row" data-testid="repo-row">
  <a href={`/r/${repo.name}/`} data-testid="repo-link">{repo.name}</a>
  <span class="meta">
    <span class="type">{repo.type.toLowerCase()}</span>
    <KindBadge kind={repo.kind} />
  </span>
</li>

<style>
  .row {
    padding: 0.6rem 0.8rem;
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    margin-bottom: 0.5rem;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .meta {
    display: flex;
    align-items: center;
    gap: 0.6rem;
  }
  .type {
    color: #718096;
    font-size: 0.8rem;
  }
</style>
