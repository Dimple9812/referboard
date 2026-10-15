import { expect, test, type Page } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const RESUME = fileURLToPath(new URL('../../services/profile-service/src/test/resources/fixtures/sample-resume.txt', import.meta.url));

async function shot(page: Page, name: string) {
  if (!process.env.E2E_SCREENSHOTS) return;
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await page.screenshot({ path: `e2e-screens/${test.info().project.name}-${name}.png`, fullPage: true });
}

/** One user, start to finish: sign up, onboard, browse, analyze, ask for a referral, track, settings. */
test('a new user can get from sign-up to a tracked application', async ({ page }) => {
  const email = `e2e-${Date.now()}-${Math.round(Math.random() * 1e6)}@example.com`;

  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await shot(page, '01-landing');

  await page.goto('/signup');
  await page.getByLabel('Full name').fill('Asha Rao');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('correct-horse-9');
  await shot(page, '02-signup');
  await page.getByRole('button', { name: 'Create account' }).click();

  await expect(page).toHaveURL(/\/onboarding/);
  await shot(page, '03-onboarding-upload');
  await page.locator('input[type=file]').setInputFiles(RESUME);
  await expect(page.getByRole('heading', { name: 'Check what we read' })).toBeVisible({ timeout: 60_000 });
  await shot(page, '04-onboarding-review');
  for (const month of await page.locator('input[type=month][aria-label^="Start of"]').all()) {
    if (!(await month.inputValue())) await month.fill('2021-06');
  }
  await page.getByRole('button', { name: 'Save and continue' }).click();
  await expect(page.getByRole('heading', { name: /What you.re looking for/ })).toBeVisible();
  await shot(page, '05-onboarding-preferences');
  await page.getByRole('button', { name: 'Show my jobs' }).click();

  await expect(page).toHaveURL(/\/jobs/);
  await page.getByRole('tab', { name: /Search/ }).click();
  const firstJob = page.locator('main a[href^="/jobs/"]').first();
  await expect(firstJob).toBeVisible();
  await shot(page, '06-jobs');
  await page.getByRole('tab', { name: /Recommended/ }).click();
  await shot(page, '06b-jobs-recommended');

  await page.getByRole('tab', { name: /Search/ }).click();
  await page.locator('main a[href^="/jobs/"]').first().click();
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]+$/);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await shot(page, '07-job-detail');
  const jobUrl = page.url();

  await page.getByRole('button', { name: /Save to tracker/ }).click();
  await expect(page.getByText('Saved to your tracker')).toBeVisible();

  await page.getByRole('link', { name: /Check my resume/ }).click();
  await expect(page).toHaveURL(/\/analyzer/);
  await shot(page, '08-analyzer-new');
  await page.locator('form button[type=submit]').last().click();
  await expect(page.getByText('Resume match score')).toBeVisible({ timeout: 60_000 });
  await shot(page, '09-analyzer-report');

  await page.goto(jobUrl);
  await page.getByRole('link', { name: /Find people to ask/ }).click();
  await expect(page).toHaveURL(/\/referrals\/jobs\//);
  await shot(page, '10-referral-assistant');

  await page.goto('/referrals');
  await shot(page, '11-referrals');
  await page.goto('/tracker');
  await expect(page.getByText(/Saved/).first()).toBeVisible();
  await shot(page, '12-tracker');
  await page.goto('/notifications');
  await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible();
  await shot(page, '13-notifications');
  await page.goto('/settings');
  await expect(page.getByRole('heading', { name: 'Resumes', exact: true })).toBeVisible();
  await shot(page, '14-settings');

  // The session survives a reload: the refresh cookie brings back an access token.
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto('/tracker');
  await expect(page).toHaveURL(/\/login/);
});
