import type { ReactNode } from 'react';
import { AlertCircle } from 'lucide-react';
import { errorMessage } from '@/api/client';
import { Button } from './Button';

/** An empty screen says what to do next. */
export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="max-w-md py-12">
      <h2 className="text-lg font-semibold">{title}</h2>
      {children && <div className="mt-2 text-slate">{children}</div>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex items-start gap-3 rounded border border-danger/30 bg-danger-soft px-4 py-3 text-sm">
      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-danger" aria-hidden />
      <div className="flex-1">
        <p className="text-ink">{errorMessage(error)}</p>
        {onRetry && <Button variant="link" size="sm" className="mt-1 text-sm" onClick={onRetry}>Try again</Button>}
      </div>
    </div>
  );
}
