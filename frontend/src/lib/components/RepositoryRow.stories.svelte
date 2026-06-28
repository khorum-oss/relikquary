<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import RepositoryRow from './RepositoryRow.svelte';

  const { Story } = defineMeta({
    title: 'Components/RepositoryRow',
    component: RepositoryRow,
  });
</script>

<Story name="Hosted" args={{ repo: { name: 'releases', type: 'release', kind: 'HOSTED' } }} />
<Story name="Proxy" args={{ repo: { name: 'maven-central', type: 'mixed', kind: 'PROXY' } }} />
<Story name="Group" args={{ repo: { name: 'public', type: 'mixed', kind: 'GROUP' } }} />
