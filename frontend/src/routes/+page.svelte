<script lang="ts">
  import { onMount } from 'svelte';
  import { listRepositories, type RepositorySummary } from '$lib/api';
  import RepositoryRow from '$lib/components/RepositoryRow.svelte';

  let repos = $state<RepositorySummary[]>([]);
  let error = $state('');

  onMount(async () => {
    try {
      repos = await listRepositories();
    } catch (e) {
      error = `Failed to load repositories (${e})`;
    }
  });
</script>

<h1>Repositories</h1>

{#if error}
  <p class="error" data-testid="error">{error}</p>
{/if}

<ul class="repos">
  {#each repos as repo (repo.name)}
    <RepositoryRow {repo} />
  {/each}
</ul>

{#if repos.length === 0 && !error}
  <p>No repositories configured.</p>
{/if}

<style>
  .repos {
    list-style: none;
    padding: 0;
  }
  .error {
    color: #c53030;
  }
</style>
