import { ApiError, SessionEndedError, createClient } from '../src/lib/session.js';

function memoryStore(initial) {
  const m = new Map(initial ? [['referboard.session', initial]] : []);
  return { get: async (k) => m.get(k), set: async (k, v) => void m.set(k, v), remove: async (k) => void m.delete(k), m };
}

const json = (status, body) => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function server({ validToken = 'a2', refreshOk = true } = {}) {
  const calls = [];
  const fetchFn = async (url, init = {}) => {
    calls.push({ url, init });
    await new Promise((r) => setTimeout(r, 5));
    if (url.endsWith('/api/auth/extension/refresh')) {
      return refreshOk ? json(200, { accessToken: validToken, refreshToken: 'r2', expiresIn: 900, user: { email: 'a@x.com' } })
        : json(401, { code: 'INVALID_REFRESH_TOKEN' });
    }
    if (url.endsWith('/api/auth/extension/disconnect')) return json(204);
    if (init.headers?.Authorization !== `Bearer ${validToken}`) return json(401, { message: 'Unauthorized' });
    if (url.endsWith('/api/limited')) return json(429, { code: 'ANALYSIS_LIMIT', message: 'You have used this month’s 5 analyses.' });
    return json(200, { ok: true, path: new URL(url).pathname });
  };
  return { fetchFn, calls };
}

const session = (over = {}) => ({ apiBase: 'http://localhost:3000', appBase: 'http://localhost:3000', accessToken: 'a1', refreshToken: 'r1',
  accessExpiresAt: 0, user: { email: 'a@x.com' }, ...over });

test('connect stores the session under the sender origin, ignoring anything claimed in the message', async () => {
  const store = memoryStore();
  const c = createClient(store, server().fetchFn, () => 1000);
  expect(await c.connect('http://localhost:3000', { type: 'referboard/connect', apiBase: 'https://evil.example', accessToken: 'a',
    refreshToken: 'r', expiresIn: 900, user: { id: 'u', email: 'a@x.com', fullName: 'Asha' } })).toBe(true);
  expect(store.m.get('referboard.session')).toMatchObject({ apiBase: 'http://localhost:3000', accessExpiresAt: 901000 });
  expect(await c.connect('http://localhost:3000', { type: 'other' })).toBe(false);
});

test('an expired access token is refreshed once, even for parallel requests', async () => {
  const store = memoryStore(session());
  const { fetchFn, calls } = server();
  const c = createClient(store, fetchFn, () => 1000);
  const results = await Promise.all([c.request('GET', '/api/a'), c.request('GET', '/api/b'), c.request('GET', '/api/c')]);
  expect(results.map((r) => r.path)).toEqual(['/api/a', '/api/b', '/api/c']);
  expect(calls.filter((x) => x.url.endsWith('/refresh'))).toHaveLength(1);
  expect(JSON.parse(calls.find((x) => x.url.endsWith('/refresh')).init.body)).toEqual({ refreshToken: 'r1' });
  expect(store.m.get('referboard.session')).toMatchObject({ accessToken: 'a2', refreshToken: 'r2' });
});

test('a 401 with a fresh-looking token triggers one refresh and a retry', async () => {
  const store = memoryStore(session({ accessExpiresAt: 10_000_000 }));
  const { fetchFn, calls } = server();
  const c = createClient(store, fetchFn, () => 1000);
  expect((await c.request('POST', '/api/applications', { jobTitle: 'x' })).ok).toBe(true);
  expect(calls.map((x) => new URL(x.url).pathname)).toEqual(['/api/applications', '/api/auth/extension/refresh', '/api/applications']);
});

test('a refused refresh ends the session and forgets it', async () => {
  const store = memoryStore(session());
  const c = createClient(store, server({ refreshOk: false }).fetchFn, () => 1000);
  await expect(c.request('GET', '/api/a')).rejects.toBeInstanceOf(SessionEndedError);
  expect(store.m.has('referboard.session')).toBe(false);
});

test('API errors carry the server message and code', async () => {
  const store = memoryStore(session({ accessToken: 'a2', accessExpiresAt: 10_000_000 }));
  const c = createClient(store, server().fetchFn, () => 1000);
  const err = await c.request('POST', '/api/limited', {}).catch((e) => e);
  expect(err).toBeInstanceOf(ApiError);
  expect(err).toMatchObject({ status: 429, code: 'ANALYSIS_LIMIT', message: 'You have used this month’s 5 analyses.' });
});

test('disconnect forgets the session and revokes it on the server', async () => {
  const store = memoryStore(session());
  const { fetchFn, calls } = server();
  const c = createClient(store, fetchFn, () => 1000);
  await c.disconnect();
  expect(store.m.has('referboard.session')).toBe(false);
  expect(calls[0].url).toBe('http://localhost:3000/api/auth/extension/disconnect');
});
