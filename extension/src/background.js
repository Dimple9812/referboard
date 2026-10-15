import { chromeStore, createClient } from './lib/session.js';

/**
 * The only thing running in the background: receiving a session from the ReferBoard web app when the user clicks
 * Connect there. Chrome only delivers messages from origins listed in the manifest's externally_connectable.
 */
const client = createClient(chromeStore);

chrome.runtime.onMessageExternal.addListener((message, sender, sendResponse) => {
  const origin = sender.origin || (sender.url ? new URL(sender.url).origin : null);
  if (!origin || message?.type !== 'referboard/connect') {
    sendResponse({ ok: false });
    return false;
  }
  client.connect(origin, message).then((ok) => sendResponse({ ok }), () => sendResponse({ ok: false }));
  return true;   // answer asynchronously
});
