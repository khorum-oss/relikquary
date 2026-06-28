import type { StorybookConfig } from '@storybook/sveltekit';

// Storybook for the Relikquary UI's reusable components (feature 008). Dev-only — not part of the
// bundled `/ui` build.
const config: StorybookConfig = {
  stories: ['../src/lib/components/**/*.stories.svelte'],
  addons: ['@storybook/addon-svelte-csf'],
  framework: {
    name: '@storybook/sveltekit',
    options: {},
  },
};

export default config;
