import { expect, test, type Page } from '@playwright/test';
import { fileURLToPath } from 'node:url';

/**
 * Phase 3 journeys against a running stack: buying Pro (Razorpay replaced by e2e/support/mock-razorpay.mjs, which
 * the billing service must be pointed at), interview prep, and insights.
 */
const RESUME = fileURLToPath(new URL('../../services/profile-service/src/test/resources/fixtures/sample-resume.txt', import.meta.url));
const MOCK_RAZORPAY = process.env.E2E_MOCK_RAZORPAY ?? 'http://localhost:9001';

const JD = `Senior Backend Engineer, Northwind Payments

Northwind Payments moves money for Indian businesses.

What you'll do
- Build payment services in Java and Spring Boot
- Own Kafka consumers and the reliability of settlement pipelines
- Improve the latency of our payouts API with PostgreSQL and Redis

Requirements
- 4+ years with Java and Spring Boot
- PostgreSQL, Redis and Kafka in production
- Docker and Kubernetes
- gRPC`;

async function shot(page: Page, name: string) {
  if (process.env.E2E_SCREENSHOTS) await page.screenshot({ path: `e2e-screens/${test.info().project.name}-p3-${name}.png`, fullPage: true });
}

async function signUp(page: Page, withResume: boolean) {
  await page.goto('/signup');
  await page.getByLabel('Full name').fill('Asha Rao');
  await page.getByLabel('Email').fill(`p3-${Date.now()}-${Math.round(Math.random() * 1e6)}@example.com`);
  await page.getByLabel('Password').fill('correct-horse-9');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/onboarding/);
  if (withResume) {
    await page.locator('input[type=file]').setInputFiles(RESUME);
    await expect(page.getByRole('heading', { name: 'Check what we read' })).toBeVisible({ timeout: 60_000 });
  }
}

test('buy a Pro pass with Razorpay Checkout', async ({ page, context }) => {
  // Checkout's script and its calls are served by the mock, under the real Checkout URL.
  await context.route('https://checkout.razorpay.com/**', async (route) => {
    const url = new URL(route.request().url());
    const r = await fetch(`${MOCK_RAZORPAY}${url.pathname}${url.search}`);
    await route.fulfill({ status: r.status, contentType: r.headers.get('content-type') ?? 'text/plain', body: await r.text(),
      headers: { 'Access-Control-Allow-Origin': '*' } });
  });
  await signUp(page, false);
  await page.goto('/pro');
  await expect(page.getByRole('heading', { name: 'ReferBoard Pro' })).toBeVisible();
  await expect(page.getByText('Test mode')).toBeVisible();
  await shot(page, '01-pro');
  await page.getByRole('button', { name: 'Pay ₹2,499' }).click();
  await expect(page).toHaveURL(/\/settings#billing$/, { timeout: 30_000 });
  await expect(page.getByText(/^Pro is on until/)).toBeVisible();
  const billing = page.locator('#billing');
  await expect(billing.getByText('Pro', { exact: true })).toBeVisible();
  await expect(billing.getByText('Pro for 1 year')).toBeVisible();
  await expect(billing.getByText('₹2,499')).toBeVisible();
  await expect(billing.getByText(/365 days left|364 days left/)).toBeVisible();
  await shot(page, '02-billing');
  // The new token carries PRO: unlimited analyses and prep packs.
  await page.goto('/interview');
  await expect(page.getByText('free packs this month')).toHaveCount(0);
  if (test.info().project.name === 'desktop') await expect(page.getByText('Pro plan')).toBeVisible();
});

test('build an interview prep pack, practise, and see it in insights', async ({ page }) => {
  test.setTimeout(240_000);
  await signUp(page, true);
  await page.goto('/analyzer');
  await page.getByLabel('Job title (optional)').fill('Senior Backend Engineer');
  await page.getByLabel('Company (optional)').fill('Northwind Payments');
  await page.getByLabel('Job description', { exact: true }).fill(JD);
  await page.getByRole('button', { name: 'Analyse' }).click();
  await expect(page.getByText('Resume match score')).toBeVisible({ timeout: 90_000 });

  await page.getByRole('button', { name: 'Build a prep pack' }).click();
  await expect(page).toHaveURL(/\/interview\/[0-9a-f-]+$/, { timeout: 60_000 });
  await expect(page.getByRole('heading', { name: 'Interview prep' })).toBeVisible();
  await expect(page.getByText('For Senior Backend Engineer, Northwind Payments')).toBeVisible();
  await expect(page.getByRole('button', { name: /Have you worked with gRPC\?/ })).toBeVisible();
  await shot(page, '03-prep');

  const first = page.getByRole('button', { name: /Tell me about yourself\./ });
  await first.click();
  await expect(page.getByText('From your resume', { exact: false }).first()).toBeVisible();
  await page.getByLabel('Your notes').first().fill('Settlements at Razorpay, 2M a day. Then why Northwind.');
  await page.getByRole('radio', { name: 'Confident' }).first().click();
  await expect(page.getByText('1 of', { exact: false }).first()).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: 'Saved' }).first()).toBeVisible();
  await shot(page, '04-practise');

  await page.reload();
  await page.getByRole('button', { name: /Tell me about yourself\./ }).click();
  await expect(page.getByLabel('Your notes').first()).toHaveValue('Settlements at Razorpay, 2M a day. Then why Northwind.');

  // Insights are built from events, so give them a moment.
  await expect(async () => {
    await page.goto('/insights');
    await expect(page.getByText('JD analyses').first()).toBeVisible({ timeout: 3_000 });
    await expect(page.locator('dt', { hasText: 'Interview prep packs' }).locator('xpath=following-sibling::dd[1]')).toHaveText('1', { timeout: 3_000 });
  }).toPass({ timeout: 45_000 });
  await shot(page, '05-insights');
});
