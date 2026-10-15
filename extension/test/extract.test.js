import { extractJobFromPage } from '../src/lib/extract.js';

const LONG = 'Build payment services in Java and Spring Boot. '.repeat(6);

function page(html) {
  document.documentElement.innerHTML = `<head>${html}</head><body><h1>Careers</h1><p>Unrelated page text that must not be read.</p></body>`;
  window.getSelection().removeAllRanges();
}

const ld = (obj) => `<script type="application/ld+json">${JSON.stringify(obj)}</script>`;

test('reads a JobPosting and keeps paragraphs and bullets', () => {
  page(ld({
    '@context': 'https://schema.org', '@type': 'JobPosting', title: 'Senior Backend Engineer',
    hiringOrganization: { '@type': 'Organization', name: 'Northwind Payments' },
    jobLocation: { '@type': 'Place', address: { addressLocality: 'Bengaluru', addressRegion: 'KA', addressCountry: 'IN' } },
    description: '<p>We move money.</p><h3>You will</h3><ul><li>Build <b>payment</b> APIs</li><li>Own Kafka consumers</li></ul>',
  }));
  const job = extractJobFromPage();
  expect(job).toMatchObject({ title: 'Senior Backend Engineer', company: 'Northwind Payments', location: 'Bengaluru, KA, IN', source: 'jsonld' });
  expect(job.description).toBe('We move money.\n\nYou will\n\n• Build payment APIs\n• Own Kafka consumers');
  expect(job.description).not.toContain('Unrelated page text');
});

test('finds postings inside @graph and type arrays, handles double-escaped HTML and remote roles', () => {
  page(ld({ '@graph': [{ '@type': 'WebPage' }, {
    '@type': ['JobPosting', 'Thing'], title: 'SDE II', hiringOrganization: 'Kestrel', jobLocationType: 'TELECOMMUTE',
    description: '&lt;p&gt;Remote-first team.&lt;/p&gt;&lt;ul&gt;&lt;li&gt;Go&lt;/li&gt;&lt;/ul&gt;',
  }] }));
  const job = extractJobFromPage();
  expect(job).toMatchObject({ title: 'SDE II', company: 'Kestrel', location: 'Remote', source: 'jsonld' });
  expect(job.description).toBe('Remote-first team.\n\n• Go');
});

test('skips malformed JSON-LD and never reads the page body', () => {
  page('<script type="application/ld+json">{ not json</script><meta property="og:title" content="Platform Engineer at Quill">');
  const job = extractJobFromPage();
  expect(job.source).toBe('none');
  expect(job.description).toBeNull();
  expect(job.title).toBe('Platform Engineer at Quill');
});

test('the user’s selection wins over the listing data', () => {
  page(ld({ '@type': 'JobPosting', title: 'Data Engineer', hiringOrganization: { name: 'Banyan' }, description: '<p>Short.</p>' }));
  const p = document.createElement('p');
  p.textContent = LONG;
  document.body.append(p);
  const range = document.createRange();
  range.selectNodeContents(p);
  window.getSelection().addRange(range);
  const job = extractJobFromPage();
  expect(job.source).toBe('selection');
  expect(job.description).toBe(LONG.trim());
  expect(job.title).toBe('Data Engineer');
});

test('runs standalone, the way chrome.scripting injects it', () => {
  // executeScript serialises the function; it must not depend on anything outside itself.
  const standalone = new Function(`return (${extractJobFromPage.toString()})();`);
  page(ld({ '@type': 'JobPosting', title: 'QA Engineer', hiringOrganization: { name: 'Orbit' }, description: 'Test things.' }));
  expect(standalone()).toMatchObject({ title: 'QA Engineer', company: 'Orbit', description: 'Test things.' });
});
