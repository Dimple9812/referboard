/**
 * Runs in the page, injected by chrome.scripting.executeScript when the user clicks the extension, so it must be
 * self-contained (no imports, no closures). It reads only:
 *  - schema.org JobPosting data (JSON-LD), which career sites publish for search engines to read; and
 *  - text the user selected themselves.
 * It does not read the rest of the page.
 */
export function extractJobFromPage() {
  const MAX = 30000;
  const out = {
    url: location.href, title: null, company: null, location: null, description: null, source: 'none',
  };

  const str = (v) => (typeof v === 'string' && v.trim() ? v.trim().replace(/\s+/g, ' ') : null);

  const postings = [];
  const collect = (v, depth) => {
    if (!v || depth > 6) return;
    if (Array.isArray(v)) {
      v.forEach((x) => collect(x, depth + 1));
      return;
    }
    if (typeof v !== 'object') return;
    const types = [].concat(v['@type'] || []);
    if (types.includes('JobPosting')) postings.push(v);
    if (v['@graph']) collect(v['@graph'], depth + 1);
  };
  for (const s of document.querySelectorAll('script[type="application/ld+json"]')) {
    try {
      collect(JSON.parse(s.textContent || ''), 0);
    } catch {
      // Malformed JSON-LD is common; skip it.
    }
  }

  // HTML description → readable text with paragraphs and bullets kept. DOMParser never runs scripts.
  const htmlToText = (html) => {
    let doc = new DOMParser().parseFromString(String(html), 'text/html');
    // Some sites escape the HTML twice ("&lt;p&gt;…").
    if (/<\/?(p|li|ul|br|div|h\d)\b/i.test(doc.body.textContent || '')) {
      doc = new DOMParser().parseFromString(doc.body.textContent || '', 'text/html');
    }
    const BLOCK = /^(P|DIV|UL|OL|H1|H2|H3|H4|H5|H6|SECTION|ARTICLE|TABLE|TR|BLOCKQUOTE)$/;
    let text = '';
    const walk = (node) => {
      for (const child of node.childNodes) {
        if (child.nodeType === 3) {
          text += child.textContent.replace(/\s+/g, ' ');
        } else if (child.nodeType === 1) {
          const tag = child.tagName;
          if (tag === 'SCRIPT' || tag === 'STYLE') continue;
          if (tag === 'BR') {
            text += '\n';
            continue;
          }
          if (tag === 'LI') text += '\n• ';
          else if (BLOCK.test(tag)) text += '\n\n';
          walk(child);
          if (BLOCK.test(tag)) text += '\n\n';
        }
      }
    };
    walk(doc.body);
    return text.replace(/[ \t]+\n/g, '\n').replace(/\n[ \t]+/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  };

  const place = (loc) => {
    const one = Array.isArray(loc) ? loc[0] : loc;
    const a = one && (one.address || one);
    if (!a || typeof a !== 'object') return str(a);
    const country = typeof a.addressCountry === 'object' ? a.addressCountry?.name : a.addressCountry;
    return [a.addressLocality, a.addressRegion, country].map(str).filter(Boolean).join(', ') || null;
  };

  const job = postings[0];
  if (job) {
    out.title = str(job.title) || str(job.name);
    const org = job.hiringOrganization;
    out.company = str(typeof org === 'object' && org ? org.name : org);
    const remote = [].concat(job.jobLocationType || []).some((t) => /TELECOMMUTE|remote/i.test(String(t)));
    const where = place(job.jobLocation);
    out.location = remote ? (where ? `Remote (${where})` : 'Remote') : where;
    if (job.description) {
      out.description = htmlToText(job.description).slice(0, MAX);
      out.source = 'jsonld';
    }
  }

  // What the user selected wins: they chose it.
  const selected = String(window.getSelection ? window.getSelection() : '').trim();
  if (selected.length >= 200) {
    out.description = selected.slice(0, MAX);
    out.source = 'selection';
  }

  if (!out.title) {
    const og = document.querySelector('meta[property="og:title"]');
    out.title = str(og && og.getAttribute('content'));
  }
  return out;
}
