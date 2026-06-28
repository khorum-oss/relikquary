<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import ErrorBanner from './ErrorBanner.svelte';

  const { Story } = defineMeta({
    title: 'Components/ErrorBanner',
    component: ErrorBanner,
  });
</script>

<Story name="Forbidden" args={{ message: 'You are not permitted to access this repository.' }} />
<Story name="Not found" args={{ message: 'Not found' }} />
<Story name="Conflict" args={{ message: 'Conflict: this release already exists and is immutable.' }} />
