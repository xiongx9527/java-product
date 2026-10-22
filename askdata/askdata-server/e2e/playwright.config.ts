import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 60000,
  expect: { timeout: 15000 },
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:8000',
    headless: true,
    channel: 'chrome',
    launchOptions: { args: ['--no-proxy-server'] },
    screenshot: 'only-on-failure',
    trace: 'off',
    viewport: { width: 1440, height: 900 },
  },
});

