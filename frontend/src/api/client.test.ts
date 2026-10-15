import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios';
import { api, bare, errorMessage, session } from './client';

/** Fake transport: each test decides what the "server" answers. */
function respond(config: InternalAxiosRequestConfig, status: number, data: unknown) {
  const response = { status, statusText: String(status), data, headers: {}, config };
  if (status >= 400) throw new AxiosError('fail', String(status), config, null, response);
  return Promise.resolve(response);
}

let refreshCalls = 0;
let validToken = 'fresh';
let refreshFails = false;

const server: AxiosAdapter = async (config) => {
  if (config.url === '/api/auth/refresh') {
    refreshCalls++;
    await new Promise((r) => setTimeout(r, 10));
    return refreshFails ? respond(config, 401, { message: 'Session expired' }) : respond(config, 200, { accessToken: validToken });
  }
  const auth = AxiosHeaders.from(config.headers).get('Authorization');
  return auth === `Bearer ${validToken}` ? respond(config, 200, { ok: true, url: config.url }) : respond(config, 401, { message: 'Unauthorized' });
};

beforeEach(() => {
  api.defaults.adapter = server;
  bare.defaults.adapter = server;
  refreshCalls = 0;
  validToken = 'fresh';
  refreshFails = false;
  session.set('stale');
});

test('a 401 triggers one refresh, then the request is retried with the new token', async () => {
  const r = await api.get('/api/jobs');
  expect(r.data).toEqual({ ok: true, url: '/api/jobs' });
  expect(refreshCalls).toBe(1);
  expect(session.token()).toBe('fresh');
});

test('many requests failing together share a single refresh', async () => {
  const results = await Promise.all(['/a', '/b', '/c', '/d', '/e'].map((u) => api.get(u)));
  expect(results.every((r) => r.data.ok)).toBe(true);
  expect(refreshCalls).toBe(1);
});

test('a failed refresh clears the token and tells the app the session ended', async () => {
  refreshFails = true;
  const expired = vi.fn();
  const off = session.onExpired(expired);
  await expect(api.get('/api/profile')).rejects.toMatchObject({ response: { status: 401 } });
  expect(expired).toHaveBeenCalledOnce();
  expect(session.token()).toBeNull();
  off();
});

test('a retried request that still gets 401 is not retried again', async () => {
  validToken = 'never-matches';
  const adapter: AxiosAdapter = async (config) => {
    if (config.url === '/api/auth/refresh') { refreshCalls++; return respond(config, 200, { accessToken: 'other' }); }
    return respond(config, 401, {});
  };
  api.defaults.adapter = adapter;
  bare.defaults.adapter = adapter;
  await expect(api.get('/api/x')).rejects.toBeInstanceOf(AxiosError);
  expect(refreshCalls).toBe(1);
});

test('auth endpoints never trigger a refresh', async () => {
  await expect(api.post('/api/auth/login', {})).rejects.toBeInstanceOf(AxiosError);
  expect(refreshCalls).toBe(0);
});

test('errorMessage prefers field violations, then the server message', () => {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const err = (data: unknown) => new AxiosError('x', '400', config, null, { status: 400, statusText: '', headers: {}, config, data });
  expect(errorMessage(err({ violations: [{ field: 'email', message: 'must be valid' }] }))).toBe('email: must be valid');
  expect(errorMessage(err({ message: 'Daily limit reached' }))).toBe('Daily limit reached');
  expect(errorMessage(new AxiosError('net', 'ERR_NETWORK', config))).toMatch(/Can't reach ReferBoard/);
  expect(errorMessage(new Error('boom'))).toBe('Something went wrong. Please try again.');
});

test('the start-up session check and a 401 retry at the same moment share one refresh', async () => {
  const { authApi } = await import('./client');
  const [restored, retried] = await Promise.all([authApi.refresh(), authApi.refresh(), api.get('/api/jobs')]);
  expect(restored.accessToken).toBe('fresh');
  expect(retried.accessToken).toBe('fresh');
  expect(refreshCalls).toBe(1);
});
