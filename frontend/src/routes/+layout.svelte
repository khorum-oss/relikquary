<script lang="ts">
  import { currentUser, login, logout } from '$lib/auth.svelte';
  import LoginForm from '$lib/components/LoginForm.svelte';

  let { children } = $props();
  let user = $derived(currentUser());
  let showLogin = $state(false);

  function doLogin(username: string, password: string) {
    login(username, password);
    showLogin = false;
  }
</script>

<header>
  <a href="/" class="brand">Relikquary</a>
  <div class="session">
    {#if user}
      <span data-testid="current-user">{user}</span>
      <button data-testid="logout-button" onclick={logout}>Log out</button>
    {:else}
      <button data-testid="login-button" onclick={() => (showLogin = true)}>Log in</button>
    {/if}
  </div>
</header>

{#if showLogin}
  <div class="login-overlay">
    <LoginForm onSubmit={doLogin} onCancel={() => (showLogin = false)} />
  </div>
{/if}

<main>
  {@render children()}
</main>

<style>
  :global(body) {
    margin: 0;
    font-family: system-ui, sans-serif;
    color: #1a202c;
    background: #f7fafc;
  }
  header {
    background: #2d3748;
    padding: 0.75rem 1.25rem;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .brand {
    color: #fff;
    font-weight: 700;
    text-decoration: none;
    font-size: 1.1rem;
  }
  .session {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    color: #e2e8f0;
    font-size: 0.9rem;
  }
  .session button {
    background: #4a5568;
    color: #fff;
    border: none;
    border-radius: 4px;
    padding: 0.3rem 0.7rem;
    cursor: pointer;
    font: inherit;
  }
  .login-overlay {
    max-width: 900px;
    margin: 1rem auto 0;
    padding: 0 1.25rem;
  }
  main {
    max-width: 900px;
    margin: 1.5rem auto;
    padding: 0 1.25rem;
  }
  :global(a) {
    color: #3182ce;
  }
</style>
