import { extractJobFromPage } from './lib/extract.js';
import { blockedSite, readablePage } from './lib/sites.js';
import { ApiError, SessionEndedError, chromeStore, createClient } from './lib/session.js';

/** The web app addresses this build may connect to; they match the manifest's externally_connectable list. */
export const APP_ORIGINS = ['http://localhost:3000', 'http://localhost:5173'];
const MIN_JD = 200;

const VERDICT = { STRONG: 'Strong match', GOOD: 'Good match', PARTIAL: 'Partial match', WEAK: 'Weak match' };

/** Tiny element builder: text always goes in as text, never as HTML. */
export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'className') el.className = v;
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

/**
 * What the popup will work with: the job read from the active tab (only on these terms), or nothing and why.
 * mode: 'jsonld' | 'selection' | 'none' | 'blocked' | 'unreadable' | 'failed'
 */
export async function readActiveTab(tabsApi, scriptingApi, tabIdOverride) {
  const [tab] = tabIdOverride ? [await tabsApi.get(tabIdOverride)] : await tabsApi.query({ active: true, currentWindow: true });
  if (!tab?.url || !readablePage(tab.url)) return { mode: 'unreadable', job: null, url: tab?.url ?? null };
  const site = blockedSite(tab.url);
  if (site) return { mode: 'blocked', site, job: null, url: tab.url };
  try {
    const [res] = await scriptingApi.executeScript({ target: { tabId: tab.id }, func: extractJobFromPage });
    const job = res?.result;
    if (!job) return { mode: 'failed', job: null, url: tab.url };
    return { mode: job.description ? job.source : 'none', job, url: tab.url };
  } catch {
    return { mode: 'failed', job: null, url: tab.url };
  }
}

const NOTES = {
  jsonld: ['ok', 'Read from the job details this page publishes for search engines. Check them before you continue.'],
  selection: ['ok', 'Using the text you selected on the page.'],
  none: ['info', "This page doesn't publish job details. Select the job description on the page and click the icon again, or paste it below."],
  unreadable: ['info', 'Open a job page and click the icon again, or paste a job description below.'],
  failed: ['info', "Couldn't read this page. Paste the job description below."],
};

export function renderPopup(root, { client, target, openTab, appOrigins = APP_ORIGINS }) {
  const footer = document.querySelector('footer');
  footer?.remove();

  const show = (...nodes) => root.replaceChildren(...nodes);

  async function start() {
    const session = await client.load();
    if (!session) return showConnect();
    showJob(session);
    document.body.append(h('footer', {},
      h('span', { title: session.user.email }, `Signed in as ${session.user.email}`),
      h('button', { className: 'link', onClick: async () => { await client.disconnect(); document.querySelector('footer')?.remove(); showConnect(); } }, 'Disconnect')));
  }

  function showConnect(message) {
    let origin = appOrigins[0];
    show(
      h('h1', {}, 'Connect to ReferBoard'),
      message && h('div', { className: 'note warn', role: 'alert' }, h('span', { className: 'dot' }), message),
      h('p', { className: 'muted' }, 'Approve the extension on ReferBoard once. After that, click this icon on a job page to save the job or check your resume against it.'),
      h('p', { className: 'muted small' }, 'It reads a page only when you click the icon, and never reads LinkedIn, Naukri or Indeed.'),
      appOrigins.length > 1 && h('label', { for: 'origin' }, 'ReferBoard address'),
      appOrigins.length > 1 && h('select', { id: 'origin', onChange: (e) => { origin = e.target.value; } },
        appOrigins.map((o, i) => h('option', { value: o, selected: i === 0 }, o + (o.endsWith(':5173') ? ' (npm run dev)' : '')))),
      h('div', { className: 'actions' },
        h('button', { onClick: () => openTab(`${origin}/connect-extension?ext=${encodeURIComponent(target.extensionId)}`) }, 'Connect')),
    );
  }

  function showJob(session) {
    const t = target.read;
    const job = t.job ?? {};
    const [tone, text] = t.mode === 'blocked'
      ? ['warn', `ReferBoard doesn't read ${t.site} pages. Copy the job description there and paste it below.`]
      : NOTES[t.mode] ?? NOTES.failed;

    const title = h('input', { id: 'title', value: job.title ?? '', maxlength: 300, autocomplete: 'off' });
    const company = h('input', { id: 'company', value: job.company ?? '', maxlength: 200, autocomplete: 'off' });
    const where = h('input', { id: 'location', value: job.location ?? '', maxlength: 300, autocomplete: 'off', placeholder: 'Optional' });
    const desc = h('textarea', { id: 'description', rows: t.mode === 'jsonld' || t.mode === 'selection' ? 5 : 7, maxlength: 30000,
      placeholder: 'Paste the job description' });
    desc.value = job.description ?? '';
    const count = h('p', { className: 'hint', id: 'desc-hint' });
    const result = h('div', { id: 'result' });
    const analyse = h('button', { type: 'button' }, 'Check my resume');
    const saveBtn = h('button', { type: 'button', className: 'secondary' }, 'Save to tracker');
    const updateCount = () => {
      const n = desc.value.trim().length;
      count.textContent = n === 0 ? 'Needed to check your resume; optional for saving.'
        : n < MIN_JD ? `${n} characters. Checking your resume needs at least ${MIN_JD}.` : `${n.toLocaleString('en-IN')} characters.`;
    };
    desc.addEventListener('input', updateCount);
    updateCount();

    const busy = (btn, on, label) => {
      btn.disabled = on;
      if (label) btn.textContent = label;
    };
    const fail = (e) => {
      if (e instanceof SessionEndedError) {
        document.querySelector('footer')?.remove();
        return showConnect('Your ReferBoard session ended. Connect again.');
      }
      const msg = e instanceof ApiError ? e.message : "Couldn't reach ReferBoard. Check that it's running and try again.";
      result.replaceChildren(h('div', { className: 'result error', role: 'alert' }, msg));
    };
    const app = (path) => openTab(`${session.appBase}${path}`);

    analyse.addEventListener('click', async () => {
      if (desc.value.trim().length < MIN_JD) {
        desc.focus();
        result.replaceChildren(h('div', { className: 'result error', role: 'alert' },
          'Add the full job description first: it needs at least 200 characters.'));
        return;
      }
      busy(analyse, true, 'Checking…');
      result.replaceChildren(h('p', { className: 'muted small', role: 'status' }, 'Comparing it with your resume. This can take up to a minute.'));
      try {
        const r = await client.request('POST', '/api/analyses', {
          jdText: desc.value.trim(), jobTitle: title.value.trim() || undefined, companyName: company.value.trim() || undefined,
        });
        const missing = (r.missingSkills?.required ?? []).slice(0, 5);
        const letterBtn = h('button', { type: 'button', className: 'secondary' }, 'Write a cover letter');
        letterBtn.addEventListener('click', async () => {
          busy(letterBtn, true, 'Writing…');
          try {
            const l = await client.request('POST', `/api/analyses/${r.id}/cover-letters`, { length: 'STANDARD' });
            app(`/cover-letters/${l.id}`);
          } catch (e) {
            busy(letterBtn, false, 'Write a cover letter');
            fail(e);
          }
        });
        result.replaceChildren(h('div', { className: 'result', id: 'analysis' },
          h('div', { className: 'score' }, h('b', {}, String(r.score)), h('span', {}, 'Resume match', h('br'), h('span', { className: 'muted' }, VERDICT[r.verdict] ?? ''))),
          missing.length > 0 && h('p', { className: 'small', style: 'margin:10px 0 0' }, 'Asked for, not on your resume:'),
          missing.length > 0 && h('div', { className: 'chips' }, missing.map((s) => h('span', { className: 'chip' }, s.label))),
          r.reused && h('p', { className: 'muted small' }, 'Same as your last check of this description, so it wasn’t counted again.'),
          h('div', { className: 'actions' }, letterBtn),
          h('div', { className: 'links' }, h('button', { className: 'link', onClick: () => app(`/analyzer/${r.id}`) }, 'Open the full report and edits'))));
      } catch (e) {
        fail(e);
      } finally {
        busy(analyse, false, 'Check my resume');
      }
    });

    saveBtn.addEventListener('click', async () => {
      if (!title.value.trim() || !company.value.trim()) {
        (title.value.trim() ? company : title).focus();
        result.replaceChildren(h('div', { className: 'result error', role: 'alert' }, 'Add the job title and company to save it.'));
        return;
      }
      busy(saveBtn, true, 'Saving…');
      try {
        const card = await client.request('POST', '/api/applications', {
          jobTitle: title.value.trim(), companyName: company.value.trim(), location: where.value.trim() || undefined,
          applyUrl: t.url && /^https?:/.test(t.url) ? t.url.slice(0, 2000) : undefined,
          notes: t.url && t.mode !== 'unreadable' ? `Saved from ${new URL(t.url).hostname} with the ReferBoard extension.` : undefined,
        });
        busy(saveBtn, true, 'Saved');
        result.replaceChildren(h('div', { className: 'result', id: 'saved' },
          h('p', { style: 'margin:0' }, 'Saved to your tracker.'),
          h('div', { className: 'links' }, h('button', { className: 'link', onClick: () => app(`/tracker?card=${card.id}`) }, 'Open the tracker'))));
      } catch (e) {
        busy(saveBtn, false, 'Save to tracker');
        fail(e);
      }
    });

    show(
      h('div', { className: `note ${tone}`, id: 'source' }, h('span', { className: 'dot' }), text),
      h('label', { for: 'title' }, 'Job title'), title,
      h('div', { className: 'row' },
        h('div', {}, h('label', { for: 'company' }, 'Company'), company),
        h('div', {}, h('label', { for: 'location' }, 'Location'), where)),
      h('label', { for: 'description' }, 'Job description'), desc, count,
      h('div', { className: 'actions' }, analyse, saveBtn),
      result,
    );
  }

  return start();
}

// ---------------------------------------------------------------------------------------------------------------
// Running as the real popup (or opened as a tab for testing: popup.html?tab=<id>).

if (typeof chrome !== 'undefined' && chrome.runtime?.id && document.getElementById('app')) {
  const params = new URLSearchParams(location.search);
  const tabId = params.has('tab') ? Number(params.get('tab')) : undefined;
  if (tabId !== undefined || params.has('page')) document.body.classList.add('page');
  readActiveTab(chrome.tabs, chrome.scripting, tabId).then((read) =>
    renderPopup(document.getElementById('app'), {
      client: createClient(chromeStore),
      target: { read, extensionId: chrome.runtime.id },
      openTab: (url) => {
        chrome.tabs.create({ url });
        if (tabId === undefined && !params.has('page')) window.close();
      },
    }));
}
