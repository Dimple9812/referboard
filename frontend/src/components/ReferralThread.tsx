import type { ContactStatus } from '@/api/types';
import { PIPELINE, STATUS_LABEL } from '@/lib/labels';
import { cn } from '@/lib/cn';

/**
 * The referral thread: you → a person → the company, drawn as a marigold line. The signature element of the
 * product; used for the landing hero and, in compact form, to show how far a contact has come.
 */
export function ReferralThread({ nodes, className }: { nodes: { label: string; sub?: string; you?: boolean }[]; className?: string }) {
  return (
    <ol className={cn('flex items-start', className)}>
      {nodes.map((n, i) => (
        <li key={i} className="relative flex flex-1 flex-col items-start last:flex-none">
          <div className="flex w-full items-center">
            <span className={cn('relative z-10 grid h-11 w-11 shrink-0 place-items-center rounded-full border-2 font-display text-sm font-semibold',
              n.you ? 'border-ink bg-ink text-white' : 'border-marigold bg-surface text-ink')}>
              {initials(n.label)}
            </span>
            {i < nodes.length - 1 && <span aria-hidden className="h-[3px] flex-1 bg-marigold" />}
          </div>
          <span className="mt-2 pr-3 text-sm font-medium leading-tight text-ink">{n.label}</span>
          {n.sub && <span className="pr-3 text-xs text-slate">{n.sub}</span>}
        </li>
      ))}
    </ol>
  );
}

/** Compact progress for one contact: filled thread up to the current status. */
export function StatusThread({ status }: { status: ContactStatus }) {
  const noResponse = status === 'NO_RESPONSE';
  const reached = noResponse ? 1 : PIPELINE.indexOf(status);
  return (
    <div className="flex items-center gap-1" aria-label={`Status: ${STATUS_LABEL[status]}`}>
      {PIPELINE.slice(1).map((s, i) => (
        <span key={s} title={STATUS_LABEL[s]}
          className={cn('h-1.5 w-6 rounded-full', i < reached ? (noResponse ? 'bg-slate-light' : 'bg-marigold') : 'bg-line')} />
      ))}
    </div>
  );
}

function initials(name: string) {
  const parts = name.replace(/[^\p{L}\s]/gu, '').trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase() || '·';
}
