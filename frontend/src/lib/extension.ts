/**
 * Talking to the ReferBoard browser extension. The web app only ever hands a session to extension IDs it knows
 * (VITE_EXTENSION_IDS, default: the development build), so a link with someone else's extension ID can't collect one.
 */
export const DEV_EXTENSION_ID = 'gjbninchlnhhincbfmmnjjjlhcgooejo';

export const allowedExtensionIds = (): string[] =>
  (import.meta.env.VITE_EXTENSION_IDS || DEV_EXTENSION_ID).split(',').map((s: string) => s.trim()).filter(Boolean);

export const isAllowedExtension = (id: string | null | undefined): id is string =>
  !!id && /^[a-p]{32}$/.test(id) && allowedExtensionIds().includes(id);

interface ChromeRuntime {
  sendMessage(extensionId: string, message: unknown, callback: (response: unknown) => void): void;
  lastError?: { message?: string };
}

const runtime = (): ChromeRuntime | undefined =>
  (globalThis as unknown as { chrome?: { runtime?: ChromeRuntime } }).chrome?.runtime;

export interface ConnectPayload {
  type: 'referboard/connect';
  apiBase: string;
  appBase: string;
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
  user: { id: string; email: string; fullName: string };
}

/** Resolves when the extension confirms it stored the session; rejects if it isn't installed or doesn't answer. */
export function sendToExtension(extensionId: string, payload: ConnectPayload, timeoutMs = 5000): Promise<void> {
  return new Promise((resolve, reject) => {
    const rt = runtime();
    if (!rt?.sendMessage) {
      reject(new Error('NO_EXTENSION'));
      return;
    }
    const timer = setTimeout(() => reject(new Error('NO_ANSWER')), timeoutMs);
    try {
      rt.sendMessage(extensionId, payload, (response) => {
        clearTimeout(timer);
        const ok = (response as { ok?: boolean } | undefined)?.ok;
        if (rt.lastError || !ok) reject(new Error(rt.lastError?.message || 'REFUSED'));
        else resolve();
      });
    } catch (e) {
      clearTimeout(timer);
      reject(e instanceof Error ? e : new Error(String(e)));
    }
  });
}
