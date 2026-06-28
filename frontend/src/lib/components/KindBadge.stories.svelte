<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import KindBadge from './KindBadge.svelte';

  const { Story } = defineMeta({
    title: 'Components/KindBadge',
    component: KindBadge,
  });
</script>

<Story name="Hosted" args={{ kind: 'HOSTED' }} />
<Story name="Proxy" args={{ kind: 'PROXY' }} />
<Story name="Group" args={{ kind: 'GROUP' }} />
