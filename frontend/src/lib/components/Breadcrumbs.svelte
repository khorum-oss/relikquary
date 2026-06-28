<script lang="ts">
  // Path navigation for a repository (feature 005/008).
  let { repo, path }: { repo: string; path: string } = $props();
  let crumbs = $derived(path ? path.split('/') : []);
</script>

<nav class="crumbs" data-testid="breadcrumbs">
  <a href="/">repositories</a>
  <span>/</span>
  <a href={`/r/${repo}/`}>{repo}</a>
  {#each crumbs as seg, i}
    <span>/</span>
    <a href={`/r/${repo}/${crumbs.slice(0, i + 1).join('/')}`}>{seg}</a>
  {/each}
</nav>

<style>
  .crumbs {
    margin-bottom: 1rem;
    font-size: 0.9rem;
  }
  .crumbs span {
    color: #a0aec0;
    margin: 0 0.25rem;
  }
</style>
