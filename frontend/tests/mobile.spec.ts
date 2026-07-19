import { test, expect, type Page } from '@playwright/test';

// Mobile-friendly responsive UI (feature 025) against the e2e backend (scripts/e2e.sh). The desktop suite
// keeps the default Desktop Chrome viewport; these tests opt into narrow phone viewports so they exercise
// exactly the responsive behavior — the drawer navigation, no sideways page scroll, and small-phone fit —
// without changing how the existing specs run.

const PHONE = { width: 375, height: 812 };
const SMALL_PHONE = { width: 320, height: 720 };
const DESKTOP = { width: 1280, height: 900 };

// The rail is an off-canvas overlay at narrow widths: closed ⇒ translated fully off the left edge (x < 0),
// open ⇒ slid to x ≈ 0. Playwright's toBeVisible() treats an off-canvas element as visible, so we read the
// bounding box (and the backdrop, which only exists while open) to tell open from closed.
async function drawerX(page: Page): Promise<number> {
  const box = await page.getByTestId('nav-drawer').boundingBox();
  return box?.x ?? Number.NaN;
}

// Neither the page as a whole nor the scrolling content column may overflow horizontally (a wrapped wide
// table scrolls inside its own region, so it does not count against the content column).
async function expectNoHorizontalPageScroll(page: Page) {
  const overflow = await page.evaluate(() => {
    const de = document.documentElement;
    const content = document.querySelector('[data-testid="app-content"]') as HTMLElement | null;
    return {
      page: de.scrollWidth - de.clientWidth,
      content: content ? content.scrollWidth - content.clientWidth : 0,
    };
  });
  expect(overflow.page, 'page must not scroll horizontally').toBeLessThanOrEqual(1);
  expect(overflow.content, 'content column must not scroll horizontally').toBeLessThanOrEqual(1);
}

// ---- User Story 1: navigate the whole app from a phone ------------------------------------------------

test.describe('US1 — drawer navigation on a phone', () => {
  test.use({ viewport: PHONE });

  test('the rail is a drawer opened by the ☰ control and dismissed by navigate / backdrop / Escape', async ({
    page,
  }) => {
    await page.goto('/');

    // N1: the rail is off-canvas and a menu control is present; no backdrop (closed). Poll the position so
    // the assertion is robust to the slide transition rather than reading a mid-animation frame.
    await expect(page.getByTestId('nav-toggle')).toBeVisible();
    await expect.poll(() => drawerX(page)).toBeLessThan(0);
    await expect(page.getByTestId('nav-backdrop')).toHaveCount(0);

    // N2: opening reveals the drawer (slid to x≈0) with every section and the session control.
    await page.getByTestId('nav-toggle').click();
    await expect(page.getByTestId('nav-backdrop')).toBeVisible();
    await expect.poll(() => drawerX(page)).toBeGreaterThanOrEqual(-1);
    const drawer = page.getByTestId('nav-drawer');
    for (const label of ['Dashboard', 'Repositories', 'Publish', 'Users & Tokens', 'Settings']) {
      await expect(drawer.getByRole('link', { name: label })).toBeVisible();
    }
    await expect(drawer.getByTestId('login-button')).toBeVisible();

    // N3: choosing a destination navigates and closes the drawer.
    await drawer.getByRole('link', { name: 'Publish' }).click();
    await expect(page).toHaveURL(/\/publish$/);
    await expect(page.getByTestId('nav-backdrop')).toHaveCount(0);
    await expect.poll(() => drawerX(page)).toBeLessThan(0);

    // N4: the backdrop dismisses without navigating.
    await page.getByTestId('nav-toggle').click();
    await expect(page.getByTestId('nav-backdrop')).toBeVisible();
    await page.getByTestId('nav-backdrop').click();
    await expect(page.getByTestId('nav-backdrop')).toHaveCount(0);
    await expect(page).toHaveURL(/\/publish$/);

    // N5: Escape dismisses without navigating.
    await page.getByTestId('nav-toggle').click();
    await expect(page.getByTestId('nav-backdrop')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('nav-backdrop')).toHaveCount(0);
    await expect(page).toHaveURL(/\/publish$/);
  });
});

test.describe('US1 — desktop keeps the permanent rail', () => {
  test.use({ viewport: DESKTOP });

  test('no menu control at desktop width; the rail is permanently visible', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('nav-toggle')).toBeHidden();
    // The permanent rail sits at the left edge (in flow, not translated off-canvas).
    expect(await drawerX(page)).toBe(0);
  });
});

// ---- User Story 2: read wide content without sideways page scrolling ----------------------------------

test.describe('US2 — no sideways page scroll on a phone', () => {
  test.use({ viewport: PHONE });

  test('primary screens do not scroll the page sideways; wide tables scroll within their region', async ({
    page,
  }) => {
    // Catalog (default view on '/'): the page does not scroll sideways, but the catalog table scrolls
    // inside its own wrapper.
    await page.goto('/');
    await expect(page.getByTestId('catalog')).toBeVisible();
    await expectNoHorizontalPageScroll(page);
    const catalogOverflow = await page
      .getByTestId('catalog-scroll')
      .evaluate((el) => el.scrollWidth - el.clientWidth);
    expect(catalogOverflow, 'the wide catalog table scrolls within its own region').toBeGreaterThan(0);

    // Dashboard — reached through the drawer (client-side navigation).
    await page.getByTestId('nav-toggle').click();
    await page.getByTestId('nav-drawer').getByRole('link', { name: 'Dashboard' }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
    await expectNoHorizontalPageScroll(page);

    // Container image list and its tag table — navigate via the UI (a cold deep-link is proxied to the
    // backend by the dev server, a harness artifact).
    await page.goto('/');
    await page.getByTestId('repos-tab').click();
    await page.getByTestId('repo-row').filter({ hasText: 'apps' }).getByTestId('repo-link').click();
    await expect(page).toHaveURL(/\/c\/apps$/);
    await expectNoHorizontalPageScroll(page);

    await page.getByTestId('image-row').filter({ hasText: 'team/service' }).getByTestId('image-link').click();
    await expect(page.getByTestId('image-title')).toHaveText('team/service');
    await expectNoHorizontalPageScroll(page);
    const tagOverflow = await page
      .getByTestId('tag-table-scroll')
      .evaluate((el) => el.scrollWidth - el.clientWidth);
    expect(tagOverflow, 'the wide tag table scrolls within its own region').toBeGreaterThan(0);

    // Maven file listing.
    await page.goto('/');
    await page.getByTestId('repos-tab').click();
    await page.getByTestId('repo-row').filter({ hasText: 'releases' }).getByTestId('repo-link').click();
    await expect(page).toHaveURL(/\/r\/releases/);
    await expect(page.getByTestId('listing')).toBeVisible();
    await expectNoHorizontalPageScroll(page);
  });
});

// ---- User Story 3: fixed panels and touch targets fit a small phone -----------------------------------

test.describe('US3 — small-phone fit and touch targets', () => {
  test.use({ viewport: SMALL_PHONE });

  test('the sign-in card fits a 320px viewport and primary controls are comfortably tappable', async ({
    page,
  }) => {
    await page.goto('/');

    // F3: the menu control is a comfortable tap target.
    const toggleBox = await page.getByTestId('nav-toggle').boundingBox();
    expect(toggleBox?.height ?? 0).toBeGreaterThanOrEqual(40);

    // Open the drawer and sign in — the nav links are comfortable targets too (F3).
    await page.getByTestId('nav-toggle').click();
    const navLink = page.getByTestId('nav-drawer').getByRole('link', { name: 'Publish' });
    const navBox = await navLink.boundingBox();
    expect(navBox?.height ?? 0).toBeGreaterThanOrEqual(40);

    await page.getByTestId('nav-drawer').getByTestId('login-button').click();

    // F1: the sign-in card fits within the 320px viewport (no clipped/off-screen content).
    await expect(page.getByTestId('login-screen')).toBeVisible();
    const cardBox = await page.locator('[data-testid="login-screen"] .card').boundingBox();
    expect(cardBox).not.toBeNull();
    expect(cardBox!.x).toBeGreaterThanOrEqual(0);
    expect(cardBox!.x + cardBox!.width).toBeLessThanOrEqual(SMALL_PHONE.width + 1);
  });

  test('the artifact search spans the width without causing overflow', async ({ page }) => {
    await page.goto('/');
    // F2: the search is present on the repositories/catalog screen and does not push the page wider.
    await expect(page.getByTestId('topbar-search')).toBeVisible();
    await expectNoHorizontalPageScroll(page);
  });
});
