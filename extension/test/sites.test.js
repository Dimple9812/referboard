import { blockedSite, readablePage } from '../src/lib/sites.js';

test('job boards are never read', () => {
  expect(blockedSite('https://www.linkedin.com/jobs/view/123')).toBe('LinkedIn');
  expect(blockedSite('https://in.linkedin.com/jobs/view/123')).toBe('LinkedIn');
  expect(blockedSite('https://www.naukri.com/job-listings-x')).toBe('Naukri');
  expect(blockedSite('https://in.indeed.com/viewjob?jk=1')).toBe('Indeed');
  expect(blockedSite('https://www.indeed.co.in/viewjob?jk=1')).toBe('Indeed');
  expect(blockedSite('https://www.glassdoor.co.in/job-listing/x')).toBe('Glassdoor');
});

test('career pages and ATS hosts can be read', () => {
  expect(blockedSite('https://boards.greenhouse.io/acme/jobs/1')).toBeNull();
  expect(blockedSite('https://jobs.lever.co/acme/1')).toBeNull();
  expect(blockedSite('https://careers.razorpay.com/job/1')).toBeNull();
  // Look-alike hosts aren't confused with the real sites.
  expect(blockedSite('https://notlinkedin.com/jobs')).toBeNull();
  expect(blockedSite('https://linkedin.com.example.org/jobs')).toBeNull();
});

test('only ordinary web pages', () => {
  expect(readablePage('https://jobs.lever.co/acme/1')).toBe(true);
  expect(readablePage('chrome://extensions')).toBe(false);
  expect(readablePage('https://chromewebstore.google.com/detail/x')).toBe(false);
  expect(readablePage('file:///tmp/jd.html')).toBe(false);
  expect(readablePage('not a url')).toBe(false);
});
