<script lang="ts">
  // Login form (feature 008). Captures username/password and calls onSubmit; the parent validates
  // lazily (a repeated 401 ⇒ invalid) and passes `error` back to display.
  let {
    onSubmit,
    onCancel,
    error = '',
    title = 'Log in',
  }: {
    onSubmit: (username: string, password: string) => void;
    onCancel?: () => void;
    error?: string;
    title?: string;
  } = $props();

  let username = $state('');
  let password = $state('');

  function submit(event: SubmitEvent) {
    event.preventDefault();
    onSubmit(username, password);
  }
</script>

<form class="login" onsubmit={submit} data-testid="login-form">
  <h3>{title}</h3>
  {#if error}
    <p class="error" data-testid="login-error">{error}</p>
  {/if}
  <label>
    Username
    <input data-testid="login-username" bind:value={username} autocomplete="username" />
  </label>
  <label>
    Password
    <input data-testid="login-password" type="password" bind:value={password} autocomplete="current-password" />
  </label>
  <div class="actions">
    <button type="submit" data-testid="login-submit">Log in</button>
    {#if onCancel}
      <button type="button" class="secondary" onclick={onCancel}>Cancel</button>
    {/if}
  </div>
</form>

<style>
  .login {
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 1rem;
    max-width: 22rem;
    display: grid;
    gap: 0.6rem;
  }
  label {
    display: grid;
    gap: 0.2rem;
    font-size: 0.85rem;
    color: #4a5568;
  }
  input {
    padding: 0.4rem 0.5rem;
    border: 1px solid #cbd5e0;
    border-radius: 4px;
    font: inherit;
  }
  .actions {
    display: flex;
    gap: 0.5rem;
  }
  button {
    background: #3182ce;
    color: #fff;
    border: none;
    border-radius: 4px;
    padding: 0.4rem 0.9rem;
    cursor: pointer;
    font: inherit;
  }
  button.secondary {
    background: #edf2f7;
    color: #2d3748;
  }
  .error {
    color: #c53030;
    margin: 0;
  }
</style>
