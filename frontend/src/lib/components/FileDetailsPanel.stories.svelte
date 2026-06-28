<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import FileDetailsPanel from './FileDetailsPanel.svelte';

  const { Story } = defineMeta({
    title: 'Components/FileDetailsPanel',
    component: FileDetailsPanel,
    args: { onDownload: () => {} },
  });

  const base = {
    repository: 'releases',
    path: 'com/example/widget/1.0.0/widget-1.0.0.jar',
    size: 12345,
    lastModified: '2026-06-27T10:00:00Z',
    downloadUrl: '/releases/com/example/widget/1.0.0/widget-1.0.0.jar',
  };
</script>

<Story name="With checksums" args={{ details: { ...base, checksums: { sha1: 'abc123', md5: 'def456' } } }} />
<Story name="No checksums" args={{ details: { ...base, checksums: {} } }} />
