<script lang="ts">
  // Copy-paste `docker pull` snippet for a container image reference (feature 018). `reference` is the
  // full `{host}/{repo}/{image}:{tag}` (or `@sha256:…`) a client passes to docker/podman/nerdctl.
  let { reference }: { reference: string } = $props();

  let command = $derived(`docker pull ${reference}`);
  let copied = $state(false);

  async function copy() {
    await navigator.clipboard?.writeText(command);
    copied = true;
    setTimeout(() => (copied = false), 1200);
  }
</script>

<section class="snippet" data-testid="docker-pull">
  <pre data-testid="docker-pull-body">{command}</pre>
  <button class="copy" onclick={copy} data-testid="docker-pull-copy">{copied ? 'Copied' : 'Copy'}</button>
</section>

<style>
  .snippet {
    border: 1px solid var(--rq-border);
    border-radius: var(--rq-radius);
    background: var(--rq-panel);
    padding: 0.7rem;
    position: relative;
  }
  pre {
    background: var(--rq-inset);
    color: var(--rq-text);
    border: 1px solid var(--rq-border-subtle);
    padding: 0.8rem;
    border-radius: var(--rq-radius);
    overflow-x: auto;
    font-family: var(--rq-mono);
    font-size: 12px;
    line-height: 1.6;
    margin: 0;
  }
  .copy {
    position: absolute;
    top: 0.7rem;
    right: 0.7rem;
    font-family: var(--rq-serif);
    font-size: 10px;
    letter-spacing: 1px;
    padding: 0.25rem 0.7rem;
    border: 1px solid var(--rq-border-strong);
    border-radius: var(--rq-radius);
    background: var(--rq-panel);
    color: var(--rq-muted);
    cursor: pointer;
  }
  .copy:hover {
    color: var(--rq-gold);
  }
</style>
