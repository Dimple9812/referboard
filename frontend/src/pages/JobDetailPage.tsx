import { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import DOMPurify from 'dompurify';
import { ExternalLink, FileSearch, Users } from 'lucide-react';
import { toast } from 'sonner';
import { useCardByJob, useJob, useTrackJob } from '@/api/queries';
import type { JobDetail } from '@/api/types';
import { MatchMeter } from '@/components/MatchMeter';
import { ScoreBar } from '@/components/Bar';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { STAGE_LABEL } from '@/lib/labels';
import { salaryRange, shortDate, timeAgo } from '@/lib/format';

const COMPONENT_LABEL: Record<string, string> = {
  semantic: 'Experience similarity',
  skills: 'Skills',
  preferences: 'Your preferences',
};

export default function JobDetailPage() {
  const { id = '' } = useParams();
  const job = useJob(id);
  if (job.isLoading) return <DetailSkeleton />;
  if (job.isError) return <ErrorState error={job.error} onRetry={() => job.refetch()} />;
  return <Detail data={job.data!} />;
}

function Detail({ data }: { data: JobDetail }) {
  const { job, match } = data;
  const card = useCardByJob(job.id);
  const track = useTrackJob();
  const html = useMemo(() => DOMPurify.sanitize(data.descriptionHtml ?? '', {
    FORBID_TAGS: ['style', 'img', 'iframe', 'form', 'input'], FORBID_ATTR: ['style', 'onerror', 'onclick'],
  }), [data.descriptionHtml]);
  const pay = salaryRange(job.salaryMin, job.salaryMax, job.salaryCurrency);
  const closed = data.status === 'CLOSED';

  return (
    <article>
      <Link to="/jobs" className="text-sm text-slate hover:text-ink">Back to jobs</Link>
      <header className="mt-4 flex flex-col gap-6 border-b border-line pb-8 lg:flex-row lg:items-end lg:justify-between">
        <div className="max-w-2xl">
          <h1 className="text-2xl font-semibold sm:text-3xl">{job.title}</h1>
          <p className="mt-2 text-lg text-ink-soft">{job.companyName}</p>
          <p className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate">
            {job.location && <span>{job.location}</span>}
            {job.remote && !(job.location ?? '').toLowerCase().includes('remote') && <span>Remote</span>}
            {pay && <span className="font-medium text-ink">{pay}</span>}
            {job.postedAt && <span>Posted {timeAgo(job.postedAt)}</span>}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            {closed && <Badge tone="danger">Closed {data.closedAt ? shortDate(data.closedAt) : ''}</Badge>}
            {job.ghostJobSuspected && <Badge tone="warn">Listed for 60+ days, may not be actively hiring</Badge>}
            {card.data && <Badge tone="neutral">On your tracker: {STAGE_LABEL[card.data.stage]}</Badge>}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {job.applyUrl && !closed && (
            <Button asChild><a href={job.applyUrl} target="_blank" rel="noopener noreferrer">Apply on company site <ExternalLink className="h-4 w-4" aria-hidden /></a></Button>
          )}
          {!card.data && !card.isLoading && (
            <Button variant="outline" loading={track.isPending}
              onClick={() => track.mutate({ jobId: job.id }, { onSuccess: () => toast.success('Saved to your tracker') })}>Save to tracker</Button>
          )}
        </div>
      </header>

      <div className="mt-8 grid gap-10 lg:grid-cols-[1fr_20rem]">
        <section aria-label="Job description" className="order-2 lg:order-1">
          {html ? <div className="prose-jd" dangerouslySetInnerHTML={{ __html: html }} />
            : <p className="text-slate">The posting has no description. Open it on the company site for details.</p>}
        </section>

        <aside className="order-1 space-y-8 lg:order-2">
          <section aria-labelledby="fit" className="rounded-lg border border-line bg-surface p-5">
            <h2 id="fit" className="text-base font-semibold">How you fit</h2>
            {match?.available ? (
              <>
                <p className="mt-1 text-xs text-slate">From your profile and preferences. Check my resume scores the resume itself against this posting, so its number can differ.</p>
                <MatchMeter value={match.matchPercent} size="lg" className="mt-3" />
                <div className="mt-5 space-y-4">
                  {Object.entries(match.components).map(([k, v]) => (
                    <ScoreBar key={k} label={COMPONENT_LABEL[k] ?? k} value={v} />
                  ))}
                </div>
                {match.missingSkills.length > 0 && (
                  <div className="mt-5">
                    <h3 className="text-sm font-medium">Not on your resume</h3>
                    <div className="mt-2 flex flex-wrap gap-1.5">{match.missingSkills.map((s) => <Badge key={s.name} tone="outline">{s.label}</Badge>)}</div>
                  </div>
                )}
                {match.reasons.length > 0 && (
                  <ul className="mt-5 space-y-1.5 text-sm text-slate">{match.reasons.map((r) => <li key={r}>{r}</li>)}</ul>
                )}
              </>
            ) : (
              <p className="mt-2 text-sm text-slate">Add your resume to see how well you fit. <Link to="/onboarding" className="font-medium text-ink underline decoration-marigold underline-offset-4">Add it now</Link></p>
            )}
          </section>

          <section aria-labelledby="next" className="space-y-3">
            <h2 id="next" className="text-base font-semibold">Before you apply</h2>
            <Link to={`/analyzer?jobId=${job.id}`} className="flex items-start gap-3 rounded-lg border border-line bg-surface p-4 hover:border-ink">
              <FileSearch className="mt-0.5 h-5 w-5 shrink-0 text-ink" aria-hidden />
              <span><span className="block font-medium">Check my resume</span>
                <span className="text-sm text-slate">See the gaps for this job and get edits you approve.</span></span>
            </Link>
            <Link to={`/referrals/jobs/${job.id}`} className="flex items-start gap-3 rounded-lg border border-marigold/50 bg-marigold-soft p-4 hover:border-marigold">
              <Users className="mt-0.5 h-5 w-5 shrink-0 text-marigold-deep" aria-hidden />
              <span><span className="block font-medium">Find people to ask for a referral</span>
                <span className="text-sm text-ink-soft">Recruiters, managers and alumni at {job.companyName}.</span></span>
            </Link>
          </section>
        </aside>
      </div>
    </article>
  );
}

function DetailSkeleton() {
  return (
    <div role="status" aria-label="Loading job" className="space-y-4">
      <Skeleton className="h-8 w-2/3" /><Skeleton className="h-5 w-1/3" /><Skeleton className="h-4 w-1/2" />
      <div className="grid gap-10 pt-6 lg:grid-cols-[1fr_20rem]">
        <div className="space-y-3">{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-4 w-full" />)}</div>
        <Skeleton className="h-64 w-full" />
      </div>
    </div>
  );
}
