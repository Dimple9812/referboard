import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './AuthProvider';
import { FullPageSpinner } from '@/components/ui/Spinner';

/** Protected routes: wait for the session check, then either render or send to /login (and back afterwards). */
export function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <FullPageSpinner />;
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <Outlet />;
}

/** Login/signup/landing: signed-in users go straight to the app. */
export function RedirectIfAuthed({ to = '/jobs' }: { to?: string }) {
  const { status } = useAuth();
  if (status === 'loading') return <FullPageSpinner />;
  if (status === 'authenticated') return <Navigate to={to} replace />;
  return <Outlet />;
}
