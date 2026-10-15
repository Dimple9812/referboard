import { readActiveTab, renderPopup } from '../src/popup.js';
import { ApiError, SessionEndedError } from '../src/lib/session.js';

const JD = 'We are hiring a Senior Backend Engineer to build payment services in Java and Spring Boot. '.repeat(4);
const SESSION = { apiBase: 'http://localhost:3000', appBase: 'http://localhost:3000', user: { email: 'asha@example.com' } };

function fakeClient(session, handler = async () => ({})) {
  const calls = [];
  return {
    calls,
    load: async () => session,
    disconnect: vi.fn(async () => undefined),
    request: async (method, path, body) => {
      calls.push({ method, path, body });
      return handler(method, path, body);
    },
  };
}

async function mount(read, client, openTab = vi.fn()) {
  document.body.innerHTML = '<main id="app"></main>';
  const root = document.getElementById('app');
  await renderPopup(root, { client, target: { read, extensionId: 'gjbninchlnhhincbfmmnjjjlhcgooejo' }, openTab });
  return { root, openTab };
}

const $ = (sel) => document.querySelector(sel);
const click = async (el) => {
  el.click();
  await new Promise((r) => setTimeout(r, 0));
  await new Promise((r) => setTimeout(r, 0));
};
const byText = (text) => [...document.querySelectorAll('button')].find((b) => b.textContent === text);

test('not connected: Connect opens the web app with this extension’s id', async () => {
  const { openTab } = await mount({ mode: 'jsonld', job: {} }, fakeClient(null));
  expect($('h1').textContent).toBe('Connect to ReferBoard');
  await click(byText('Connect'));
  expect(openTab).toHaveBeenCalledWith('http://localhost:3000/connect-extension?ext=gjbninchlnhhincbfmmnjjjlhcgooejo');
});

test('a read listing is prefilled and checked against the resume', async () => {
  const client = fakeClient(SESSION, async (m, path) => path === '/api/analyses'
    ? { id: 'an1', score: 72, verdict: 'GOOD', missingSkills: { required: [{ label: 'gRPC' }, { label: 'Go' }] } }
    : { id: 'l1' });
  const { openTab } = await mount({ mode: 'jsonld', url: 'https://jobs.lever.co/n/1',
    job: { title: 'Senior Backend Engineer', company: 'Northwind', location: 'Bengaluru', description: JD } }, client);
  expect($('#source').textContent).toContain('job details this page publishes');
  expect($('#title').value).toBe('Senior Backend Engineer');
  expect($('#description').value).toBe(JD);
  expect($('footer').textContent).toContain('asha@example.com');

  await click(byText('Check my resume'));
  expect(client.calls[0]).toEqual({ method: 'POST', path: '/api/analyses',
    body: { jdText: JD.trim(), jobTitle: 'Senior Backend Engineer', companyName: 'Northwind' } });
  expect($('#analysis').textContent).toContain('72');
  expect($('#analysis').textContent).toContain('Good match');
  expect([...document.querySelectorAll('.chip')].map((c) => c.textContent)).toEqual(['gRPC', 'Go']);

  await click(byText('Write a cover letter'));
  expect(client.calls[1]).toEqual({ method: 'POST', path: '/api/analyses/an1/cover-letters', body: { length: 'STANDARD' } });
  expect(openTab).toHaveBeenCalledWith('http://localhost:3000/cover-letters/l1');
});

test('on a blocked job board nothing is read; the user pastes instead', async () => {
  await mount({ mode: 'blocked', site: 'LinkedIn', job: null, url: 'https://www.linkedin.com/jobs/view/1' }, fakeClient(SESSION));
  expect($('#source').textContent).toBe("ReferBoard doesn't read LinkedIn pages. Copy the job description there and paste it below.");
  expect($('#description').value).toBe('');
  expect($('#title').value).toBe('');
});

test('too-short descriptions and missing fields are caught before calling the API', async () => {
  const client = fakeClient(SESSION);
  await mount({ mode: 'none', job: { title: '', description: null }, url: 'https://careers.example.com/1' }, client);
  $('#description').value = 'Java developer';
  await click(byText('Check my resume'));
  expect($('#result').textContent).toContain('at least 200 characters');
  await click(byText('Save to tracker'));
  expect($('#result').textContent).toContain('Add the job title and company');
  expect(client.calls).toHaveLength(0);
});

test('saving sends the job and the page link to the tracker', async () => {
  const client = fakeClient(SESSION, async () => ({ id: 'card1' }));
  const { openTab } = await mount({ mode: 'jsonld', url: 'https://jobs.lever.co/n/1',
    job: { title: 'SDE II', company: 'Kestrel', location: 'Remote', description: JD } }, client);
  await click(byText('Save to tracker'));
  expect(client.calls[0]).toEqual({ method: 'POST', path: '/api/applications', body: { jobTitle: 'SDE II', companyName: 'Kestrel',
    location: 'Remote', applyUrl: 'https://jobs.lever.co/n/1', notes: 'Saved from jobs.lever.co with the ReferBoard extension.' } });
  expect(byText('Saved').disabled).toBe(true);
  await click(byText('Open the tracker'));
  expect(openTab).toHaveBeenCalledWith('http://localhost:3000/tracker?card=card1');
});

test('server errors are shown as written; an ended session goes back to Connect', async () => {
  const limited = fakeClient(SESSION, async () => { throw new ApiError(429, { message: 'You have used this month’s 5 analyses.' }); });
  await mount({ mode: 'jsonld', job: { title: 'X', company: 'Y', description: JD } }, limited);
  await click(byText('Check my resume'));
  expect($('#result [role=alert]').textContent).toBe('You have used this month’s 5 analyses.');

  const ended = fakeClient(SESSION, async () => { throw new SessionEndedError(); });
  await mount({ mode: 'jsonld', job: { title: 'X', company: 'Y', description: JD } }, ended);
  await click(byText('Check my resume'));
  expect($('h1').textContent).toBe('Connect to ReferBoard');
  expect($('[role=alert]').textContent).toContain('session ended');
});

test('readActiveTab never injects into blocked or browser pages', async () => {
  const scripting = { executeScript: vi.fn(async () => [{ result: { description: 'x', source: 'jsonld' } }]) };
  const tabs = (url) => ({ query: async () => [{ id: 1, url }], get: async () => ({ id: 1, url }) });
  expect((await readActiveTab(tabs('https://www.naukri.com/job'), scripting)).mode).toBe('blocked');
  expect((await readActiveTab(tabs('chrome://newtab'), scripting)).mode).toBe('unreadable');
  expect(scripting.executeScript).not.toHaveBeenCalled();
  expect((await readActiveTab(tabs('https://boards.greenhouse.io/a/1'), scripting)).mode).toBe('jsonld');
  expect(scripting.executeScript).toHaveBeenCalledOnce();
});
