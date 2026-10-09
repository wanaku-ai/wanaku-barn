import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests', testMatch: ['semantic-routers.spec.ts', 'data-store-downloads.spec.ts', 'kamelets.spec.ts'], workers: 1,
  forbidOnly: Boolean(process.env.CI), retries: 0, reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:4173/admin/', trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm --prefix ../../../apps/ui/admin run preview -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173/admin/', reuseExistingServer: !process.env.CI,
    timeout: 30000,
  },
});
