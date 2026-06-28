<script module lang="ts">
  import { defineMeta } from '@storybook/addon-svelte-csf';
  import LoginForm from './LoginForm.svelte';

  const { Story } = defineMeta({
    title: 'Components/LoginForm',
    component: LoginForm,
    args: { onSubmit: () => {} },
  });
</script>

<Story name="Idle" />
<Story name="Invalid credentials" args={{ error: 'Invalid credentials.' }} />
<Story name="With cancel" args={{ onCancel: () => {}, title: 'Log in to continue' }} />
