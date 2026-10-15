import { cn } from '@/lib/cn';

/** A labelled 0–1 bar for score components. Null = not applicable, shown as such rather than as zero. */
export function ScoreBar({ label, value, detail, tone = 'fit' }: { label: string; value: number | null | undefined; detail?: string; tone?: 'fit' | 'ink' }) {
  const pct = value == null ? null : Math.round(value * 100);
  return (
    <div>
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="font-medium text-ink">{label}</span>
        <span className="tabular text-slate">{pct == null ? 'n/a' : `${pct}%`}</span>
      </div>
      <div className="mt-1.5 h-1.5 rounded-full bg-line">
        {pct != null && <div className={cn('h-1.5 rounded-full', tone === 'fit' ? 'bg-fit' : 'bg-ink')} style={{ width: `${Math.max(2, pct)}%` }} />}
      </div>
      {detail && <p className="mt-1.5 text-xs leading-relaxed text-slate">{detail}</p>}
    </div>
  );
}
