<script lang="ts">
  import { afterNavigate } from '$app/navigation';
  import Sidebar from './Sidebar.svelte';
  import Topbar from './Topbar.svelte';

  // The application frame (feature 016): navigation rail + section topbar + scrolling content region.
  // The page content is provided as the default snippet.
  //
  // Responsive (feature 025): at narrow (≤768px) widths the rail is presented as an off-canvas overlay
  // drawer opened by the topbar ☰ control; at wide widths it is the permanent rail, unchanged. The open
  // state lives here (the one place that composes rail + topbar + content) and resets on navigate, on
  // Escape, on a backdrop tap, and when the viewport grows past the breakpoint.
  let {
    pathname,
    title,
    user,
    onSignIn,
    onSignOut,
    children,
  }: {
    pathname: string;
    title: string;
    user: string | null;
    onSignIn: () => void;
    onSignOut: () => void;
    children: import('svelte').Snippet;
  } = $props();

  let drawerOpen = $state(false);

  function closeDrawer() {
    drawerOpen = false;
  }

  // Close the drawer after any client navigation (covers link taps and browser back/forward).
  afterNavigate(closeDrawer);

  // Global Escape closes the drawer; a breakpoint-cross to wide clears any lingering open state so the
  // overlay never persists once the rail becomes permanent.
  $effect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') closeDrawer();
    }
    const wide = window.matchMedia('(min-width: 769px)');
    function onChange(e: MediaQueryListEvent) {
      if (e.matches) closeDrawer();
    }
    window.addEventListener('keydown', onKey);
    wide.addEventListener('change', onChange);
    return () => {
      window.removeEventListener('keydown', onKey);
      wide.removeEventListener('change', onChange);
    };
  });
</script>

<div class="shell">
  <Sidebar {pathname} {user} {onSignIn} {onSignOut} open={drawerOpen} onClose={closeDrawer} />
  {#if drawerOpen}
    <button class="backdrop" data-testid="nav-backdrop" aria-label="Close navigation" onclick={closeDrawer}
    ></button>
  {/if}
  <div class="main">
    <Topbar {title} showSearch={pathname === '/'} onMenu={() => (drawerOpen = true)} />
    <div class="content" data-testid="app-content">
      {@render children()}
    </div>
  </div>
</div>

<style>
  .shell {
    height: 100vh;
    display: flex;
    overflow: hidden;
    background: var(--rq-shell);
  }
  .main {
    flex: 1;
    min-width: 0;
    max-width: 100%;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .content {
    flex: 1;
    min-width: 0;
    /* The content column scrolls vertically; it never scrolls sideways as a whole — wide regions carry
       their own bounded horizontal scroll (feature 025). */
    overflow-y: auto;
    overflow-x: hidden;
    padding: 22px;
  }

  /* The dimmed dismiss surface behind the open drawer (narrow only; the drawer itself is not rendered as an
     overlay at wide widths). */
  .backdrop {
    position: fixed;
    inset: 0;
    z-index: 40;
    border: none;
    padding: 0;
    background: rgba(8, 5, 3, 0.66);
    cursor: pointer;
  }
  @media (min-width: 769px) {
    /* Defensive: at wide widths there is no overlay even if state lingered mid-transition. */
    .backdrop {
      display: none;
    }
  }

  @media (max-width: 768px) {
    .content {
      padding: 16px 14px;
    }
  }
</style>
