import { useMemo, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Search } from 'lucide-react';
import { useJobSearch, useProfile, useRecommended } from '@/api/queries';
import type { JobPage, JobSearch, JobSort } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { JobRow } from '@/components/JobRow';
import { Button } from '@/components/ui/Button';
import { Checkbox, Input, Select } from '@/components/ui/Field';
import { ListSkeleton } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { cn } from '@/lib/cn';
import { plural } from '@/lib/format';

export default function JobFeedPage() {
  const [params, setParams] = useSearchParams();
  const profile = useProfile();
  const hasProfile = profile.data?.exists ?? false;
  const view = params.get('view') === 'search' || params.get('q') ? 'search' : 'recommended';

  const search: JobSearch = useMemo(() => ({
    q: params.get('q') || undefined,
    location: params.get('location') || undefined,
    remote: params.get('remote') === 'true' || undefined,
    postedWithinDays: params.get('days') ? Number(params.get('days')) : undefined,
    hideGhostJobs: params.get('fresh') === 'true' || undefined,
    sort: (params.get('sort') as JobSort) || (params.get('q') ? 'RELEVANCE' : 'RECENT'),
  }), [params]);

  const update = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    Object.entries(patch).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    setParams(next, { replace: true });
  };

  return (
    <>
      <PageHeader title="Jobs">Tech roles from company career pages, matched to your resume.</PageHeader>

      {profile.isSuccess && !hasProfile && (
        <div className="mb-8 flex flex-col gap-3 rounded-lg border border-marigold/40 bg-marigold-soft px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-ink">Add your resume to see how well each job fits you.</p>
          <Button variant="primary" size="sm" asChild><Link to="/onboarding">Add your resume</Link></Button>
        </div>
      )}

      <div role="tablist" aria-label="Job lists" className="mb-6 flex gap-6 border-b border-line">
        {(['recommended', 'search'] as const).map((v) => (
          <button key={v} role="tab" aria-selected={view === v}
            onClick={() => (v === 'search' ? update({ view: 'search' }) : setParams({}, { replace: true }))}
            className={cn('-mb-px border-b-2 pb-2.5 text-sm font-medium', view === v ? 'border-ink text-ink' : 'border-transparent text-slate hover:text-ink')}>
            {v === 'recommended' ? 'Recommended for you' : 'Search all jobs'}
          </button>
        ))}
      </div>

      {view === 'recommended' ? <Recommended enabled={hasProfile} profileLoading={profile.isLoading} />
        : <SearchView search={search} update={update} />}
    </>
  );
}

function Recommended({ enabled, profileLoading }: { enabled: boolean; profileLoading: boolean }) {
  const q = useRecommended(enabled);
  if (profileLoading || (enabled && q.isLoading)) return <ListSkeleton />;
  if (!enabled) {
    return <EmptyState title="Recommendations need your resume">Once we've read it, the jobs that fit you best show up here, best match first.</EmptyState>;
  }
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  return <JobList pages={q.data?.pages ?? []} hasNext={!!q.hasNextPage} loadingMore={q.isFetchingNextPage} onMore={() => q.fetchNextPage()}
    empty={<EmptyState title="No strong matches yet">New jobs arrive every few hours. Meanwhile, widen your roles or cities in Settings, or search all jobs.</EmptyState>} />;
}

function SearchView({ search, update }: { search: JobSearch; update: (p: Record<string, string | undefined>) => void }) {
  const [q, setQ] = useState(search.q ?? '');
  const [location, setLocation] = useState(search.location ?? '');
  const results = useJobSearch(search);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    update({ view: 'search', q: q.trim() || undefined, location: location.trim() || undefined });
  };
  const total = results.data?.pages[0]?.total;

  return (
    <>
      <form onSubmit={submit} role="search" className="grid gap-3 sm:grid-cols-[1fr_14rem_auto]">
        <label className="relative">
          <span className="sr-only">Role, skill or company</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate" aria-hidden />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Role, skill or company" className="pl-9" />
        </label>
        <label>
          <span className="sr-only">City</span>
          <Input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="City (e.g. Bengaluru)" />
        </label>
        <Button type="submit">Search</Button>
      </form>
      <div className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-3 border-b border-line pb-4 text-sm">
        <Checkbox label="Remote" checked={!!search.remote} onChange={(e) => update({ remote: e.target.checked ? 'true' : undefined })} />
        <Checkbox label="Hide jobs listed 60+ days" checked={!!search.hideGhostJobs} onChange={(e) => update({ fresh: e.target.checked ? 'true' : undefined })} />
        <label className="flex items-center gap-2 text-slate">Posted
          <Select className="h-8 w-auto" value={search.postedWithinDays ?? ''} onChange={(e) => update({ days: e.target.value || undefined })}>
            <option value="">any time</option><option value="1">today</option><option value="7">this week</option><option value="30">this month</option>
          </Select>
        </label>
        <label className="flex items-center gap-2 text-slate sm:ml-auto">Sort
          <Select className="h-8 w-auto" value={search.sort} onChange={(e) => update({ sort: e.target.value })}>
            {search.q && <option value="RELEVANCE">Best keyword match</option>}
            <option value="RECENT">Newest</option><option value="MATCH">Best fit for me</option>
          </Select>
        </label>
      </div>
      {total != null && <p className="mt-4 text-sm text-slate" aria-live="polite">{plural(total, 'job')}</p>}
      {results.isLoading ? <div className="mt-4"><ListSkeleton /></div>
        : results.isError ? <div className="mt-4"><ErrorState error={results.error} onRetry={() => results.refetch()} /></div>
          : <JobList pages={results.data?.pages ?? []} hasNext={!!results.hasNextPage} loadingMore={results.isFetchingNextPage}
              onMore={() => results.fetchNextPage()} dim={results.isPlaceholderData}
              empty={<EmptyState title="No jobs match these filters">Remove a filter, or try a broader role such as “backend” instead of a full title.</EmptyState>} />}
    </>
  );
}

function JobList({ pages, hasNext, loadingMore, onMore, empty, dim }: {
  pages: JobPage[]; hasNext: boolean; loadingMore: boolean; onMore: () => void; empty: React.ReactNode; dim?: boolean;
}) {
  const jobs = pages.flatMap((p) => p.items);
  if (jobs.length === 0) return <>{empty}</>;
  return (
    <>
      <ul className={cn('divide-y divide-line border-b border-line transition-opacity', dim && 'opacity-60')}>
        {jobs.map((j) => <JobRow key={j.id} job={j} />)}
      </ul>
      {hasNext && <div className="mt-6"><Button variant="outline" onClick={onMore} loading={loadingMore}>Show more jobs</Button></div>}
    </>
  );
}
