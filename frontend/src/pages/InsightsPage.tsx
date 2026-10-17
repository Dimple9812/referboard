import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAdminMetrics, useInsights } from '@/api/queries';
import type { Insights } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { rupees, shortDate } from '@/lib/format';
import { cn } from '@/lib/cn';

/** Your job search in numbers. One hue per chart; identity is always in words, never colour alone. */
export default function InsightsPage() {
  const q = useInsights();
  return (
    <div className="max-w-4xl">
      <PageHeader title="Insights">Your job search in numbers: what's moving, and what's working for you.</PageHeader>
      {q.isPending ? <div className="space-y-4" role="status" aria-label="Loading insights"><Skeleton className="h-24" /><Skeleton className="h-48" /></div>
        : q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} />
        : q.data.funnel.saved === 0 && q.data.referrals.added === 0 && q.data.resumes.analyses === 0 ? (
          <EmptyState title="Nothing to show yet" action={<Link to="/jobs" className="font-medium underline underline-offset-4">Find jobs to start with</Link>}>
            Save jobs, check your resume and ask for referrals; this page fills in as you go.
          </EmptyState>
        ) : <Body d={q.data} />}
    </div>
  );
}

function Body({ d }: { d: Insights }) {
  const f = d.funnel;
  const c = d.referredVsCold;
  const r = d.referrals;
  return (
    <div className="space-y-14">
      {d.highlights.length > 0 && (
        <section aria-labelledby="hl">
          <h2 id="hl" className="sr-only">Highlights</h2>
          <ul className="space-y-2 border-l-2 border-marigold pl-4">
            {d.highlights.map((h) => <li key={h} className="leading-relaxed">{h}</li>)}
          </ul>
        </section>
      )}

      <section aria-labelledby="funnel">
        <h2 id="funnel" className="text-lg font-semibold">Applications</h2>
        <p className="mt-1 text-sm text-slate">Every job on your tracker, by the furthest stage it reached.</p>
        <HBars className="mt-5" rows={[
          { label: 'Saved', value: f.saved }, { label: 'Applied or referred', value: f.applied },
          { label: 'Referred', value: f.referred }, { label: 'Interview', value: f.interview }, { label: 'Offer', value: f.offer },
        ]} />
        {f.rejected > 0 && <p className="mt-3 text-sm text-slate tabular">{f.rejected} closed with a rejection.</p>}
        {d.medianDaysToInterview != null && (
          <p className="mt-1 text-sm text-slate">Median time from applying to an interview: <span className="tabular text-ink">{d.medianDaysToInterview}</span> days.</p>
        )}
      </section>

      <section aria-labelledby="cmp">
        <h2 id="cmp" className="text-lg font-semibold">Referred or cold: which gets you interviews?</h2>
        <p className="mt-1 text-sm text-slate">Of the applications that went out, the share that reached an interview.</p>
        <div className="mt-5 grid gap-6 sm:grid-cols-2">
          {([['Referred', c.referred, 'bg-marigold'], ['Cold', c.cold, 'bg-ink/40']] as const).map(([label, g, color]) => (
            <div key={label}>
              <p className="text-sm font-medium">{label}</p>
              {g.rate != null ? (
                <>
                  <p className="mt-1 font-display text-3xl font-semibold tabular">{Math.round(g.rate * 100)}%</p>
                  <div className="mt-2 h-2 overflow-hidden rounded-full bg-ink/10" role="img" aria-label={`${label}: ${g.interviews} of ${g.applications} reached an interview`}>
                    <div className={cn('h-full rounded-full', color)} style={{ width: `${g.rate * 100}%` }} />
                  </div>
                  <p className="mt-1.5 text-sm text-slate tabular">{g.interviews} of {g.applications} applications</p>
                </>
              ) : (
                <p className="mt-1 text-sm text-slate">
                  {g.applications} so far. A rate shows once there are {c.minSample}; fewer would be guesswork.
                </p>
              )}
            </div>
          ))}
        </div>
      </section>

      <section aria-labelledby="refs">
        <h2 id="refs" className="text-lg font-semibold">Referral outreach</h2>
        <p className="mt-1 text-sm text-slate">People you added for referrals, by how far each conversation got.</p>
        <HBars className="mt-5" tone="bg-marigold" rows={[
          { label: 'Added', value: r.added }, { label: 'Connection request sent', value: r.requested },
          { label: 'Connected', value: r.connected }, { label: 'Asked for a referral', value: r.asked },
          { label: 'Referred you', value: r.referred },
        ]} />
        {r.noResponse > 0 && <p className="mt-3 text-sm text-slate tabular">{r.noResponse} marked as no response.</p>}
      </section>

      <Weekly d={d} />

      <section aria-labelledby="res">
        <h2 id="res" className="text-lg font-semibold">Resume work</h2>
        <dl className="mt-4 grid grid-cols-2 gap-6 sm:grid-cols-4">
          <Stat label="JD analyses" value={d.resumes.analyses} />
          <Stat label="Average match" value={d.resumes.averageScore ?? '–'} />
          <Stat label="Tailored resumes" value={d.resumes.tailored} note={d.resumes.avgGain != null ? `+${d.resumes.avgGain} points on average` : undefined} />
          <Stat label="Interview prep packs" value={d.resumes.prepPacks} />
        </dl>
      </section>
    </div>
  );
}

function Stat({ label, value, note }: { label: string; value: number | string; note?: string }) {
  return (
    <div>
      <dt className="text-sm text-slate">{label}</dt>
      <dd className="mt-1 font-display text-2xl font-semibold tabular">{value}</dd>
      {note && <dd className="text-xs text-slate">{note}</dd>}
    </div>
  );
}

/** Horizontal bars on one scale (the first row's value), each labelled with its number. */
function HBars({ rows, className, tone = 'bg-ink' }: { rows: { label: string; value: number }[]; className?: string; tone?: string }) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <dl className={cn('space-y-2.5', className)}>
      {rows.map((r) => (
        <div key={r.label} className="grid grid-cols-[10rem_1fr_2.5rem] items-center gap-3 text-sm sm:grid-cols-[12rem_1fr_3rem]">
          <dt className="text-ink-soft">{r.label}</dt>
          <div className="h-3 rounded-r bg-ink/[0.06]" aria-hidden>
            <div className={cn('h-full rounded-r', tone)} style={{ width: `${(r.value / max) * 100}%`, minWidth: r.value > 0 ? 4 : 0 }} />
          </div>
          <dd className="text-right tabular">{r.value}</dd>
        </div>
      ))}
    </dl>
  );
}

const WEEKLY: { key: 'saved' | 'applied' | 'referralAsks' | 'interviews' | 'analyses'; label: string }[] = [
  { key: 'saved', label: 'Jobs saved' }, { key: 'applied', label: 'Applied' }, { key: 'referralAsks', label: 'Referral asks' },
  { key: 'interviews', label: 'Interviews' }, { key: 'analyses', label: 'JD analyses' },
];

/** Small multiples: one column chart per measure, on its own scale, so no chart needs two axes or a legend. */
function Weekly({ d }: { d: Insights }) {
  const [table, setTable] = useState(false);
  return (
    <section aria-labelledby="weekly">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id="weekly" className="text-lg font-semibold">The last 12 weeks</h2>
          <p className="mt-1 text-sm text-slate">Weeks start on Monday. Hover or focus a bar for its numbers.</p>
        </div>
        <button type="button" onClick={() => setTable((t) => !t)} className="text-sm text-slate underline decoration-line-strong underline-offset-4 hover:text-ink">
          {table ? 'Show as charts' : 'Show as a table'}
        </button>
      </div>
      {table ? (
        <div className="mt-5 overflow-x-auto">
          <table className="w-full min-w-[34rem] text-sm tabular">
            <thead><tr className="border-b border-line text-left text-slate"><th className="py-2 font-normal">Week of</th>
              {WEEKLY.map((w) => <th key={w.key} className="py-2 text-right font-normal">{w.label}</th>)}</tr></thead>
            <tbody>{d.weekly.map((w) => (
              <tr key={w.weekStart} className="border-b border-line"><td className="py-2">{shortDate(w.weekStart)}</td>
                {WEEKLY.map((m) => <td key={m.key} className="py-2 text-right">{w[m.key]}</td>)}</tr>
            ))}</tbody>
          </table>
        </div>
      ) : (
        <div className="mt-5 grid gap-x-8 gap-y-6 sm:grid-cols-2 lg:grid-cols-3">
          {WEEKLY.map((m) => {
            const values = d.weekly.map((w) => w[m.key]);
            const max = Math.max(1, ...values);
            const total = values.reduce((a, b) => a + b, 0);
            return (
              <figure key={m.key}>
                <figcaption className="flex items-baseline justify-between text-sm"><span>{m.label}</span><span className="tabular text-slate">{total} in 12 weeks</span></figcaption>
                <div className="mt-2 flex h-16 items-end gap-[2px] border-b border-line">
                  {d.weekly.map((w) => {
                    const v = w[m.key];
                    const tip = `Week of ${shortDate(w.weekStart)}: ${v}`;
                    return (
                      <div key={w.weekStart} tabIndex={0} title={tip} aria-label={tip}
                        className="group relative flex h-full flex-1 items-end focus:outline-none">
                        <div className="w-full rounded-t-[3px] bg-ink group-hover:bg-ink-soft group-focus-visible:bg-marigold"
                          style={{ height: `${(v / max) * 100}%`, minHeight: v > 0 ? 3 : 0 }} />
                        <span role="tooltip" className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-1 hidden -translate-x-1/2 whitespace-nowrap rounded bg-ink px-2 py-1 text-xs text-paper group-hover:block group-focus-visible:block">
                          {tip}
                        </span>
                      </div>
                    );
                  })}
                </div>
              </figure>
            );
          })}
        </div>
      )}
    </section>
  );
}

/** Platform metrics for admins. */
export function AdminPage() {
  const q = useAdminMetrics();
  return (
    <div className="max-w-4xl">
      <PageHeader title="Admin">Platform metrics, from the same events as everyone's insights. No personal data is shown here.</PageHeader>
      {q.isPending ? <Skeleton className="h-48" /> : q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : (
        <div className="space-y-10">
          <dl className="grid grid-cols-2 gap-6 sm:grid-cols-4">
            <Stat label="Users" value={q.data.users} />
            <Stat label="Pro right now" value={q.data.proNow} />
            <Stat label="Ever paid" value={q.data.everPaid} note={q.data.paidConversionPct != null ? `${q.data.paidConversionPct}% of users` : undefined} />
            <Stat label="Revenue, net of refunds" value={rupees(q.data.revenuePaise)} />
          </dl>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[40rem] text-sm tabular">
              <caption className="mb-2 text-left font-semibold">The last 12 weeks</caption>
              <thead><tr className="border-b border-line text-left text-slate">
                {['Week of', 'Sign-ups', 'Active users', 'Analyses', 'Referral asks', 'Prep packs', 'Purchases', 'Revenue'].map((h, i) =>
                  <th key={h} className={cn('py-2 font-normal', i > 0 && 'text-right')}>{h}</th>)}
              </tr></thead>
              <tbody>{q.data.weekly.map((w) => (
                <tr key={w.weekStart} className="border-b border-line">
                  <td className="py-2">{shortDate(w.weekStart)}</td>
                  {[w.signups, w.activeUsers, w.analyses, w.referralAsks, w.prepPacks, w.purchases].map((v, i) => <td key={i} className="py-2 text-right">{v}</td>)}
                  <td className="py-2 text-right">{rupees(w.revenuePaise)}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
