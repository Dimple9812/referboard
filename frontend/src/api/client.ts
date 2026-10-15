import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import type { ApiErrorBody, AuthResponse } from './types';

/**
 * The access token lives only in memory (never localStorage: XSS can't lift it from storage). The refresh token
 * is an httpOnly cookie scoped to /api/auth, so a page reload restores the session with one refresh call.
 */
let accessToken: string | null = null;
const listeners = new Set<(reason: 'expired') => void>();

export const session = {
  token: () => accessToken,
  set(token: string | null) {
    accessToken = token;
  },
  /** Called when a refresh fails mid-session: the app signs out and sends the user to /login. */
  onExpired(fn: (reason: 'expired') => void) {
    listeners.add(fn);
    return () => { listeners.delete(fn); };
  },
};

const baseURL = import.meta.env.VITE_API_BASE_URL || '';

export const api = axios.create({ baseURL, withCredentials: true, timeout: 130_000 });
export const bare = axios.create({ baseURL, withCredentials: true, timeout: 15_000 });

const NO_REFRESH = /\/api\/auth\/(login|signup|google|refresh|logout)$/;

api.interceptors.request.use((config) => {
  if (accessToken) config.headers.set('Authorization', `Bearer ${accessToken}`);
  return config;
});

let refreshing: Promise<AuthResponse> | null = null;

/**
 * One refresh at a time. Refresh tokens rotate and a reused one revokes the session, so two concurrent refreshes
 * with the same cookie would sign the user out: ten requests failing with 401 together, or the start-up check
 * running twice (React StrictMode), all share a single call.
 */
function refreshSession(): Promise<AuthResponse> {
  if (!refreshing) {
    refreshing = bare
      .post<AuthResponse>('/api/auth/refresh')
      .then((r) => {
        accessToken = r.data.accessToken;
        return r.data;
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

export const refreshAccessToken = (): Promise<string> => refreshSession().then((r) => r.accessToken);

type Retriable = InternalAxiosRequestConfig & { _retried?: boolean };

api.interceptors.response.use(
  (r) => r,
  async (error: AxiosError) => {
    const config = error.config as Retriable | undefined;
    if (error.response?.status !== 401 || !config || config._retried || NO_REFRESH.test(config.url ?? '')) {
      throw error;
    }
    config._retried = true;
    try {
      const token = await refreshAccessToken();
      config.headers.set('Authorization', `Bearer ${token}`);
      return api.request(config as AxiosRequestConfig);
    } catch {
      accessToken = null;
      listeners.forEach((fn) => fn('expired'));
      throw error;
    }
  },
);

/** The server's message when there is one (every service and the gateway use the same error shape). */
export function errorMessage(err: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (axios.isAxiosError(err)) {
    const body = err.response?.data as Partial<ApiErrorBody> | undefined;
    if (body?.violations?.length) {
      return body.violations.map((v) => `${v.field}: ${v.message}`).join('; ');
    }
    if (body?.message) return body.message;
    if (err.code === 'ECONNABORTED') return 'That took too long. Please try again.';
    if (!err.response) return "Can't reach ReferBoard. Check your connection.";
  }
  return fallback;
}

export function errorCode(err: unknown): string | undefined {
  return axios.isAxiosError(err) ? (err.response?.data as Partial<ApiErrorBody> | undefined)?.code : undefined;
}

export const authApi = {
  signup: (body: { email: string; password: string; fullName: string }) =>
    bare.post<AuthResponse>('/api/auth/signup', body).then((r) => r.data),
  login: (body: { email: string; password: string }) => bare.post<AuthResponse>('/api/auth/login', body).then((r) => r.data),
  google: (idToken: string) => bare.post<AuthResponse>('/api/auth/google', { idToken }).then((r) => r.data),
  refresh: refreshSession,
  logout: () => bare.post('/api/auth/logout').then(() => undefined),
};
