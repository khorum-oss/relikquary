<script lang="ts">
  import { version } from '$lib/version';
  import { searchQuery, setSearchQuery } from '$lib/search.svelte';

  // The section header bar (feature 016): the active section title as the page's single h1, an optional
  // artifact search (shown on the catalog), and a version chip.
  //
  // Responsive (feature 025): at narrow widths a leading ☰ control reveals the navigation drawer; it is
  // hidden at wide widths where the rail is always visible. The search shrinks to the available width.
  let {
    title,
    showSearch = false,
    onMenu = () => {},
  }: { title: string; showSearch?: boolean; onMenu?: () => void } = $props();
</script>

<header class="topbar">
  <button class="menu" data-testid="nav-toggle" aria-label="Open navigation" onclick={onMenu}>☰</button>
  <h1>{title}</h1>
  <div class="spacer"></div>
  {#if showSearch}
    <input
      class="rq-input search"
      type="search"
      placeholder="Search artifacts…"
      data-testid="topbar-search"
      value={searchQuery()}
      oninput={(e) => setSearchQuery((e.currentTarget as HTMLInputElement).value)}
    />
  {/if}
  <span class="version">v{version}</span>
</header>

<style>
  .topbar {
    height: 48px;
    flex-shrink: 0;
    background: #130e06;
    border-bottom: 1px solid var(--rq-border);
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 0 22px;
  }
  h1 {
    margin: 0;
    font-family: var(--rq-serif);
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 1px;
    color: var(--rq-gold);
    white-space: nowrap;
  }
  .spacer {
    flex: 1;
  }
  .search {
    width: 240px;
    padding: 6px 11px;
    font-size: 12px;
  }
  .version {
    font-family: var(--rq-serif);
    font-size: 10px;
    color: var(--rq-dim);
    padding: 3px 8px;
    border: 1px solid var(--rq-border-subtle);
    border-radius: var(--rq-radius);
    white-space: nowrap;
  }

  /* The ☰ control is a narrow-only affordance; at wide widths the rail is permanent so it is hidden. */
  .menu {
    display: none;
    background: none;
    border: none;
    color: var(--rq-gold);
    font-size: 20px;
    line-height: 1;
    padding: 6px;
    margin-left: -6px;
    cursor: pointer;
  }

  @media (max-width: 768px) {
    .topbar {
      gap: 10px;
      padding: 0 14px;
    }
    .menu {
      display: block;
      min-width: 44px;
      min-height: 44px;
    }
    /* Let the search take the remaining width rather than a fixed 240px that could overflow the header. */
    .search {
      width: auto;
      min-width: 0;
      flex: 1;
    }
  }
</style>
