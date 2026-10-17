import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { errorCode } from '@/api/client';
import { useAnalyses, useMakePrep, usePrepUsage, usePreps } from '@/api/queries';
import type { AnalysisSummary } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { Button } from '@/components/ui/Button';
import { ListSkeleton } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { shortDate, timeAgo } from '@/lib/format';

/** Prep packs, and building one from an analysis. ?jobId= narrows both to one job (the inbox nudge links here). */
export default function InterviewListPage() {
  const [params] = useSearchParams();
  const jobId = params.get('jobId') ?? undefined;
  const packs = usePreps(jobId);
  const analyses = useAnalyses(jobId);
  const usage = usePrepUsage().data;
  const make = useMakePrep();
  const navigate = useNavigate();
  const build = (a: AnalysisSummary) => make.mutate(a.id, { onSuccess: (p) => navigate(`/interview/${p.id}`) });
  const limited = errorCode(make.error) === 'PREP_LIMIT' || usage?.remaining === 0;
  const withPack = new Set(packs.data?.map((p) => p.analysisId));

  return (
    <div className="max-w-3xl">
      <PageHeader title="Interview prep">
        Questions you're likely to get for a specific job, the examples from your own resume to answer with, and honest
        ways to handle what's missing.
      </PageHeader>
      {jobId && <p className="-mt-4 mb-6 text-sm"><Link to="/interview" className="text-slate underline decoration-line-strong underline-offset-4 hover:text-ink">Show all jobs</Link></p>}

      <section aria-labelledby="packs">
        <h2 id="packs" className="text-lg font-semibold">Your prep packs</h2>
        {packs.isPending ? <ListSkeleton rows={2} />
          : packs.isError ? <ErrorState error={packs.error} onRetry={() => packs.refetch()} />
          : packs.data.length === 0 ? <p className="mt-2 text-sm text-slate">None yet. Build one from an analysis below.</p>
          : (
            <ul className="mt-4 divide-y divide-line border-y border-line">
              {packs.data.map((p) => (
                  <li key={p.id}>
                    <Link to={`/interview/${p.id}`} className="flex flex-wrap items-center gap-4 py-3 hover:bg-surface">
                      <span className="min-w-0 flex-1">
                        <span className="block truncate font-medium">{p.jobTitle ?? 'Job'}{p.companyName ? `, ${p.companyName}` : ''}</span>
                        <span className="text-sm text-slate">Built {shortDate(p.createdAt)}</span>
                      </span>
                      <span className="text-sm text-slate tabular">
                        {p.confident} confident, {p.practising} in practice
                      </span>
                    </Link>
                  </li>
              ))}
            </ul>
          )}
      </section>

      <section aria-labelledby="start" className="mt-12">
        <h2 id="start" className="text-lg font-semibold">Build a pack from an analysis</h2>
        <p className="mt-1 text-sm text-slate">
          A pack uses the posting and the resume from one JD analysis.
          {usage && !usage.unlimited && <> You've built <span className="tabular">{usage.used}</span> of <span className="tabular">{usage.limit}</span> free packs this month.</>}
        </p>
        {limited && (
          <p className="mt-3 rounded border border-line bg-surface px-3 py-2 text-sm">
            You've used this month's free packs. <Link to="/pro" className="font-medium underline underline-offset-4">Go Pro</Link> for unlimited packs, or wait until {usage?.resetsOn ? shortDate(usage.resetsOn) : 'next month'}.
          </p>
        )}
        {analyses.isPending ? <ListSkeleton rows={3} />
          : analyses.isError ? <ErrorState error={analyses.error} onRetry={() => analyses.refetch()} />
          : analyses.data.length === 0 ? (
            <EmptyState title={jobId ? 'No analysis for this job yet' : 'No analyses yet'}
              action={<Button asChild><Link to={jobId ? `/analyzer?jobId=${jobId}` : '/analyzer'}>Check my resume</Link></Button>}>
              Check your resume against the job first. The prep pack is built from that comparison.
            </EmptyState>
          ) : (
            <ul className="mt-4 divide-y divide-line border-y border-line">
              {analyses.data.map((a) => (
                <li key={a.id} className="flex flex-wrap items-center gap-4 py-3">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{a.jobTitle ?? 'Pasted job description'}{a.companyName ? `, ${a.companyName}` : ''}</span>
                    <span className="text-sm text-slate">Analysed {timeAgo(a.createdAt)}, match {a.score}</span>
                  </span>
                  <Button size="sm" variant={withPack.has(a.id) ? 'outline' : 'primary'} disabled={limited}
                    loading={make.isPending && make.variables === a.id} onClick={() => build(a)}>
                    {withPack.has(a.id) ? 'Build another' : 'Build prep pack'}
                  </Button>
                </li>
              ))}
            </ul>
          )}
      </section>
    </div>
  );
}
