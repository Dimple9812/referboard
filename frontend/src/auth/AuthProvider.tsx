import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { authApi, session } from '@/api/client';
import type { AuthResponse, User } from '@/api/types';

type Status = 'loading' | 'authenticated' | 'anonymous';

interface AuthState {
  status: Status;
  user: User | null;
  login: (email: string, password: string) => Promise<void>;
  signup: (fullName: string, email: string, password: string) => Promise<void>;
  loginWithGoogle: (idToken: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>('loading');
  const [user, setUser] = useState<User | null>(null);
  const qc = useQueryClient();

  const accept = useCallback((r: AuthResponse) => {
    session.set(r.accessToken);
    setUser(r.user);
    setStatus('authenticated');
  }, []);

  const clear = useCallback(() => {
    session.set(null);
    setUser(null);
    setStatus('anonymous');
    qc.clear();
  }, [qc]);

  // On load: the refresh cookie (if any) restores the session.
  useEffect(() => {
    let cancelled = false;
    authApi.refresh().then((r) => !cancelled && accept(r)).catch(() => !cancelled && setStatus('anonymous'));
    return () => {
      cancelled = true;
    };
  }, [accept]);

  useEffect(() => session.onExpired(() => {
    clear();
    toast.error('Your session ended. Sign in again to continue.');
  }), [clear]);

  const value = useMemo<AuthState>(() => ({
    status,
    user,
    login: async (email, password) => accept(await authApi.login({ email, password })),
    signup: async (fullName, email, password) => accept(await authApi.signup({ fullName, email, password })),
    loginWithGoogle: async (idToken) => accept(await authApi.google(idToken)),
    logout: async () => {
      await authApi.logout().catch(() => undefined);
      clear();
    },
  }), [status, user, accept, clear]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
