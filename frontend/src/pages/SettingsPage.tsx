import { useEffect, useState, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { Download, FileText } from 'lucide-react';
import {
  downloadVersion, useBilling, useCoverLetters, useDisconnectExtension, useEmailPrefs, useExtensionConnections, useMakePrimary, useProfile, useResumes, useSaveEmailPrefs, useSavePreferences,
  useUploadResume, useUsage, useVersions,
} from '@/api/queries';
import { errorMessage } from '@/api/client';
import type { EmailPreferences, Resume, ResumeVersion } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { PageHeader } from '@/components/PageHeader';
import { PreferencesFields, usePreferencesDraft } from '@/components/PreferencesForm';
import { FileDrop } from '@/components/ResumeDrop';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Checkbox } from '@/components/ui/Field';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { rupees, shortDate, timeAgo } from '@/lib/format';

/** Settings are a stack of sections: what it is on the left, the controls on the right. */
function Section({ title, description, children }: { title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <section className="grid gap-4 border-t border-line py-8 md:grid-cols-[14rem_1fr] md:gap-10">
      <div>
        <h2 className="font-semibold">{title}</h2>
        {description && <p className="mt-1 text-sm text-slate">{description}</p>}
      </div>
      <div className="min-w-0">{children}</div>
    </section>
  );
}

export default function SettingsPage() {
  // Links like /settings#billing land on that section.
  const { hash } = useLocation();
  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView();
  }, [hash]);
  return (
    <div className="max-w-4xl">
      <PageHeader title="Settings" />
      <AccountSection />
      <BillingSection />
      <ResumesSection />
      <VersionsSection />
      <LettersSection />
      <PreferencesSection />
      <EmailSection />
      <ExtensionSection />
      <UsageSection />
    </div>
  );
}

const METHOD: Record<string, string> = { upi: 'UPI', card: 'Card', netbanking: 'Net banking', wallet: 'Wallet', emi: 'EMI' };

function BillingSection() {
  const billing = useBilling();
  return (
    <div id="billing" className="scroll-mt-6">
      <Section title="Plan and payments" description="Pro passes are one-time payments through Razorpay. Nothing renews.">
        {billing.isPending ? <Skeleton className="h-16" />
          : billing.isError ? <ErrorState error={billing.error} onRetry={() => billing.refetch()} />
          : (
            <div className="space-y-5">
              <div className="flex flex-wrap items-center justify-between gap-4">
                <div>
                  <p className="font-medium">{billing.data.plan === 'PRO' ? 'Pro' : 'Free plan'}</p>
                  <p className="text-sm text-slate">
                    {billing.data.plan === 'PRO'
                      ? `Until ${shortDate(billing.data.proUntil)}, ${billing.data.daysLeft} day${billing.data.daysLeft === 1 ? '' : 's'} left.`
                      : '5 JD analyses and 3 interview prep packs a month.'}
                  </p>
                </div>
                <Button asChild variant={billing.data.plan === 'PRO' ? 'outline' : 'primary'}>
                  <Link to="/pro">{billing.data.plan === 'PRO' ? 'Add more time' : 'Go Pro'}</Link>
                </Button>
              </div>
              {billing.data.orders.length > 0 && (
                <ul className="divide-y divide-line rounded border border-line bg-surface">
                  {billing.data.orders.map((o) => (
                    <li key={o.id} className="flex flex-wrap items-center gap-3 px-4 py-3 text-sm">
                      <div className="min-w-0 flex-1">
                        <p className="font-medium">{o.planName}</p>
                        <p className="text-xs text-slate">
                          Paid {shortDate(o.paidAt)}{o.method ? ` by ${METHOD[o.method] ?? o.method}` : ''}. Reference {o.razorpayPaymentId ?? o.receipt}.
                        </p>
                      </div>
                      <span className="tabular">{rupees(o.amountPaise)}</span>
                      {o.status === 'REFUNDED' && <Badge tone="outline">Refunded</Badge>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
      </Section>
    </div>
  );
}

function AccountSection() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const signOut = async () => {
    setBusy(true);
    await logout();
    navigate('/', { replace: true });
  };
  return (
    <Section title="Account">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <p className="font-medium">{user?.fullName}</p>
          <p className="text-sm text-slate">{user?.email}</p>
        </div>
        <Button variant="outline" loading={busy} onClick={signOut}>Sign out</Button>
      </div>
    </Section>
  );
}

const PARSE: Record<Resume['parseStatus'], { label: string; tone: 'neutral' | 'fit' | 'danger' }> = {
  PENDING: { label: 'Reading', tone: 'neutral' },
  PARSED: { label: 'Read', tone: 'fit' },
  FAILED: { label: "Couldn't read", tone: 'danger' },
};

function ResumesSection() {
  const resumes = useResumes();
  const upload = useUploadResume();
  const primary = useMakePrimary();
  return (
    <Section title="Resumes" description="Your primary resume fills your profile and is used for job matches.">
      {resumes.isPending ? <Skeleton className="h-24" />
        : resumes.isError ? <ErrorState error={resumes.error} onRetry={() => resumes.refetch()} />
        : (
          <div className="space-y-5">
            {resumes.data.length > 0 && (
              <ul className="divide-y divide-line rounded border border-line bg-surface">
                {resumes.data.map((r) => (
                  <li key={r.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                    <FileText className="h-4 w-4 shrink-0 text-slate" aria-hidden />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium">{r.fileName}</p>
                      <p className="text-xs text-slate">
                        Uploaded {timeAgo(r.createdAt)}
                        {r.parseStatus === 'FAILED' && r.parseError ? `. ${r.parseError}` : ''}
                      </p>
                    </div>
                    <Badge tone={PARSE[r.parseStatus].tone}>{PARSE[r.parseStatus].label}</Badge>
                    {r.primary ? <Badge tone="outline">Primary</Badge> : (
                      <Button variant="ghost" size="sm" disabled={r.parseStatus !== 'PARSED'}
                        loading={primary.isPending && primary.variables === r.id}
                        onClick={() => primary.mutate(r.id, { onSuccess: () => toast.success(`${r.fileName} is now your primary resume`) })}>
                        Make primary
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            <FileDrop label={upload.isPending ? 'Uploading…' : 'Upload a resume'} busy={upload.isPending}
              onFile={(f) => upload.mutate(f, { onSuccess: () => toast.success('Uploaded. Reading it now.') })} />
          </div>
        )}
    </Section>
  );
}

function VersionsSection() {
  const versions = useVersions();
  const [pending, setPending] = useState<string | null>(null);
  const download = async (v: ResumeVersion, format: 'DOCX' | 'PDF') => {
    setPending(v.id + format);
    try {
      await downloadVersion(v.id, format);
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setPending(null);
    }
  };
  return (
    <Section title="Tailored resumes" description="Versions you made in the JD Analyzer. Each one only rephrases or reorders what's in your resume.">
      {versions.isPending ? <Skeleton className="h-16" />
        : versions.isError ? <ErrorState error={versions.error} onRetry={() => versions.refetch()} />
        : versions.data.length === 0 ? (
          <p className="text-sm text-slate">None yet. Run a job description through the JD Analyzer, accept the edits you agree with, and generate a version.</p>
        ) : (
          <ul className="divide-y divide-line rounded border border-line bg-surface">
            {versions.data.map((v) => (
              <li key={v.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{v.name}</p>
                  <p className="text-xs text-slate">
                    Made {shortDate(v.createdAt)}
                    {v.scoreBefore != null && v.scoreAfter != null && (
                      <>, match <span className="tabular">{v.scoreBefore}</span> to <span className="tabular font-medium text-fit">{v.scoreAfter}</span></>
                    )}
                  </p>
                </div>
                <div className="flex gap-1">
                  {(['DOCX', 'PDF'] as const).map((f) => (
                    <Button key={f} variant="ghost" size="sm" loading={pending === v.id + f} onClick={() => download(v, f)}
                      aria-label={`Download ${v.name} as ${f}`}>
                      <Download className="h-3.5 w-3.5" aria-hidden />{f}
                    </Button>
                  ))}
                </div>
              </li>
            ))}
          </ul>
        )}
    </Section>
  );
}

function LettersSection() {
  const letters = useCoverLetters();
  return (
    <Section title="Cover letters" description="Written from an analysis, using only what your resume says.">
      {letters.isPending ? <Skeleton className="h-16" />
        : letters.isError ? <ErrorState error={letters.error} onRetry={() => letters.refetch()} />
        : letters.data.length === 0 ? (
          <p className="text-sm text-slate">None yet. Open an analysis in the JD Analyzer and choose Write cover letter.</p>
        ) : (
          <ul className="divide-y divide-line rounded border border-line bg-surface">
            {letters.data.map((l) => (
              <li key={l.id}>
                <Link to={`/cover-letters/${l.id}`} className="flex flex-wrap items-center gap-3 px-4 py-3 hover:bg-ink/[0.02]">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">
                      {[l.jobTitle, l.companyName].filter(Boolean).join(', ') || 'Cover letter'}
                    </span>
                    <span className="text-xs text-slate">
                      {l.length === 'SHORT' ? 'Short note' : 'One-page letter'}, written {shortDate(l.createdAt)}{l.edited ? ', edited' : ''}
                    </span>
                  </span>
                  {l.origin === 'AI' && <Badge tone="outline">AI draft</Badge>}
                </Link>
              </li>
            ))}
          </ul>
        )}
    </Section>
  );
}

function PreferencesSection() {
  const profile = useProfile();
  const save = useSavePreferences();
  const draft = usePreferencesDraft(profile.data);
  return (
    <Section title="Job preferences" description="These shape your recommended jobs.">
      {profile.isPending ? <Skeleton className="h-40" />
        : profile.isError ? <ErrorState error={profile.error} onRetry={() => profile.refetch()} />
        : (
          <form onSubmit={(e) => { e.preventDefault(); save.mutate(draft.value(), { onSuccess: () => toast.success('Preferences saved') }); }}>
            <PreferencesFields draft={draft} />
            <Button type="submit" className="mt-6" loading={save.isPending}>Save preferences</Button>
          </form>
        )}
    </Section>
  );
}

function ExtensionSection() {
  const conns = useExtensionConnections();
  const disconnect = useDisconnectExtension();
  return (
    <Section title="Browser extension" description="Save jobs and check your resume from company career pages.">
      {conns.isPending ? <Skeleton className="h-12" />
        : conns.isError ? <ErrorState error={conns.error} onRetry={() => conns.refetch()} />
        : conns.data.length === 0 ? (
          <p className="text-sm text-slate">Not connected. Install the extension, click its icon and choose Connect. See the README for how to load it.</p>
        ) : (
          <ul className="divide-y divide-line rounded border border-line bg-surface">
            {conns.data.map((c) => (
              <li key={c.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium">{c.browser}</p>
                  <p className="text-xs text-slate">Connected {shortDate(c.connectedAt)}, last used {timeAgo(c.lastUsedAt)}</p>
                </div>
                <Button variant="ghost" size="sm" loading={disconnect.isPending && disconnect.variables === c.id}
                  onClick={() => disconnect.mutate(c.id, { onSuccess: () => toast.success('Extension disconnected') })}>
                  Disconnect
                </Button>
              </li>
            ))}
          </ul>
        )}
    </Section>
  );
}

function EmailSection() {
  const prefs = useEmailPrefs();
  const save = useSaveEmailPrefs();
  const toggle = (patch: Partial<EmailPreferences>) => save.mutate(patch, { onSuccess: () => toast.success('Email settings saved') });
  return (
    <Section title="Email" description="In-app notifications are always on.">
      {prefs.isPending ? <Skeleton className="h-16" />
        : prefs.isError ? <ErrorState error={prefs.error} onRetry={() => prefs.refetch()} />
        : (
          <div className="space-y-4">
            <Checkbox label="Follow-up reminders" description="When it's time to follow up with someone you asked for a referral."
              checked={prefs.data.emailReminders} disabled={save.isPending} onChange={(e) => toggle({ emailReminders: e.target.checked })} />
            <Checkbox label="Daily job digest" description="New jobs that match your profile, once a day."
              checked={prefs.data.emailDigest} disabled={save.isPending} onChange={(e) => toggle({ emailDigest: e.target.checked })} />
          </div>
        )}
    </Section>
  );
}

function UsageSection() {
  const usage = useUsage();
  if (usage.isPending || usage.isError) return null;
  const u = usage.data;
  return (
    <Section title="JD Analyzer" description="Each analysis compares one resume with one job description.">
      {u.unlimited ? <p className="text-sm">Your plan has unlimited analyses.</p> : (
        <div className="max-w-sm">
          <p className="text-sm"><span className="tabular font-medium">{u.used}</span> of <span className="tabular">{u.limit}</span> analyses used this month.</p>
          <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-ink/10" role="progressbar" aria-valuemin={0}
            aria-valuemax={u.limit ?? 0} aria-valuenow={u.used} aria-label="Analyses used">
            <div className="h-full bg-ink" style={{ width: `${Math.min(100, (u.used / Math.max(1, u.limit ?? 1)) * 100)}%` }} />
          </div>
          {u.resetsOn && <p className="mt-2 text-xs text-slate">Resets on {shortDate(u.resetsOn)}.</p>}
        </div>
      )}
    </Section>
  );
}
