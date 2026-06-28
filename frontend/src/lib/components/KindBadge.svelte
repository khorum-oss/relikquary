<script lang="ts">
  // A small badge showing a repository's kind (hosted / proxy / group), feature 006/008.
  let { kind }: { kind: string } = $props();
  let label = $derived(kind.toLowerCase());
</script>

<span class="badge {label}" data-testid="repo-kind">{label}</span>

<style>
  .badge {
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    padding: 0.1rem 0.45rem;
    border-radius: 999px;
    border: 1px solid currentColor;
  }
  .hosted {
    color: #2f855a;
  }
  .proxy {
    color: #2b6cb0;
  }
  .group {
    color: #6b46c1;
  }
</style>
