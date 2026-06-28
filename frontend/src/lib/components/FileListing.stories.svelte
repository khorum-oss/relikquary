<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import FileListing from './FileListing.svelte';
  import type { ListingEntry } from '$lib/api';

  const { Story } = defineMeta({
    title: 'Components/FileListing',
    component: FileListing,
    args: { repo: 'releases', path: 'com/example/widget', onOpen: () => {}, onDelete: () => {} },
  });

  const entries: ListingEntry[] = [
    { name: '1.0.0', kind: 'folder' },
    { name: 'widget-1.0.0.jar', kind: 'file', size: 12345, lastModified: '2026-06-27T10:00:00Z' },
    { name: 'widget-1.0.0.pom', kind: 'file', size: 678, lastModified: '2026-06-27T10:00:00Z' },
  ];
</script>

<Story name="With entries" args={{ entries }} />
<Story name="Empty" args={{ entries: [] }} />
