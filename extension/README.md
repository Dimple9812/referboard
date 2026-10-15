# ReferBoard browser extension

Chrome (Manifest V3), plain JavaScript modules, no build step. On a company careers page, click the ReferBoard icon
to save the job to your tracker, check your resume against it, and write a cover letter.

## What it reads, and when

- **Only when you click the icon.** It asks for `activeTab` (the tab you clicked on, for that moment), `scripting`
  and `storage`. No host permissions, no background reading, no access to other tabs.
- **Only machine-readable job data or your selection.** On click it reads the page's schema.org `JobPosting`
  data (the JSON-LD that careers sites publish for search engines), or text you selected. It never takes the
  rest of the page.
- **Never on job boards.** On LinkedIn, Naukri, Indeed, Glassdoor and other job boards (`src/lib/sites.js`) it
  doesn't run anything on the page. You can paste a description you copied instead.
- **It never sends messages or connection requests**, on LinkedIn or anywhere else.

## Load it (development)

1. `chrome://extensions` → turn on **Developer mode** → **Load unpacked** → choose this `extension/` folder.
2. The key in `manifest.json` gives this build a fixed ID: `gjbninchlnhhincbfmmnjjjlhcgooejo`. The gateway allows
   that origin (`EXTENSION_ORIGINS`), and the web app only hands sessions to it (`VITE_EXTENSION_IDS`).
3. Click the icon → **Connect**. ReferBoard opens (http://localhost:3000, or :5173 for `npm run dev`); approve
   there. Disconnect from the popup or from **Settings → Browser extension**.

## How sign-in works

The signed-in web app calls `POST /api/auth/extension/connect` after you click **Connect**. It then passes the tokens
to the extension with `chrome.runtime.sendMessage`. Chrome only delivers those messages from the origins in
`externally_connectable`, and the extension stores the API address as the sender's origin.

The extension keeps an access token and a rotating refresh token in `chrome.storage.local`. It refreshes through
`/api/auth/extension/refresh`, one refresh at a time. Extension sessions are their own token families: they're
listed and revocable in Settings, they can't be used as a web session cookie, and a web cookie can't be used on the
extension endpoints.

## Tests

```bash
npm ci && npm test            # Vitest + jsdom: site rules, page reading, session/refresh, popup behaviour
# In ../frontend, against a running stack with the web app on :3000:
PLAYWRIGHT_CHROMIUM_PATH=/path/to/chromium npx playwright test --project=extension
```

The Playwright test loads the extension into Chromium. Because tests can't click the toolbar icon, it uses a test
copy with host permissions. It connects through the web app, reads a JobPosting page, checks the resume, writes a
letter and saves the job. It also proves a LinkedIn page is never read.

## Publishing

`npm run package` writes `dist/referboard-extension.zip` (without the development key) for the Chrome Web Store.
Then:

- add the store's extension ID to `EXTENSION_ORIGINS` (gateway) and `EXTENSION_IDS` (web app build);
- add your production web origin to `externally_connectable` and `APP_ORIGINS` in `src/popup.js`.
