<script lang="ts">
  import type { ListingEntry } from '$lib/api';
  // The contents table for a folder (feature 005/008): folders link deeper, files open details, each
  // row has a delete action. Authorization is enforced by the backend on the resulting requests.
  let {
    repo,
    path,
    entries,
    onOpen,
    onDelete,
  }: {
    repo: string;
    path: string;
    entries: ListingEntry[];
    onOpen: (name: string) => void;
    onDelete: (name: string) => void;
  } = $props();

  function join(base: string, name: string): string {
    return base ? `${base}/${name}` : name;
  }
</script>

<table data-testid="listing">
  <thead><tr><th>Name</th><th>Size</th><th>Modified</th><th></th></tr></thead>
  <tbody>
    {#each entries as entry (entry.name)}
      <tr>
        <td>
          {#if entry.kind === 'folder'}
            📁 <a href={`/r/${repo}/${join(path, entry.name)}`}>{entry.name}/</a>
          {:else}
            📄 <button class="link" onclick={() => onOpen(entry.name)} data-testid="file">{entry.name}</button>
          {/if}
        </td>
        <td>{entry.size ?? ''}</td>
        <td>{entry.lastModified ? new Date(entry.lastModified).toLocaleString() : ''}</td>
        <td><button class="danger" onclick={() => onDelete(entry.name)} data-testid="delete">Delete</button></td>
      </tr>
    {/each}
    {#if entries.length === 0}
      <tr><td colspan="4" class="empty">Empty</td></tr>
    {/if}
  </tbody>
</table>

<style>
  table {
    width: 100%;
    border-collapse: collapse;
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
  }
  th, td {
    text-align: left;
    padding: 0.5rem 0.75rem;
    border-bottom: 1px solid #edf2f7;
    font-size: 0.9rem;
  }
  .empty {
    color: #a0aec0;
    text-align: center;
  }
  button.link {
    background: none;
    border: none;
    color: #3182ce;
    cursor: pointer;
    padding: 0;
    font: inherit;
  }
  button.danger {
    background: #fff5f5;
    border: 1px solid #feb2b2;
    color: #c53030;
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.8rem;
  }
</style>
