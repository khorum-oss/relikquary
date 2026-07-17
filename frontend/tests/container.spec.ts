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
  await expect(tagRow.getByTestId('tag-name')).toHaveText('1.0.0');
  await expect(tagRow.getByTestId('tag-digest')).toContainText('sha256:');
  await expect(page.getByTestId('docker-pull-body')).toContainText('docker pull');
  await expect(page.getByTestId('docker-pull-body')).toContainText('apps/team/service:1.0.0');
});
