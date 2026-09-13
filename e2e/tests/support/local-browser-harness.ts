import { Page, Route } from '@playwright/test';

export const LOCAL_ORIGIN = 'http://127.0.0.1:4173';

export interface BitbrushTestState {
  sockjs: Array<{ url: string }>;
  stomp: {
    constructorArgs: Array<Record<string, unknown>>;
    activations: number[];
    connectHeaders: Array<Record<string, string>>;
    subscriptions: string[];
    injectedMessages: Array<{ destination: string; body: string }>;
    deliver?: (destination: string, payload: unknown) => void;
  };
  turnstile: { renders: Array<{ sitekey: string }>; resets?: number[]; expire?: () => void };
}

export interface LocalBrowserHarness {
  blockedExternalRequests: string[];
}

export interface LocalBrowserHarnessOptions {
  apiHandler?: (route: Route) => Promise<void> | void;
}

declare global {
  interface Window {
    __bitbrushTest?: BitbrushTestState;
  }
}

const FAKE_SOCKJS = `
  (() => {
    const state = window.__bitbrushTest ||= {
      sockjs: [], stomp: { constructorArgs: [], activations: [], connectHeaders: [], subscriptions: [], injectedMessages: [] }, turnstile: { renders: [] }
    };
    window.SockJS = function SockJS(url) { state.sockjs.push({ url }); this.url = url; };
  })();
`;

const FAKE_STOMP = `
  (() => {
    const state = window.__bitbrushTest ||= {
      sockjs: [], stomp: { constructorArgs: [], activations: [], connectHeaders: [], subscriptions: [], injectedMessages: [] }, turnstile: { renders: [] }
    };
    const bodies = {
      '/topic/pixels': '{"x":1,"y":2,"color":"#00CC00","authorId":"author_local","erased":false}',
      '/topic/users/count': '1',
      '/app/users/count': '1',
      '/user/queue/bank': '{"balance":3,"maxBalance":5,"secondsUntilNextPoint":30}',
      '/app/bank': '{"balance":3,"maxBalance":5,"secondsUntilNextPoint":30}'
    };
    const subscribers = new Map();
    state.stomp.deliver = (destination, payload) => {
      const body = JSON.stringify(payload);
      state.stomp.injectedMessages.push({ destination, body });
      for (const callback of subscribers.get(destination) || []) callback({ body });
    };
    class Client {
      constructor(options) {
        this.options = options;
        state.stomp.constructorArgs.push(options);
        state.stomp.connectHeaders.push(options.connectHeaders || {});
      }
      activate() {
        state.stomp.activations.push(Date.now());
        this.socket = this.options.webSocketFactory && this.options.webSocketFactory();
        queueMicrotask(() => this.onConnect && this.onConnect({ command: 'CONNECTED', headers: {} }));
      }
      subscribe(destination, callback) {
        state.stomp.subscriptions.push(destination);
        const callbacks = subscribers.get(destination) || new Set();
        callbacks.add(callback);
        subscribers.set(destination, callbacks);
        if (Object.hasOwn(bodies, destination)) {
          const body = bodies[destination];
          state.stomp.injectedMessages.push({ destination, body });
          callback({ body });
        }
        return { id: destination, unsubscribe() { callbacks.delete(callback); } };
      }
      deactivate() { return Promise.resolve(); }
    }
    window.StompJs = { Client };
  })();
`;

const FAKE_TURNSTILE = `
  (() => {
    const state = window.__bitbrushTest ||= {
      sockjs: [], stomp: { constructorArgs: [], activations: [], connectHeaders: [], subscriptions: [], injectedMessages: [] }, turnstile: { renders: [] }
    };
    const widgets = [];
    let tokenGeneration = 0;
    const issueToken = options => queueMicrotask(() => options.callback && options.callback('local-turnstile-token-' + ++tokenGeneration));
    state.turnstile.resets = [];
    state.turnstile.expire = () => {
      for (const options of widgets) options['expired-callback'] && options['expired-callback']();
    };
    window.turnstile = {
      render(container, options) {
        state.turnstile.renders.push({ sitekey: options.sitekey });
        widgets.push(options);
        issueToken(options);
        return state.turnstile.renders.length;
      },
      reset(widgetId) {
        state.turnstile.resets.push(widgetId);
        if (widgets[widgetId - 1]) issueToken(widgets[widgetId - 1]);
      }
    };
  })();
`;

export async function installLocalBrowserHarness(
  page: Page,
  options: LocalBrowserHarnessOptions = {},
): Promise<LocalBrowserHarness> {
  const blockedExternalRequests: string[] = [];
  await page.route('**/*', route => {
    if (new URL(route.request().url()).origin === LOCAL_ORIGIN) {
      return route.continue();
    }
    blockedExternalRequests.push(route.request().url());
    return route.abort('blockedbyclient');
  });
  await page.route('**/sockjs-client@*/dist/sockjs.min.js', route =>
    route.fulfill({ contentType: 'application/javascript', body: FAKE_SOCKJS }));
  await page.route('**/@stomp/stompjs@*/bundles/stomp.umd.min.js', route =>
    route.fulfill({ contentType: 'application/javascript', body: FAKE_STOMP }));
  await page.route('https://fonts.googleapis.com/**', route =>
    route.fulfill({ contentType: 'text/css', body: '' }));
  await page.route('https://challenges.cloudflare.com/**', route =>
    route.fulfill({ contentType: 'application/javascript', body: FAKE_TURNSTILE }));
  await page.route('**/api/**', async route => {
    if (options.apiHandler) {
      await options.apiHandler(route);
      return;
    }
    const { pathname } = new URL(route.request().url());
    if (pathname === '/api/canvas') {
      await route.fulfill({ contentType: 'application/json', body: '[]' });
      return;
    }
    if (pathname === '/api/stats') {
      await route.fulfill({ contentType: 'application/json', body: '{"totalPixels":0,"colorDistribution":[]}' });
      return;
    }
    if (pathname === '/api/users' || pathname === '/api/users/reconnect') {
      const { uuid } = route.request().postDataJSON();
      await route.fulfill({
        status: pathname === '/api/users' ? 201 : 200,
        json: { uuid, username: 'Local_Tester' },
      });
      return;
    }
    await route.fulfill({ status: 204 });
  });
  return { blockedExternalRequests };
}

export function readBitbrushTestState(page: Page): Promise<BitbrushTestState | undefined> {
  return page.evaluate(() => window.__bitbrushTest);
}

export function deliverStompMessage(page: Page, destination: string, payload: unknown): Promise<void> {
  return page.evaluate(({ destination, payload }) => {
    window.__bitbrushTest.stomp.deliver(destination, payload);
  }, { destination, payload });
}

export function expireTurnstile(page: Page): Promise<void> {
  return page.evaluate(() => window.__bitbrushTest.turnstile.expire());
}
