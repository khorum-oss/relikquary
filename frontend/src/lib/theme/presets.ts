// Theme presets and accent derivation (feature 019). Each preset is a full map of the `--rq-*` design
// tokens (see tokens.css) so switching presets fully re-skins the app; an optional custom accent then
// overrides just the accent/text ramp on top. Preset names are kept in sync with the backend's
// ThemePreference.PRESETS allow-list.

/** A user's theme choice: a named preset plus an optional `#rrggbb` accent override. */
export interface ThemeChoice {
  preset: string;
  accent: string | null;
}

export const DEFAULT_PRESET = 'vault-gold';

/** The design-token custom properties a preset defines (the leading `--rq-` is added on apply). */
type Tokens = Record<string, string>;

interface Preset {
  label: string;
  /** A representative colour for the picker swatch. */
  swatch: string;
  tokens: Tokens;
}

/** Every preset defines the same key set so switching one for another leaves no stale token. */
export const PRESETS: Record<string, Preset> = {
  'vault-gold': {
    label: 'Vault Gold',
    swatch: '#c9a227',
    tokens: {
      bg: '#080503', shell: '#160e06', panel: '#241a0a', inset: '#0e0b04', rail: '#0e0a04',
      gold: '#c9a227', 'gold-soft': '#9a7820', text: '#c8a840', muted: '#8b6914', dim: '#7a5c14',
      border: '#3a2810', 'border-strong': '#5a4210', 'border-subtle': '#2a1a0a',
      'row-hover': '#1e1308', 'nav-hover': '#160e04', danger: '#c4663a', 'danger-bg': '#1c0f06',
    },
  },
  emerald: {
    label: 'Emerald',
    swatch: '#3fb27f',
    tokens: {
      bg: '#060806', shell: '#0c140e', panel: '#101d14', inset: '#08100a', rail: '#080f0a',
      gold: '#3fb27f', 'gold-soft': '#2f8560', text: '#6fce9f', muted: '#3f8f68', dim: '#357a58',
      border: '#163a28', 'border-strong': '#1f5a3c', 'border-subtle': '#12281c',
      'row-hover': '#0e1f16', 'nav-hover': '#0c1a12', danger: '#c4663a', 'danger-bg': '#1c0f06',
    },
  },
  crimson: {
    label: 'Crimson',
    swatch: '#d24b4b',
    tokens: {
      bg: '#080505', shell: '#160b0b', panel: '#241010', inset: '#0e0808', rail: '#0e0808',
      gold: '#d24b4b', 'gold-soft': '#9a3333', text: '#e08a8a', muted: '#a15656', dim: '#8a4a4a',
      border: '#3a1818', 'border-strong': '#5a2222', 'border-subtle': '#2a1010',
      'row-hover': '#1e0f0f', 'nav-hover': '#160a0a', danger: '#d24b4b', 'danger-bg': '#1c0808',
    },
  },
  slate: {
    label: 'Slate',
    swatch: '#6f9fd0',
    tokens: {
      bg: '#06080b', shell: '#0e131a', panel: '#151c26', inset: '#0a0e14', rail: '#0a0e14',
      gold: '#6f9fd0', 'gold-soft': '#4f7aa5', text: '#a9c3e0', muted: '#6f89a5', dim: '#5f7690',
      border: '#24303f', 'border-strong': '#364a63', 'border-subtle': '#1a2430',
      'row-hover': '#121a26', 'nav-hover': '#0e1620', danger: '#c4663a', 'danger-bg': '#14100c',
    },
  },
};

/** Whether `name` is a known preset (used to sanitize restored/stored values). */
export function isPreset(name: string | null | undefined): boolean {
  return typeof name === 'string' && Object.prototype.hasOwnProperty.call(PRESETS, name);
}

/** Whether `value` is a well-formed `#rrggbb` hex colour. */
export function isHexColor(value: string | null | undefined): value is string {
  return typeof value === 'string' && /^#[0-9a-fA-F]{6}$/.test(value);
}

/** Scales each RGB channel of a `#rrggbb` colour by `factor`, clamped to a valid byte. */
function scale(hex: string, factor: number): string {
  const n = parseInt(hex.slice(1), 16);
  const ch = [(n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff]
    .map((c) => Math.max(0, Math.min(255, Math.round(c * factor))));
  return '#' + ch.map((c) => c.toString(16).padStart(2, '0')).join('');
}

/** Derives an accent/text ramp from a single hex, overriding only the accent-related tokens. */
export function accentOverrides(hex: string): Tokens {
  return {
    gold: hex,
    'gold-soft': scale(hex, 0.78),
    text: scale(hex, 1.14),
    muted: scale(hex, 0.7),
    dim: scale(hex, 0.6),
    'border-strong': scale(hex, 0.5),
  };
}

/**
 * Resolves a choice to the full `--rq-*` custom-property map to apply to the document root: the preset's
 * tokens, with the custom accent ramp layered on top when one is set.
 */
export function resolveVars(choice: ThemeChoice): Record<string, string> {
  const preset = PRESETS[choice.preset] ?? PRESETS[DEFAULT_PRESET];
  const merged: Tokens = { ...preset.tokens };
  if (isHexColor(choice.accent)) Object.assign(merged, accentOverrides(choice.accent));
  const vars: Record<string, string> = {};
  for (const [key, value] of Object.entries(merged)) vars[`--rq-${key}`] = value;
  return vars;
}
