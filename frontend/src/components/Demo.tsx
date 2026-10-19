import { useState, type MouseEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthProvider';
import { errorMessage } from '@/api/client';
import { Button } from '@/components/ui/Button';

/** One-click sign-in to the shared demo account. Renders nothing when this ReferBoard has no demo. */
export function TryDemoButton({ size = 'lg', variant = 'outline', className }: {
  size?: 'sm' | 'md' | 'lg';
  variant?: 'outline' | 'ghost' | 'primary';
  className?: string;
}) {
  const { demoAvailable, loginDemo } = useAuth();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!demoAvailable) return null;

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      await loginDemo();
      navigate('/jobs', { replace: true });
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <span className="inline-flex flex-col gap-1">
      <Button type="button" size={size} variant={variant} className={className} loading={busy} onClick={start}>
        Try the demo
      </Button>
      {error && <span role="alert" className="text-sm text-danger">{error}</span>}
    </span>
  );
}

/** Shown inside the app while signed in as the shared demo account. */
export function DemoBanner() {
  const { isDemo, logout } = useAuth();
  const navigate = useNavigate();
  if (!isDemo) return null;
  // Sign out of the shared account first, so the sign-up page doesn't bounce a signed-in visitor back into the app.
  const ownAccount = async (e: MouseEvent) => {
    e.preventDefault();
    await logout();
    navigate('/signup');
  };
  return (
    <div role="note" className="mb-6 rounded-lg border border-line-strong bg-surface px-4 py-3 text-sm text-ink">
      You're exploring a shared demo account filled with fictional sample data. Anyone trying the demo sees it too,
      so don't add anything personal.{' '}
      <Link to="/signup" onClick={ownAccount} className="font-medium underline decoration-line-strong underline-offset-4 hover:decoration-ink">
        Create your own account
      </Link>{' '}
      to save your own jobs and referrals.
    </div>
  );
}
