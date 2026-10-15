/**
 * Job boards whose terms prohibit automated collection, or that we treat that way to be safe. The extension never
 * runs a script on these pages; the user can paste the description they copied instead. Company career pages and
 * the applicant-tracking systems that host them (Greenhouse, Lever, Workday, Ashby…) are read, on click only.
 */
export const BLOCKED_SITES = [
  { pattern: /(^|\.)linkedin\.com$/, name: 'LinkedIn' },
  { pattern: /(^|\.)naukri\.com$/, name: 'Naukri' },
  { pattern: /(^|\.)indeed\.[a-z.]+$/, name: 'Indeed' },
  { pattern: /(^|\.)glassdoor\.[a-z.]+$/, name: 'Glassdoor' },
  { pattern: /(^|\.)(foundit\.in|monsterindia\.com|monster\.com)$/, name: 'foundit' },
  { pattern: /(^|\.)shine\.com$/, name: 'Shine' },
  { pattern: /(^|\.)timesjobs\.com$/, name: 'TimesJobs' },
  { pattern: /(^|\.)instahyre\.com$/, name: 'Instahyre' },
  { pattern: /(^|\.)iimjobs\.com$/, name: 'iimjobs' },
  { pattern: /(^|\.)hirist\.(tech|com)$/, name: 'hirist' },
  { pattern: /(^|\.)cutshort\.io$/, name: 'Cutshort' },
  { pattern: /(^|\.)(wellfound\.com|angel\.co)$/, name: 'Wellfound' },
  { pattern: /(^|\.)apna\.co$/, name: 'apna' },
];

/** The blocked site's name for a URL, or null when the extension may read the page. */
export function blockedSite(url) {
  let host;
  try {
    host = new URL(url).hostname.toLowerCase();
  } catch {
    return null;
  }
  return BLOCKED_SITES.find((s) => s.pattern.test(host))?.name ?? null;
}

/** Only ordinary web pages can be read (not chrome://, the Web Store, PDFs opened from disk…). */
export function readablePage(url) {
  try {
    const u = new URL(url);
    return (u.protocol === 'https:' || u.protocol === 'http:') && u.hostname !== 'chromewebstore.google.com'
      && u.hostname !== 'chrome.google.com';
  } catch {
    return false;
  }
}
