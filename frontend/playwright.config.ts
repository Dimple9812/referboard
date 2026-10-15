import { defineConfig, devices } from '@playwright/test';

/**
 * Smoke test against a running stack: `npm run dev` (or the nginx image) in front of the API gateway.
 * Set E2E_BASE_URL to point elsewhere; set E2E_SCREENSHOTS=1 to save a full-page screenshot of each screen.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 240_000,
  expect: { timeout: 20_000 },
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH } : {},
  },
  projects: [
    { name: 'desktop', testIgnore: /extension\.spec/, use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'mobile', testIgnore: /extension\.spec/, use: { ...devices['Pixel 7'] } },
    // Loads ../extension into Chromium; the web app must be on http://localhost:3000 (allowed by the manifest).
    { name: 'extension', testMatch: /extension\.spec/ },
  ],
});
