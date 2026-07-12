<script lang="ts">
  import { PRESETS } from '$lib/theme/presets';
  import { currentTheme, setTheme } from '$lib/theme/theme.svelte';
  import { currentUser } from '$lib/auth.svelte';

  // Theme settings (feature 019): pick a preset palette and, optionally, a custom accent colour. Changes
  // apply live (the whole app re-skins via the --rq-* tokens) and persist — to this browser always, and to
  // the server when signed in, so the choice follows the user across devices.
  let theme = $derived(currentTheme());
  let user = $derived(currentUser());
  const presets = Object.entries(PRESETS);

  function choosePreset(name: string) {
    setTheme({ preset: name, accent: theme.accent });
  }
  function chooseAccent(hex: string) {
    setTheme({ preset: theme.preset, accent: hex });
  }
  function clearAccent() {
    setTheme({ preset: theme.preset, accent: null });
  }

  // The colour input needs a concrete hex: the custom accent when set, else the preset's own swatch.
  let accentValue = $derived(theme.accent ?? PRESETS[theme.preset]?.swatch ?? '#c9a227');
</script>

<div class="rq-panel panel" data-testid="theme-settings">
  <div class="head">
    <div class="rq-uppercase">Appearance</div>
    <div class="title">Theme</div>
    <p class="hint">
      {#if user}
        Saved to your account — it follows you across devices.
      {:else}
        Saved to this browser. <a href="/" onclick={(e) => e.preventDefault()}>Sign in</a> to sync it across
        devices.
      {/if}
    </p>
  </div>

  <div class="section">
    <div class="rq-uppercase">Palette</div>
    <div class="presets" data-testid="theme-presets">
      {#each presets as [name, preset] (name)}
        <button
          class="preset"
          class:active={theme.preset === name}
          data-testid={`preset-${name}`}
          aria-pressed={theme.preset === name}
          onclick={() => choosePreset(name)}
        >
          <span class="swatch" style={`background:${preset.swatch}`}></span>
          <span class="label">{preset.label}</span>
        </button>
      {/each}
    </div>
  </div>

  <div class="section">
    <div class="rq-uppercase">Accent colour</div>
    <div class="accent">
      <input
        type="color"
        value={accentValue}
        data-testid="accent-input"
        aria-label="Accent colour"
        oninput={(e) => chooseAccent(e.currentTarget.value)}
      />
      <span class="rq-mono value" data-testid="accent-value">{theme.accent ?? `${accentValue} (preset)`}</span>
      {#if theme.accent}
        <button class="rq-btn" data-testid="accent-reset" onclick={clearAccent}>Use preset default</button>
      {/if}
    </div>
    <p class="hint">Overrides the palette's accent and text tones. Clear it to fall back to the preset.</p>
  </div>
</div>

<style>
  .panel {
    max-width: 560px;
    padding: 0;
  }
  .head {
    padding: 20px 24px;
    border-bottom: 1px solid var(--rq-border);
  }
  .title {
    font-family: var(--rq-serif);
    font-size: 18px;
    color: var(--rq-gold);
    letter-spacing: 1px;
    margin-top: 4px;
  }
  .hint {
    color: var(--rq-dim);
    font-size: 12px;
    margin: 8px 0 0;
    line-height: 1.5;
  }
  .section {
    padding: 20px 24px;
    border-bottom: 1px solid var(--rq-border-subtle);
  }
  .section:last-child {
    border-bottom: none;
  }
  .presets {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 10px;
    margin-top: 12px;
  }
  .preset {
    display: flex;
    align-items: center;
    gap: 10px;
    background: var(--rq-inset);
    border: 1px solid var(--rq-border);
    border-radius: var(--rq-radius);
    padding: 10px 12px;
    cursor: pointer;
    color: var(--rq-muted);
    text-align: left;
  }
  .preset:hover {
    background: var(--rq-row-hover);
  }
  .preset.active {
    border-color: var(--rq-gold);
    color: var(--rq-text);
  }
  .swatch {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    border: 1px solid var(--rq-border-strong);
    flex-shrink: 0;
  }
  .label {
    font-family: var(--rq-serif);
    font-size: 12px;
    letter-spacing: 1px;
  }
  .accent {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 12px;
  }
  .accent input[type='color'] {
    width: 44px;
    height: 32px;
    padding: 0;
    border: 1px solid var(--rq-border);
    border-radius: var(--rq-radius);
    background: var(--rq-inset);
    cursor: pointer;
  }
  .value {
    font-size: 13px;
    color: var(--rq-text);
  }
</style>
