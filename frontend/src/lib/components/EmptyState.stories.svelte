<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import EmptyState from './EmptyState.svelte';

  const { Story } = defineMeta({
    title: 'Components/EmptyState',
    component: EmptyState,
  });
</script>

<Story name="Empty" args={{ message: 'Empty' }} />
<Story
  name="Group aggregate"
  args={{ message: "'public' is a group repository — an aggregate of its member repositories." }}
/>
