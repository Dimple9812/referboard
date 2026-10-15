import { cn } from '@/lib/cn';

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cn('animate-pulse rounded bg-line/70', className)} />;
}

/** Placeholder rows shaped like the list they stand in for. */
export function ListSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div role="status" aria-label="Loading" className="divide-y divide-line border-y border-line">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-start gap-4 py-5">
          <Skeleton className="h-11 w-11 shrink-0" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-2/5" />
            <Skeleton className="h-3 w-1/3" />
            <Skeleton className="h-3 w-3/5" />
          </div>
        </div>
      ))}
    </div>
  );
}
