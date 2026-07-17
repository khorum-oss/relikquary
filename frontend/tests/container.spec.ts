import { test, expect } from '@playwright/test';

// Container registry browse UI (feature 018) against the e2e backend (scripts/e2e.sh seeds the hosted
// 'apps' container repo with image team/service:1.0.0). A container repo is badged and links to the OCI
// image browser rather than the Maven folder tree; drilling in shows the image's tags with digest + size
// and a copy-paste `docker pull` command.
test('browse a container repository, its images and tags', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('repos-tab').click();

  // The container repo is badged 'container' (not a Maven type) and resolves as hosted.
  const appsRow = page.getByTestId('repo-row').filter({ hasText: 'apps' });
  await expect(appsRow.getByTestId('repo-format')).toHaveText('container');
  await expect(appsRow.getByTestId('repo-kind')).toHaveText('hosted');

  // It links to the container image browser (/c/...), not the Maven tree (/r/...).
  await appsRow.getByTestId('repo-link').click();
  await expect(page).toHaveURL(/\/c\/apps$/);
  await expect(page.getByTestId('container-repo-title')).toHaveText('apps');
  await expect(page.getByTestId('container-repo-kind')).toContainText('hosted');

  // The seeded image is listed with its tag count and links to its tags.
  const imageRow = page.getByTestId('image-row').filter({ hasText: 'team/service' });
  await expect(imageRow).toContainText('1 tag');
  await imageRow.getByTestId('image-link').click();

  // The tags view shows the pushed tag, its (short) digest, and a docker pull snippet.
  await expect(page.getByTestId('image-title')).toHaveText('team/service');
  const tagRow = page.getByTestId('tag-row').filter({ hasText: '1.0.0' });
  await expect(tagRow.getByTestId('tag-open')).toHaveText('1.0.0');
  await expect(tagRow.getByTestId('tag-digest')).toContainText('sha256:');
  await expect(page.getByTestId('docker-pull-body')).toContainText('docker pull');
  await expect(page.getByTestId('docker-pull-body')).toContainText('apps/team/service:1.0.0');

  // Drill the tag into its manifest detail (feature 020): config digest, total size, and layer rows.
  await tagRow.getByTestId('tag-open').click();
  const detail = page.getByTestId('manifest-detail');
  await expect(detail.getByTestId('manifest-config')).toContainText('sha256:');
  await expect(detail.getByTestId('manifest-total')).toContainText('B');
  await expect(detail.getByTestId('layer-row')).toHaveCount(1);
});

// A multi-arch tag (seeded by scripts/e2e.sh as team/multi:1.0.0) resolves to an image index: the manifest
// detail lists its platforms, and drilling into one shows that platform's own layers (feature 020).
// Navigate via the UI (client-side routing) rather than a cold page.goto — the dev server proxies a
// full-page load of /c/... to the backend, which is a harness artifact, not how the SPA is served.
test('drill a multi-arch image into a platform manifest', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('repos-tab').click();
  await page.getByTestId('repo-row').filter({ hasText: 'apps' }).getByTestId('repo-link').click();
  await page.getByTestId('image-row').filter({ hasText: 'team/multi' }).getByTestId('image-link').click();
  await expect(page.getByTestId('image-title')).toHaveText('team/multi');

  await page.getByTestId('tag-row').filter({ hasText: '1.0.0' }).getByTestId('tag-open').click();

  // The index detail lists two platforms.
  const detail = page.getByTestId('manifest-detail');
  await expect(detail.getByTestId('platform-row')).toHaveCount(2);
  const arm = detail.getByTestId('platform-row').filter({ hasText: 'arm64' });
  await expect(arm).toContainText('linux/arm64/v8');

  // Drill into a platform → its own image manifest (layers), then back to the platform list.
  await detail.getByTestId('platform-row').filter({ hasText: 'amd64' }).click();
  await expect(detail.getByTestId('layer-row')).toHaveCount(1);
  await detail.getByTestId('manifest-back').click();
  await expect(detail.getByTestId('platform-row')).toHaveCount(2);
});
