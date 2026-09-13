import { Page } from '@playwright/test';
import { installLocalBrowserHarness } from './local-browser-harness';

export function installRoutesFromNonSpecModule(page: Page) {
  return installLocalBrowserHarness(page);
}
