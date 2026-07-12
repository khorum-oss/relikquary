import { test, expect, type Page } from '@playwright/test';

// Theme settings (feature 019): choosing a preset re-skins the app through the --rq-* design tokens, a
// custom accent overrides the accent token, and the choice persists across a reload. Runs against
// `vite dev` with no backend seeding — an anonymous visitor's theme is kept in localStorage, and the
// app.html boot script re-applies it before first paint.

/** The resolved value of the primary accent token on the document root. */
function goldVar(page: Page): Promise<string> {
  return page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue('--rq-gold').trim(),
  );
}

test('a preset and a custom accent re-skin the app and persist across reload', async ({ page }) => {
  await page.goto('/settings');
  await expect(page.getByTestId('theme-settings')).toBeVisible();

  // The default palette is Vault Gold.
  await expect(page.getByTestId('preset-vault-gold')).toHaveAttribute('aria-pressed', 'true');

  // Switching to Emerald re-skins the accent token and marks the card active.
  await page.getByTestId('preset-emerald').click();
  await expect(page.getByTestId('preset-emerald')).toHaveAttribute('aria-pressed', 'true');
  expect(await goldVar(page)).toBe('#3fb27f');

  // A custom accent overrides the accent token live.
  await page.getByTestId('accent-input').evaluate((el: HTMLInputElement) => {
    el.value = '#123456';
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await expect(page.getByTestId('accent-value')).toHaveText('#123456');
  expect(await goldVar(page)).toBe('#123456');

  // The choice survives a reload (localStorage → the boot script applies it before paint).
  await page.reload();
  await expect(page.getByTestId('preset-emerald')).toHaveAttribute('aria-pressed', 'true');
  expect(await goldVar(page)).toBe('#123456');

  // Clearing the custom accent falls back to the preset's own accent.
  await page.getByTestId('accent-reset').click();
  await expect(page.getByTestId('accent-reset')).toHaveCount(0);
  expect(await goldVar(page)).toBe('#3fb27f');
});
