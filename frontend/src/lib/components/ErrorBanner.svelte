<script lang="ts">
  // A clear inline error message (feature 008). Used for 403 (forbidden), 404 (not found), 409
  // (conflict) and generic failures — 401 is handled by the login prompt, not this banner.
  let { message }: { message: string } = $props();
</script>

<p class="error" data-testid="error">{message}</p>

<style>
  .error {
    color: #c53030;
    background: #fff5f5;
    border: 1px solid #feb2b2;
    border-radius: 6px;
    padding: 0.6rem 0.8rem;
  }
</style>
