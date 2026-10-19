import { useCallback, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthProvider';
import { GoogleButton } from '@/auth/GoogleButton';
import { errorMessage } from '@/api/client';
import { Button } from '@/components/ui/Button';
import { Field, Input } from '@/components/ui/Field';
import { Logo } from '@/components/Logo';
import { TryDemoButton } from '@/components/Demo';

function AuthShell({ title, children, footer }: { title: string; children: React.ReactNode; footer: React.ReactNode }) {
  return (
    <div className="grid min-h-screen place-items-start bg-paper px-5 py-10 sm:place-items-center">
      <div className="w-full max-w-sm">
        <Link to="/"><Logo /></Link>
        <h1 className="mt-10 text-2xl font-semibold">{title}</h1>
        <div className="mt-6">{children}</div>
        <p className="mt-8 text-sm text-slate">{footer}</p>
      </div>
    </div>
  );
}

/** "Just looking?" with a demo button, only on deployments that offer a demo. */
function DemoHint() {
  const { demoAvailable } = useAuth();
  if (!demoAvailable) return null;
  return (
    <div className="mt-6 flex items-center justify-between gap-3 border-t border-line pt-5 text-sm text-ink-soft">
      <span>Just looking around?</span>
      <TryDemoButton size="sm" />
    </div>
  );
}

function useAfterAuth() {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from;
  return (isNew: boolean) => navigate(isNew ? '/onboarding' : from ?? '/jobs', { replace: true });
}

export function LoginPage() {
  const { login, loginWithGoogle } = useAuth();
  const done = useAfterAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email.trim(), password);
      done(false);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };
  const google = useCallback((token: string) => {
    loginWithGoogle(token).then(() => done(false)).catch((err) => setError(errorMessage(err)));
  }, [loginWithGoogle, done]);

  return (
    <AuthShell title="Sign in" footer={<>New to ReferBoard? <Link className="font-medium text-ink underline decoration-marigold underline-offset-4" to="/signup">Create an account</Link></>}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <Field label="Email">{(p) => <Input {...p} type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />}</Field>
        <Field label="Password">{(p) => <Input {...p} type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
        {error && <p role="alert" className="text-sm text-danger">{error}</p>}
        <Button type="submit" className="w-full" loading={busy}>Sign in</Button>
      </form>
      <div className="mt-6"><GoogleButton onCredential={google} /></div>
      <DemoHint />
    </AuthShell>
  );
}

export function SignupPage() {
  const { signup, loginWithGoogle } = useAuth();
  const done = useAfterAuth();
  const [form, setForm] = useState({ fullName: '', email: '', password: '' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const weak = form.password.length > 0 && !(form.password.length >= 8 && /[A-Za-z]/.test(form.password) && /\d/.test(form.password));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (weak) return;
    setBusy(true);
    setError(null);
    try {
      await signup(form.fullName.trim(), form.email.trim(), form.password);
      done(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };
  const google = useCallback((token: string) => {
    loginWithGoogle(token).then(() => done(true)).catch((err) => setError(errorMessage(err)));
  }, [loginWithGoogle, done]);

  return (
    <AuthShell title="Create your account" footer={<>Already have an account? <Link className="font-medium text-ink underline decoration-marigold underline-offset-4" to="/login">Sign in</Link></>}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <Field label="Full name">{(p) => <Input {...p} autoComplete="name" required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />}</Field>
        <Field label="Email">{(p) => <Input {...p} type="email" autoComplete="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />}</Field>
        <Field label="Password" hint="At least 8 characters, with a letter and a number." error={weak ? 'Use at least 8 characters, including a letter and a number.' : null}>
          {(p) => <Input {...p} type="password" autoComplete="new-password" required value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />}
        </Field>
        {error && <p role="alert" className="text-sm text-danger">{error}</p>}
        <Button type="submit" className="w-full" loading={busy}>Create account</Button>
      </form>
      <div className="mt-6"><GoogleButton onCredential={google} /></div>
    </AuthShell>
  );
}
