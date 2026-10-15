import { cn } from '@/lib/cn';

/**
 * Match % in fit-green, the only place that color appears: a number with a short bar under it. Null means the
 * user has no profile yet, so there is nothing to compare.
 */
export function MatchMeter({ value, size = 'md', className }: { value?: number | null; size?: 'sm' | 'md' | 'lg'; className?: string }) {
  if (value == null) {
    return <div className={cn('text-slate-light', size === 'lg' ? 'text-3xl' : 'text-lg', 'font-display', className)} aria-label="No match score">—</div>;
  }
  const strength = value >= 75 ? 'bg-fit' : value >= 50 ? 'bg-fit-mid' : 'bg-line-strong';
  return (
    <div className={cn('inline-flex flex-col', className)} aria-label={`${value}% match`}>
      <span className={cn('font-display font-semibold tabular leading-none text-fit',
        size === 'sm' ? 'text-lg' : size === 'md' ? 'text-2xl' : 'text-4xl')}>
        {value}<span className={cn('font-medium', size === 'lg' ? 'text-xl' : 'text-sm')}>%</span>
      </span>
      <span className={cn('mt-1.5 h-1 rounded-full bg-line', size === 'lg' ? 'w-24' : 'w-11')}>
        <span className={cn('block h-1 rounded-full', strength)} style={{ width: `${Math.max(4, value)}%` }} />
      </span>
    </div>
  );
}
