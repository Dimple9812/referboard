import { chromium, expect, test, type BrowserContext, type Page, type Worker } from '@playwright/test';
import { cpSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * The extension against the live stack. Playwright can't click the toolbar icon (the gesture that grants activeTab),
 * so this loads a copy whose manifest also has host permissions and opens the popup as a tab aimed at a given tab
 * (popup.html?tab=<id>). Everything else is the shipped code.
 */
const EXTENSION = fileURLToPath(new URL('../../extension', import.meta.url));
const RESUME = fileURLToPath(new URL('../../services/profile-service/src/test/resources/fixtures/sample-resume.txt', import.meta.url));
const APP = 'http://localhost:3000';
const EXT_ID = 'gjbninchlnhhincbfmmnjjjlhcgooejo';

const JD = `<p>Northwind Payments moves money for 40,000 Indian businesses.</p><h3>What you'll do</h3><ul>
<li>Build payment services in Java and Spring Boot</li><li>Own Kafka consumers and PostgreSQL schemas</li>
<li>Improve the latency of our payouts API</li></ul><h3>Requirements</h3><ul><li>4+ years with Java and Spring Boot</li>
<li>Experience with PostgreSQL and Redis</li><li>Docker and Kubernetes</li><li>gRPC</li></ul>`;

const jobPage = (title: string) => `<!doctype html><html><head><title>${title}</title>
<script type="application/ld+json">${JSON.stringify({ '@context': 'https://schema.org', '@type': 'JobPosting', title,
  hiringOrganization: { '@type': 'Organization', name: 'Northwind Payments' },
  jobLocation: { '@type': 'Place', address: { addressLocality: 'Bengaluru', addressCountry: 'IN' } }, description: JD })}</script>
<script>
  // Records any attempt to read the page, so the test can prove blocked sites are never touched.
  const qsa = Document.prototype.querySelectorAll;
  Document.prototype.querySelectorAll = function (...a) { window.__readByExtension = true; return qsa.apply(this, a); };
</script></head><body><h1>${title}</h1><p>Apply now.</p></body></html>`;

let context: BrowserContext;
let worker: Worker;

test.beforeAll(async () => {
  const dir = mkdtempSync(join(tmpdir(), 'rb-ext-'));
  cpSync(EXTENSION, dir, { recursive: true, filter: (src) => !src.includes('node_modules') && !src.includes('/test') });
  const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'));
  manifest.host_permissions = ['https://*/*'];   // test-only stand-in for the click that grants activeTab
  writeFileSync(join(dir, 'manifest.json'), JSON.stringify(manifest));
  context = await chromium.launchPersistentContext(mkdtempSync(join(tmpdir(), 'rb-profile-')), {
    headless: true,
    executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH,
    args: [`--disable-extensions-except=${dir}`, `--load-extension=${dir}`],
    viewport: { width: 1280, height: 860 },
  });
  await context.route('https://careers.northwind.test/**', (r) => r.fulfill({ contentType: 'text/html', body: jobPage('Senior Backend Engineer') }));
  await context.route('https://www.linkedin.com/**', (r) => r.fulfill({ contentType: 'text/html', body: jobPage('Backend Engineer') }));
  worker = context.serviceWorkers()[0] ?? await context.waitForEvent('serviceworker');
});

test.afterAll(async () => {
  await context?.close();
});

async function tabIdOf(urlPattern: string) {
  // Runs in the extension's service worker, where `chrome` exists.
  return worker.evaluate(async (pattern) => {
    const c = (globalThis as unknown as { chrome: { tabs: { query(q: object): Promise<{ id?: number }[]> } } }).chrome;
    return (await c.tabs.query({ url: pattern }))[0]?.id;
  }, urlPattern);
}

async function shot(page: Page, name: string) {
  if (process.env.E2E_SCREENSHOTS) await page.screenshot({ path: `e2e-screens/extension-${name}.png`, fullPage: true });
}

test('connect, read a careers page, check the resume, write a letter, save the job, disconnect', async () => {
  test.setTimeout(240_000);
  expect(new URL(worker.url()).host).toBe(EXT_ID);   // the key in the manifest pins the development ID

  // A user with a resume.
  const app = await context.newPage();
  await app.goto(`${APP}/signup`);
  await app.getByLabel('Full name').fill('Asha Rao');
  await app.getByLabel('Email').fill(`ext-${Date.now()}@example.com`);
  await app.getByLabel('Password').fill('correct-horse-9');
  await app.getByRole('button', { name: 'Create account' }).click();
  await expect(app).toHaveURL(/\/onboarding/);
  await app.locator('input[type=file]').setInputFiles(RESUME);
  await expect(app.getByRole('heading', { name: 'Check what we read' })).toBeVisible({ timeout: 60_000 });

  // Not connected yet: the popup offers Connect, which opens the web app's confirmation page.
  const popup = await context.newPage();
  await popup.goto(`chrome-extension://${EXT_ID}/src/popup.html?page=1`);
  await expect(popup.getByRole('heading', { name: 'Connect to ReferBoard' })).toBeVisible();
  await shot(popup, '01-connect');
  const [confirm] = await Promise.all([context.waitForEvent('page'), popup.getByRole('button', { name: 'Connect' }).click()]);
  await expect(confirm).toHaveURL(`${APP}/connect-extension?ext=${EXT_ID}`);
  await expect(confirm.getByRole('heading', { name: 'Connect the ReferBoard extension' })).toBeVisible();
  await shot(confirm, '02-approve');
  await confirm.getByRole('button', { name: 'Connect' }).click();
  await expect(confirm.getByRole('heading', { name: 'The extension is connected' })).toBeVisible();

  // A link carrying someone else's extension id gets nothing.
  await confirm.goto(`${APP}/connect-extension?ext=${'a'.repeat(32)}`);
  await expect(confirm.getByRole('heading', { name: "This link isn't from the ReferBoard extension" })).toBeVisible();

  // A careers page that publishes JobPosting data.
  const careers = await context.newPage();
  await careers.goto('https://careers.northwind.test/jobs/42');
  const careersTab = await tabIdOf('https://careers.northwind.test/*');
  await popup.goto(`chrome-extension://${EXT_ID}/src/popup.html?tab=${careersTab}`);
  await expect(popup.locator('#source')).toContainText('job details this page publishes');
  await expect(popup.getByLabel('Job title')).toHaveValue('Senior Backend Engineer');
  await expect(popup.getByLabel('Company')).toHaveValue('Northwind Payments');
  await expect(popup.getByLabel('Location')).toHaveValue('Bengaluru, IN');
  await expect(popup.getByLabel('Job description')).toHaveValue(/• Build payment services in Java and Spring Boot/);
  await shot(popup, '03-read');

  await popup.getByRole('button', { name: 'Check my resume' }).click();
  await expect(popup.locator('#analysis')).toContainText('Resume match', { timeout: 90_000 });
  await expect(popup.locator('.chip').first()).toBeVisible();
  await shot(popup, '04-score');

  const [letter] = await Promise.all([context.waitForEvent('page'), popup.getByRole('button', { name: 'Write a cover letter' }).click()]);
  await expect(letter).toHaveURL(/\/cover-letters\/[0-9a-f-]+$/, { timeout: 60_000 });
  await expect(letter.getByLabel('Letter text')).toHaveValue(/^Dear Hiring Team at Northwind Payments,/);
  await expect(letter.getByLabel('Letter text')).toHaveValue(/Regards,\nPriya Sharma$/);   // the name on the resume
  await shot(letter, '05-letter');

  await popup.getByRole('button', { name: 'Save to tracker' }).click();
  await expect(popup.locator('#saved')).toContainText('Saved to your tracker.');
  const [tracker] = await Promise.all([context.waitForEvent('page'), popup.getByRole('button', { name: 'Open the tracker' }).click()]);
  await expect(tracker.getByRole('dialog')).toContainText('Senior Backend Engineer');
  await expect(tracker.getByRole('dialog')).toContainText('careers.northwind.test');

  // LinkedIn: nothing is read, even though the page publishes the same data.
  const linkedin = await context.newPage();
  await linkedin.goto('https://www.linkedin.com/jobs/view/1');
  const linkedinTab = await tabIdOf('https://www.linkedin.com/*');
  await linkedin.evaluate(() => { (globalThis as unknown as { __readByExtension?: boolean }).__readByExtension = false; });
  await popup.goto(`chrome-extension://${EXT_ID}/src/popup.html?tab=${linkedinTab}`);
  await expect(popup.locator('#source')).toHaveText("ReferBoard doesn't read LinkedIn pages. Copy the job description there and paste it below.");
  await expect(popup.getByLabel('Job description')).toHaveValue('');
  expect(await linkedin.evaluate(() => (globalThis as unknown as { __readByExtension?: boolean }).__readByExtension)).toBe(false);
  await shot(popup, '06-blocked');

  // Settings lists the connection; disconnecting from the popup removes it.
  await app.goto(`${APP}/settings`);
  await expect(app.getByText('Chrome on Linux')).toBeVisible();
  await popup.getByRole('button', { name: 'Disconnect' }).click();
  await expect(popup.getByRole('heading', { name: 'Connect to ReferBoard' })).toBeVisible();
  await app.reload();
  await expect(app.getByText('Not connected.')).toBeVisible();
});
