import { expect, test } from '@playwright/test';
import { deliverStompMessage, installLocalBrowserHarness, readBitbrushTestState } from '../support/local-browser-harness';

// Remaining race, outside this fix: a WebSocket delta or in-flight metadata
// response can cross snapshot application. These tests keep those ordered.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

for (const widget of [false, true]) {
  for (const status of [400, 201]) {
    test(`${widget ? 'widget' : 'full page'}: authoritative snapshot replaces stale pixels after batch ${status}`, async ({ page }) => {
      await installLocalBrowserHarness(page);
      await page.addInitScript(widget => {
        localStorage.setItem(widget ? 'bitbrush_widget_uuid' : 'bitbrush_uuid', '11111111-2222-4333-8444-555555555555');
      }, widget);
      let snapshot = [{ x: 120, y: 120, color: '#00CC00' }];
      let reads = 0;
      let submitted: Array<{ x: number; y: number }> = [];
      let release!: () => void;
      const reply = new Promise<void>(resolve => { release = resolve; });
      await page.route('**/api/canvas', async route => {
        reads++;
        await route.fulfill({ json: snapshot });
      });
      await page.route('**/api/pixels', async route => {
        submitted = route.request().postDataJSON().pixels;
        // The existing empty 201 response can acknowledge only an affordable prefix.
        snapshot = status === 201 ? [{ ...submitted[0], color: '#00CC00' }] : [];
        await reply;
        await route.fulfill({ status, body: '' });
      });
      await page.goto(widget ? '/widget-host.html' : '/index.html');
      await expect.poll(async () => (await readBitbrushTestState(page))?.stomp.subscriptions.length).toBe(5);
      const canvas = page.locator(widget ? '.bitbrush-widget canvas' : '#canvas');
      const pixel = (x: number, y: number) => canvas.evaluate((element, p) =>
        Array.from((element as HTMLCanvasElement).getContext('2d')!.getImageData(p.x * 2, p.y * 2, 1, 1).data), { x, y });
      await expect.poll(() => pixel(120, 120)).toEqual([0, 204, 0, 255]);
      await deliverStompMessage(page, '/user/queue/bank', { balance: 5, maxBalance: 5, secondsUntilNextPoint: 30 });
      if (widget) {
        await canvas.evaluate(element => {
          const target = element as HTMLCanvasElement;
          const box = target.getBoundingClientRect();
          target.setPointerCapture = () => {};
          const event = (type: string, x: number) => target.dispatchEvent(new PointerEvent(type, {
            bubbles: true, pointerId: 1, pointerType: 'mouse', button: 0,
            clientX: box.left + x, clientY: box.top + box.height / 2,
          }));
          event('pointerdown', box.width / 2);
          event('pointermove', box.width / 2 + box.width / 50);
          event('pointerup', box.width / 2 + box.width / 50);
        });
      } else {
        const box = (await canvas.boundingBox())!;
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
        await page.mouse.down();
        await page.mouse.move(box.x + box.width / 2 + box.width / 50, box.y + box.height / 2, { steps: 3 });
        await page.mouse.up();
      }
      await expect.poll(() => submitted.length).toBeGreaterThan(1);
      const last = submitted[submitted.length - 1];
      expect(await pixel(last.x, last.y)).not.toEqual([0, 0, 0, 255]);
      if (!widget) {
        await page.evaluate(`pixelInfoCache.set('stale', {}); coordToAuthor.set('120,120', 'stale'); currentHighlightAuthor = 'stale'; document.getElementById('tooltip').style.display = 'block';`);
      }
      const before = reads;
      release();
      await expect.poll(() => reads).toBeGreaterThan(before);
      await expect.poll(() => pixel(120, 120)).toEqual([0, 0, 0, 255]);
      await expect.poll(() => pixel(last.x, last.y)).toEqual([0, 0, 0, 255]);
      if (status === 201) {
        await expect.poll(() => pixel(submitted[0].x, submitted[0].y)).toEqual([0, 204, 0, 255]);
      }
      if (!widget) {
        expect(await page.evaluate(`[pixelInfoCache.size, coordToAuthor.size, currentHighlightAuthor]`)).toEqual([0, 0, null]);
        await expect(page.locator('#tooltip')).toBeHidden();
      }
    });
  }
}
