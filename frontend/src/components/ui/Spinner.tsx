import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/cn';

export function Spinner({ className, label = 'Loading' }: { className?: string; label?: string }) {
  return <Loader2 role="status" aria-label={label} className={cn('h-5 w-5 animate-spin text-slate', className)} />;
}

export function FullPageSpinner() {
  return <div className="grid min-h-[60vh] place-items-center"><Spinner className="h-6 w-6" /></div>;
}
