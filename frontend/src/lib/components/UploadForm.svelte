<script lang="ts">
  // Upload a single file to a hosted repo path (feature 008). The target path is prefilled from the
  // current folder and confirmable. Submitting delegates to onUpload, which performs the PUT and
  // reports an outcome message; an "uploading" state shows progress so it never looks hung.
  let {
    repo,
    basePath,
    onUpload,
  }: {
    repo: string;
    basePath: string;
    onUpload: (path: string, file: File) => Promise<string | null>;
  } = $props();

  let file = $state<File | null>(null);
  let targetPath = $state('');
  let uploading = $state(false);
  let message = $state('');

  function onPick(event: Event) {
    const picked = (event.target as HTMLInputElement).files?.[0] ?? null;
    file = picked;
    if (picked) targetPath = basePath ? `${basePath}/${picked.name}` : picked.name;
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (!file || !targetPath) return;
    uploading = true;
    message = '';
    const error = await onUpload(targetPath, file);
    uploading = false;
    message = error ?? '';
    if (!error) {
      file = null;
      targetPath = '';
    }
  }
</script>

<form class="upload" onsubmit={submit} data-testid="upload-form">
  <h4>Upload to {repo}</h4>
  <input type="file" onchange={onPick} data-testid="upload-file" />
  <label>
    Target path
    <input bind:value={targetPath} data-testid="upload-path" placeholder="group/artifact/version/file" />
  </label>
  <button type="submit" disabled={!file || !targetPath || uploading} data-testid="upload-submit">
    {uploading ? 'Uploading…' : 'Upload'}
  </button>
  {#if message}
    <p class="error" data-testid="upload-error">{message}</p>
  {/if}
</form>

<style>
  .upload {
    background: #fff;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 1rem;
    margin-top: 1.25rem;
    display: grid;
    gap: 0.6rem;
    max-width: 28rem;
  }
  h4 {
    margin: 0;
  }
  label {
    display: grid;
    gap: 0.2rem;
    font-size: 0.85rem;
    color: #4a5568;
  }
  label input {
    padding: 0.4rem 0.5rem;
    border: 1px solid #cbd5e0;
    border-radius: 4px;
    font: inherit;
  }
  button {
    background: #3182ce;
    color: #fff;
    border: none;
    border-radius: 4px;
    padding: 0.4rem 0.9rem;
    cursor: pointer;
    font: inherit;
    justify-self: start;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .error {
    color: #c53030;
    margin: 0;
  }
</style>
