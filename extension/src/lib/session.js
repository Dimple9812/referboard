/**
 * The extension's ReferBoard session, kept in chrome.storage.local (never in a page). The refresh token rotates
 * on every use; all refreshes share one in-flight call so two requests can't present the same token twice.
 */
const KEY = 'referboard.session';

export class SessionEndedError extends Error {
  constructor() {
    super('Your ReferBoard session ended. Connect again.');
    this.name = 'SessionEndedError';
  }
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `ReferBoard answered ${status}.`);
    this.name = 'ApiError';
    this.status = status;
    this.code = body?.code;
  }
}

/**
 * @param {{ get(key: string): Promise<any>, set(key: string, value: any): Promise<void>, remove(key: string): Promise<void> }} store
 * @param {typeof fetch} fetchFn
 * @param {() => number} now
 */
export function createClient(store, fetchFn = fetch, now = Date.now) {
  let refreshing = null;

  const load = () => store.get(KEY);
  const save = (s) => store.set(KEY, s);

  async function refresh(session) {
    if (!refreshing) {
      refreshing = (async () => {
        const r = await fetchFn(`${session.apiBase}/api/auth/extension/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: session.refreshToken }),
        });
        if (r.status === 401 || r.status === 403) {
          await store.remove(KEY);
          throw new SessionEndedError();
        }
        if (!r.ok) throw new ApiError(r.status, await r.json().catch(() => null));
        const t = await r.json();
        const next = {
          ...session, accessToken: t.accessToken, refreshToken: t.refreshToken,
          accessExpiresAt: now() + t.expiresIn * 1000, user: t.user ?? session.user,
        };
        await save(next);
        return next;
      })().finally(() => {
        refreshing = null;
      });
    }
    return refreshing;
  }

  /** A session with an access token that's good for at least another 30 seconds. */
  async function fresh() {
    const s = await load();
    if (!s) throw new SessionEndedError();
    return s.accessExpiresAt - now() > 30_000 ? s : refresh(s);
  }

  /** JSON request to the API; one retry after a 401 with a refreshed token. */
  async function request(method, path, body) {
    let s = await fresh();
    for (let attempt = 0; attempt < 2; attempt++) {
      const r = await fetchFn(`${s.apiBase}${path}`, {
        method,
        headers: { Authorization: `Bearer ${s.accessToken}`, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        body: body !== undefined ? JSON.stringify(body) : undefined,
      });
      if (r.status === 401 && attempt === 0) {
        s = await refresh(s);
        continue;
      }
      const data = r.status === 204 ? null : await r.json().catch(() => null);
      if (!r.ok) throw new ApiError(r.status, data);
      return data;
    }
    throw new SessionEndedError();
  }

  /** Stores what the web app handed over. apiBase comes from the sender's origin, not the message. */
  async function connect(origin, payload) {
    const valid = payload && payload.type === 'referboard/connect' && typeof payload.accessToken === 'string'
      && typeof payload.refreshToken === 'string' && Number.isFinite(payload.expiresIn) && payload.user?.email;
    if (!valid) return false;
    await save({
      apiBase: origin, appBase: origin, accessToken: payload.accessToken, refreshToken: payload.refreshToken,
      accessExpiresAt: now() + payload.expiresIn * 1000,
      user: { id: payload.user.id, email: payload.user.email, fullName: payload.user.fullName },
    });
    return true;
  }

  /** Revokes the session on the server (best effort) and forgets it here. */
  async function disconnect() {
    const s = await load();
    await store.remove(KEY);
    if (s) {
      await fetchFn(`${s.apiBase}/api/auth/extension/disconnect`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: s.refreshToken }),
      }).catch(() => undefined);
    }
  }

  return { load, request, connect, disconnect, refresh };
}

/** chrome.storage.local as the small async store createClient expects. */
export const chromeStore = {
  get: async (k) => (await chrome.storage.local.get(k))[k],
  set: (k, v) => chrome.storage.local.set({ [k]: v }),
  remove: (k) => chrome.storage.local.remove(k),
};
