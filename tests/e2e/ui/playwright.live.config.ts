import { defineConfig, devices } from '@playwright/test';

const uiUrl = new URL(process.env.BARN_UI_URL ?? 'http://127.0.0.1:4174/admin/');

export default defineConfig({
  outputDir: './test-results-live',
  testDir: './tests', testMatch: 'semantic-routers.live.spec.ts', workers: 1,
  forbidOnly: Boolean(process.env.CI), retries: 0, reporter: 'list', timeout: 60000,
  use: { baseURL: uiUrl.toString(), trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm --prefix ../../../apps/ui/admin run dev -- --host ${uiUrl.hostname} --port ${uiUrl.port || '80'} --strictPort`,
    url: uiUrl.toString(), reuseExistingServer: false, timeout: 60000,
    env: { BARN_URL: process.env.BARN_URL ?? 'http://127.0.0.1:8180' },
  },
});
