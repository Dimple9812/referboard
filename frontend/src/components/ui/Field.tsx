import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

const control = 'w-full rounded border border-line-strong bg-surface px-3 text-ink placeholder:text-slate-light '
  + 'focus-visible:border-ink focus-visible:ring-0 focus-visible:ring-offset-0 disabled:bg-paper';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(({ className, ...p }, ref) => (
  <input ref={ref} className={cn(control, 'h-10 text-sm', className)} {...p} />
));
Input.displayName = 'Input';

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(({ className, ...p }, ref) => (
  <textarea ref={ref} className={cn(control, 'min-h-24 py-2 text-sm leading-relaxed', className)} {...p} />
));
Textarea.displayName = 'Textarea';

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(({ className, ...p }, ref) => (
  <select ref={ref} className={cn(control, 'h-10 pr-8 text-sm', className)} {...p} />
));
Select.displayName = 'Select';

/** Label + control + hint/error, wired for screen readers. */
export function Field({ label, hint, error, children, className }: {
  label: string; hint?: ReactNode; error?: string | null; className?: string;
  children: (props: { id: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }) => ReactNode;
}) {
  const id = useId();
  const described = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className={cn('space-y-1.5', className)}>
      <label htmlFor={id} className="block text-sm font-medium text-ink">{label}</label>
      {children({ id, 'aria-describedby': described, 'aria-invalid': !!error || undefined })}
      {error ? <p id={`${id}-error`} className="text-sm text-danger">{error}</p>
        : hint ? <p id={`${id}-hint`} className="text-xs text-slate">{hint}</p> : null}
    </div>
  );
}

export function Checkbox({ label, description, className, ...p }: InputHTMLAttributes<HTMLInputElement> & { label: ReactNode; description?: ReactNode }) {
  const id = useId();
  return (
    <div className={cn('flex items-start gap-3', className)}>
      <input id={id} type="checkbox" className="mt-0.5 h-4 w-4 shrink-0 accent-ink" {...p} />
      <label htmlFor={id} className="text-sm leading-snug">
        <span className="font-medium text-ink">{label}</span>
        {description && <span className="mt-0.5 block text-slate">{description}</span>}
      </label>
    </div>
  );
}
