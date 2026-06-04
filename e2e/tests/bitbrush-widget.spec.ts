import { test, expect, Page } from '@playwright/test';

const BITBRUSH_SERVER = 'https://bitbrush.fly.dev';

/** Pre-seed identity so the username overlay doesn't block interactions.
 *  Turnstile invisible mode doesn't work in headless Playwright. */
async function seedIdentity(page: Page) {
  await page.goto('/', { waitUntil: 'load' });
  const uuid = await page.evaluate(() => {
    let uuid = localStorage.getItem('bitbrush_widget_uuid');
    if (!uuid) {
      uuid = crypto.randomUUID();
      localStorage.setItem('bitbrush_widget_uuid', uuid);
    }
    localStorage.setItem('bitbrush_widget_username', 'PlaywrightBot');
    return uuid;
  });
  // Register the user server-side (bypass Turnstile with Cloudflare test keys in dev)
  // In prod, we just seed localStorage — the widget will re-register silently on connect,
  // and even if it fails (403), the overlay won't show because username is in localStorage.
  return uuid;
}

/** Navigate to the BitBrush quadrant (top-left) from the intro page */
async function navigateToBitbrush(page: Page) {
  await page.goto('/', { waitUntil: 'load' });
  await page.waitForTimeout(2000);

  // The site is a quad layout; BitBrush is top-left (quad 3).
  // Default view is TR (quad 0). Press ArrowLeft to go to TL (quad 3).
  await page.keyboard.press('ArrowLeft');
  await page.waitForTimeout(3000); // wait for animation

  // Verify the widget loaded
  await expect(page.locator('.bitbrush-widget')).toBeVisible({ timeout: 15000 });
}

/** Seed identity then navigate to BitBrush */
async function setupAndNavigate(page: Page) {
  await seedIdentity(page);
  await navigateToBitbrush(page);
  // Ensure overlay is not visible
  await expect(page.locator('.bbw-overlay')).toBeHidden({ timeout: 5000 });
}

test.describe('BitBrush Widget on Portfolio Site', () => {

  test('widget loads with all key elements', async ({ page }) => {
    await setupAndNavigate(page);

    // Canvas exists
    await expect(page.locator('.bitbrush-widget canvas')).toBeVisible();

    // Status bar
    await expect(page.locator('.bbw-status')).toBeVisible();

    // Bank display
    await expect(page.locator('.bbw-bank')).toBeVisible();

    // Palette
    await expect(page.locator('.bbw-palette')).toBeVisible();
    const swatches = page.locator('.bbw-swatch');
    expect(await swatches.count()).toBeGreaterThan(10);

    // Footer link
    await expect(page.locator('.bbw-footer a')).toHaveAttribute('href', BITBRUSH_SERVER);
  });

  test('WebSocket connects and shows online count', async ({ page }) => {
    await setupAndNavigate(page);

    // Should show "N online" once connected
    const statusText = page.locator('.bbw-status-text');
    await expect(statusText).toHaveText(/\d+ online/, { timeout: 15000 });
  });

  test('canvas loads pixel data from server', async ({ page }) => {
    await setupAndNavigate(page);
    await page.waitForTimeout(3000);

    const pixelCount = await page.evaluate(async (server) => {
      const resp = await fetch(server + '/api/canvas');
      const data = await resp.json();
      return data.length;
    }, BITBRUSH_SERVER);

    expect(pixelCount).toBeGreaterThan(0);
  });

  test('username overlay shows when no identity', async ({ page }) => {
    // Clear any saved identity
    await page.goto('/', { waitUntil: 'load' });
    await page.evaluate(() => {
      localStorage.removeItem('bitbrush_widget_username');
      localStorage.removeItem('bitbrush_widget_uuid');
    });

    await navigateToBitbrush(page);

    // Should show username overlay
    const overlay = page.locator('.bbw-overlay');
    await expect(overlay).toBeVisible({ timeout: 15000 });

    // Input and button should be present
    await expect(overlay.locator('input')).toBeVisible();
    await expect(overlay.locator('button')).toBeVisible();
  });

  test('REST API CORS works from portfolio domain', async ({ page }) => {
    await page.goto('/', { waitUntil: 'load' });

    const getResult = await page.evaluate(async (server) => {
      try {
        const resp = await fetch(server + '/api/canvas');
        return { ok: resp.ok, status: resp.status };
      } catch (e) {
        return { ok: false, error: (e as Error).message };
      }
    }, BITBRUSH_SERVER);

    expect(getResult.ok).toBe(true);
    expect(getResult.status).toBe(200);
  });

  test('mouse wheel zoom works', async ({ page }) => {
    await setupAndNavigate(page);
    await page.waitForTimeout(2000);

    const canvas = page.locator('.bitbrush-widget canvas');

    // Zoom in with mouse wheel
    await canvas.hover();
    await page.mouse.wheel(0, -300); // scroll up = zoom in
    await page.waitForTimeout(500);

    // Zoom badge should appear
    const badge = page.locator('.bbw-zoom-badge');
    await expect(badge).toHaveClass(/visible/, { timeout: 3000 });
    const badgeText = await badge.textContent();
    expect(badgeText).toMatch(/\d+\.\d+x/);

    // Click badge to reset zoom
    await badge.click();
    await page.waitForTimeout(500);
    await expect(badge).not.toHaveClass(/visible/);
  });

  test('pointer-based pinch zoom gesture works', async ({ page }) => {
    await setupAndNavigate(page);
    await page.waitForTimeout(2000);

    const canvas = page.locator('.bitbrush-widget canvas');
    const box = await canvas.boundingBox();
    if (!box) throw new Error('Canvas not found');

    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;

    // Dispatch pointer events to simulate a two-finger pinch
    await page.evaluate(({ cx, cy }) => {
      const canvas = document.querySelector('.bitbrush-widget canvas')!;

      function fire(type: string, id: number, x: number, y: number) {
        canvas.dispatchEvent(new PointerEvent(type, {
          pointerId: id,
          clientX: x,
          clientY: y,
          bubbles: true,
          cancelable: true,
          pointerType: 'touch',
        }));
      }

      // Two fingers down close together
      fire('pointerdown', 1, cx - 20, cy);
      fire('pointerdown', 2, cx + 20, cy);

      // Spread fingers apart (zoom in)
      for (let i = 1; i <= 8; i++) {
        const spread = 20 + i * 12;
        fire('pointermove', 1, cx - spread, cy);
        fire('pointermove', 2, cx + spread, cy);
      }

      // Lift fingers
      fire('pointerup', 1, cx - 116, cy);
      fire('pointerup', 2, cx + 116, cy);
    }, { cx, cy });

    await page.waitForTimeout(500);

    // Zoom badge should be visible
    const badge = page.locator('.bbw-zoom-badge');
    await expect(badge).toHaveClass(/visible/, { timeout: 3000 });
  });

  test('no page errors during widget interaction', async ({ page }) => {
    const pageErrors: string[] = [];
    page.on('pageerror', err => pageErrors.push(err.message));

    await setupAndNavigate(page);
    await page.waitForTimeout(5000);

    expect(pageErrors).toEqual([]);
  });

  test('More colors toggle works', async ({ page }) => {
    await setupAndNavigate(page);

    const swatchesBefore = await page.locator('.bbw-swatch').count();
    const moreBtn = page.locator('.bbw-more-btn');

    await moreBtn.click();
    await page.waitForTimeout(300);

    const swatchesAfter = await page.locator('.bbw-swatch').count();
    expect(swatchesAfter).toBeGreaterThan(swatchesBefore);

    await expect(moreBtn).toHaveText('Fewer colors');

    // Click again to collapse
    await moreBtn.click();
    await page.waitForTimeout(300);
    const swatchesFinal = await page.locator('.bbw-swatch').count();
    expect(swatchesFinal).toBe(swatchesBefore);
  });

  test('bank display shows balance', async ({ page }) => {
    await setupAndNavigate(page);

    // Wait for WebSocket to deliver bank state
    await expect(page.locator('.bbw-status-text')).toHaveText(/\d+ online/, { timeout: 15000 });
    await page.waitForTimeout(2000);

    // Bank label should show balance in format "N/M"
    const bankText = await page.locator('.bbw-bank-label').textContent();
    expect(bankText).toMatch(/\d+\/\d+/);
  });
});
