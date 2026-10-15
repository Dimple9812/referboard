import { Link } from 'react-router-dom';
import type { JobCard } from '@/api/types';
import { MatchMeter } from './MatchMeter';
import { Badge } from './ui/Badge';
import { salaryRange, timeAgo } from '@/lib/format';

/** One job in a list: match first (that's what people scan for), then what and where. */
export function JobRow({ job }: { job: JobCard }) {
  const matched = new Set(job.matchedSkills.map((s) => s.name));
  const skills = [...job.skills].sort((a, b) => Number(matched.has(b.name)) - Number(matched.has(a.name))).slice(0, 6);
  const pay = salaryRange(job.salaryMin, job.salaryMax, job.salaryCurrency);
  const where = [job.location, job.remote && !(job.location ?? '').toLowerCase().includes('remote') ? 'Remote' : null].filter(Boolean).join(' / ');
  return (
    <li className="group relative grid grid-cols-[3.5rem_1fr] gap-4 py-5 sm:grid-cols-[4.5rem_1fr_auto]">
      <MatchMeter value={job.matchPercent} size="sm" className="pt-0.5" />
      <div className="min-w-0">
        <h3 className="font-sans text-base font-semibold leading-snug">
          <Link to={`/jobs/${job.id}`} className="after:absolute after:inset-0 hover:underline hover:decoration-line-strong hover:underline-offset-4">{job.title}</Link>
        </h3>
        <p className="mt-0.5 text-sm text-ink-soft">
          {job.companyName}
          {where && <span className="text-slate">, {where}</span>}
        </p>
        <div className="mt-2.5 flex flex-wrap gap-1.5">
          {skills.map((s) => <Badge key={s.name} tone={matched.has(s.name) ? 'fit' : 'neutral'}>{s.label}</Badge>)}
        </div>
      </div>
      <div className="col-start-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-slate sm:col-start-3 sm:flex-col sm:items-end sm:text-right">
        {pay && <span className="font-medium text-ink">{pay}</span>}
        {job.postedAt && <span>{timeAgo(job.postedAt)}</span>}
        {job.ghostJobSuspected && <Badge tone="warn" title="Listed for 60+ days; it may not be actively hiring">Listed 60+ days</Badge>}
      </div>
    </li>
  );
}
