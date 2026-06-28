<script lang="ts">
  // An informational placeholder: an empty listing, or a group repo's aggregate note (feature 008).
  let { message, testid = 'empty-state' }: { message: string; testid?: string } = $props();
</script>

<p class="empty-state" data-testid={testid}>{message}</p>

<style>
  .empty-state {
    color: #718096;
    background: #fff;
    border: 1px dashed #cbd5e0;
    border-radius: 6px;
    padding: 1rem;
    text-align: center;
  }
</style>
