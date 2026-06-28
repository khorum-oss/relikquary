<script lang="ts">
  import type { FileDetails } from '$lib/api';
  // File details + a Download action (feature 005/008). Download is delegated so the parent can fetch
  // with credentials and save a Blob (private repos).
  let { details, onDownload }: { details: FileDetails; onDownload: () => void } = $props();
</script>

<aside class="details" data-testid="details">
  <h3>{details.path.split('/').pop()}</h3>
  <dl>
    <dt>Path</dt>
    <dd>{details.repository}/{details.path}</dd>
    <dt>Size</dt>
    <dd>{details.size} bytes</dd>
    {#if details.lastModified}
      <dt>Modified</dt>
      <dd>{new Date(details.lastModified).toLocaleString()}</dd>
    {/if}
    {#each Object.entries(details.checksums) as [algo, value]}
      <dt>{algo}</dt>
      <dd class="mono">{value}</dd>
    {/each}
  </dl>
  <button class="button" onclick={onDownload} data-testid="download">Download</button>
</aside>

<style>
  .details {
    margin-top: 1.25rem;
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 1rem;
  }
  .details dl {
    display: grid;
    grid-template-columns: 7rem 1fr;
    gap: 0.25rem 0.75rem;
  }
  .details dt {
    color: #718096;
  }
  .mono {
    font-family: monospace;
    word-break: break-all;
  }
  .button {
    display: inline-block;
    margin-top: 0.75rem;
    background: #3182ce;
    color: #fff;
    border: none;
    padding: 0.4rem 0.9rem;
    border-radius: 4px;
    cursor: pointer;
    font: inherit;
  }
</style>
