<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import UploadForm from './UploadForm.svelte';

  const { Story } = defineMeta({
    title: 'Components/UploadForm',
    component: UploadForm,
    args: {
      repo: 'releases',
      basePath: 'com/example/widget/1.0.0',
      onUpload: async () => null,
    },
  });
</script>

<Story name="Idle" />
<Story name="With error" args={{ onUpload: async () => 'You are not permitted to publish here.' }} />
