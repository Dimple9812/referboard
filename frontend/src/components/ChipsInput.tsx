import { useState, type KeyboardEvent } from 'react';
import { X } from 'lucide-react';
import { cn } from '@/lib/cn';

/** Free-text tags: Enter or comma adds, Backspace on empty removes the last. */
export function ChipsInput({ value, onChange, placeholder, id, max = 30, className, describedBy }: {
  value: string[]; onChange: (v: string[]) => void; placeholder?: string; id?: string; max?: number; className?: string;
  describedBy?: string;
}) {
  const [draft, setDraft] = useState('');
  const add = (raw: string) => {
    const v = raw.trim().replace(/,$/, '');
    if (!v || value.some((x) => x.toLowerCase() === v.toLowerCase()) || value.length >= max) return;
    onChange([...value, v]);
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      add(draft);
      setDraft('');
    } else if (e.key === 'Backspace' && !draft && value.length) {
      onChange(value.slice(0, -1));
    }
  };
  return (
    <div className={cn('flex min-h-10 flex-wrap items-center gap-1.5 rounded border border-line-strong bg-surface px-2 py-1.5 focus-within:border-ink', className)}>
      {value.map((v) => (
        <span key={v} className="inline-flex items-center gap-1 rounded bg-ink/5 py-0.5 pl-2 pr-1 text-sm text-ink">
          {v}
          <button type="button" onClick={() => onChange(value.filter((x) => x !== v))} className="rounded p-0.5 text-slate hover:text-ink" aria-label={`Remove ${v}`}>
            <X className="h-3.5 w-3.5" />
          </button>
        </span>
      ))}
      <input id={id} aria-describedby={describedBy} value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={onKey}
        onBlur={() => { add(draft); setDraft(''); }} placeholder={value.length ? '' : placeholder}
        className="min-w-[8rem] flex-1 bg-transparent px-1 py-1 text-sm outline-none placeholder:text-slate-light focus-visible:ring-0" />
    </div>
  );
}
