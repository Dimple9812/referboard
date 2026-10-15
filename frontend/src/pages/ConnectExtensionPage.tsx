import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { CheckCircle2, Puzzle } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '@/api/client';
import { keys } from '@/api/queries';
import type { ExtensionTokens } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { isAllowedExtension, sendToExtension } from '@/lib/extension';

type Step = 'ask' | 'connecting' | 'done' | 'failed';

/** Opened by the extension's "Connect" button. Nothing is handed over until the user confirms here. */
export default function ConnectExtensionPage() {
  const [params] = useSearchParams();
  const ext = params.get('ext');
  const { user } = useAuth();
  const qc = useQueryClient();
  const [step, setStep] = useState<Step>('ask');
  const [error, setError] = useState<string | null>(null);

  if (!isAllowedExtension(ext)) {
    return (
      <Shell>
        <h1 className="text-2xl font-semibold">This link isn't from the ReferBoard extension</h1>
        <p className="mt-3 text-slate">Connect from the extension itself: click its icon in your browser's toolbar and choose Connect.
          Nothing was shared.</p>
        <Button asChild variant="outline" className="mt-6"><Link to="/jobs">Go to your jobs</Link></Button>
      </Shell>
    );
  }

  const connect = async () => {
    setStep('connecting');
    setError(null);
    let refreshToken: string | null = null;
    try {
      const t = (await api.post<ExtensionTokens>('/api/auth/extension/connect')).data;
      refreshToken = t.refreshToken;
      await sendToExtension(ext, {
        type: 'referboard/connect', apiBase: window.location.origin, appBase: window.location.origin,
        accessToken: t.accessToken, expiresIn: t.expiresIn, refreshToken: t.refreshToken,
        user: { id: t.user.id, email: t.user.email, fullName: t.user.fullName },
      });
      qc.invalidateQueries({ queryKey: keys.extensions });
      setStep('done');
    } catch (e) {
      // The session never reached the extension: don't leave it live.
      if (refreshToken) api.post('/api/auth/extension/disconnect', { refreshToken }).catch(() => undefined);
      const code = e instanceof Error ? e.message : '';
      setError(code === 'NO_EXTENSION' || code === 'NO_ANSWER' || code.includes('Could not establish connection')
        ? "The extension didn't answer. Check it's installed and turned on in this browser, then try again."
        : errorMessage(e));
      setStep('failed');
    }
  };

  if (step === 'done') {
    return (
      <Shell>
        <CheckCircle2 className="h-8 w-8 text-fit" aria-hidden />
        <h1 className="mt-4 text-2xl font-semibold">The extension is connected</h1>
        <p className="mt-3 text-slate">You can close this tab. On a job page, click the ReferBoard icon to save the job or check your resume against it.
          You can disconnect it any time in Settings.</p>
        <Button asChild variant="outline" className="mt-6"><Link to="/settings">Open Settings</Link></Button>
      </Shell>
    );
  }

  return (
    <Shell>
      <Puzzle className="h-8 w-8 text-ink" aria-hidden />
      <h1 className="mt-4 text-2xl font-semibold">Connect the ReferBoard extension</h1>
      <p className="mt-3 text-slate">It will act as {user?.fullName} ({user?.email}) in this browser.</p>
      <div className="mt-6 grid gap-6 sm:grid-cols-2">
        <div>
          <h2 className="text-sm font-semibold">It can</h2>
          <ul className="mt-2 list-disc space-y-1.5 pl-5 text-sm text-ink-soft">
            <li>Read the job page you're on, only when you click its button</li>
            <li>Save that job to your tracker</li>
            <li>Check your resume against it and write a cover letter</li>
          </ul>
        </div>
        <div>
          <h2 className="text-sm font-semibold">It never</h2>
          <ul className="mt-2 list-disc space-y-1.5 pl-5 text-sm text-ink-soft">
            <li>Reads LinkedIn, Naukri or Indeed pages (you can paste a description instead)</li>
            <li>Sends messages or connection requests</li>
            <li>Runs in the background or reads other tabs</li>
          </ul>
        </div>
      </div>
      {error && <p role="alert" className="mt-6 text-sm text-danger">{error}</p>}
      <div className="mt-8 flex flex-wrap gap-3">
        <Button onClick={connect} loading={step === 'connecting'}>{step === 'failed' ? 'Try again' : 'Connect'}</Button>
        <Button asChild variant="ghost"><Link to="/jobs">Not now</Link></Button>
      </div>
    </Shell>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return <div className="max-w-xl rounded-lg border border-line bg-surface p-6 sm:p-8">{children}</div>;
}
