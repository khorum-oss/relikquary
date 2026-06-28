<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import Breadcrumbs from './Breadcrumbs.svelte';

  const { Story } = defineMeta({
    title: 'Components/Breadcrumbs',
    component: Breadcrumbs,
  });
</script>

<Story name="Root" args={{ repo: 'releases', path: '' }} />
<Story name="Nested" args={{ repo: 'releases', path: 'com/example/widget/1.0.0' }} />
