import { test, expect } from '@playwright/test';

// Phase 2 read-only data (feature 016): the Dashboard shows live stats, and the Repositories landing
// defaults to a cross-repo artifact catalog filtered by the topbar search. Seeded by scripts/e2e.sh
// (releases/com/example/widget/1.0.0 and releases/com/example/gmodule/2.0.0).
test('dashboard stats and the searchable artifact catalog', async ({ page }) => {
  // Dashboard renders the live stat cards.
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  await expect(page.getByTestId('stat-cards')).toContainText('Total Artifacts');
  await expect(page.getByTestId('stat-cards')).toContainText('Repositories');

  // The catalog is the default Repositories view: one row per group:artifact.
  await page.goto('/');
  await expect(page.getByTestId('catalog')).toBeVisible();
  const widget = page.getByTestId('catalog-row').filter({ hasText: 'com.example:widget' });
  await expect(widget).toBeVisible();

  // Topbar search filters the catalog to matching coordinates.
  await page.getByTestId('topbar-search').fill('gmodule');
  await expect(page.getByTestId('catalog-row').filter({ hasText: 'com.example:gmodule' })).toBeVisible();
  await expect(page.getByTestId('catalog-row').filter({ hasText: 'com.example:widget' })).toHaveCount(0);

  // A catalog row opens the artifact's raw folder view (its version directories).
  await page.getByTestId('topbar-search').fill('');
  await widget.getByRole('link').click();
  await expect(page.getByRole('link', { name: '1.0.0/', exact: true })).toBeVisible();
});

// Container images are first-class in the catalog and counted on the dashboard (feature 023). Seeded by
// scripts/e2e.sh (apps/team/service etc.).
test('container images appear in the catalog and on the dashboard', async ({ page }) => {
  // The dashboard shows the container-images figure.
  await page.goto('/dashboard');
  await expect(page.getByTestId('stat-cards')).toContainText('Container Images');

  // The catalog lists a container image, type-badged, linking to its tag view (not a Maven folder view).
  await page.goto('/');
  const image = page.getByTestId('catalog-row').filter({ hasText: 'team/service' });
  await expect(image).toBeVisible();
  await expect(image.getByTestId('catalog-type')).toHaveText('container');
  await expect(image.getByTestId('catalog-link')).toHaveAttribute('href', '/c/apps/team/service');

  // The name filter narrows to the image just like a Maven coordinate.
  await page.getByTestId('topbar-search').fill('team/service');
  await expect(page.getByTestId('catalog-row').filter({ hasText: 'team/service' })).toBeVisible();
  await expect(page.getByTestId('catalog-row').filter({ hasText: 'com.example:widget' })).toHaveCount(0);
});
