import { useRef, useState, type DragEvent } from 'react';
import { FileUp } from 'lucide-react';
import { cn } from '@/lib/cn';

const ACCEPT = '.pdf,.docx,.doc,.txt,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain';

/** Drop zone + file picker. Validates type and size before anything is uploaded. */
export function FileDrop({ onFile, maxMb = 5, label, busy }: { onFile: (f: File) => void; maxMb?: number; label: string; busy?: boolean }) {
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pick = (f?: File | null) => {
    if (!f) return;
    if (!/\.(pdf|docx?|txt)$/i.test(f.name)) return setError('Use a PDF, DOCX or TXT file.');
    if (f.size > maxMb * 1024 * 1024) return setError(`That file is over ${maxMb} MB.`);
    setError(null);
    onFile(f);
  };
  const drop = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    pick(e.dataTransfer.files?.[0]);
  };
  return (
    <div>
      <button type="button" disabled={busy} onClick={() => input.current?.click()}
        onDragOver={(e) => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)} onDrop={drop}
        className={cn('flex w-full flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed px-6 py-10 text-center transition-colors',
          over ? 'border-ink bg-ink/5' : 'border-line-strong bg-surface hover:border-ink', busy && 'opacity-60')}>
        <FileUp className="h-6 w-6 text-slate" aria-hidden />
        <span className="font-medium text-ink">{label}</span>
        <span className="text-sm text-slate">PDF, DOCX or TXT, up to {maxMb} MB. Drop it here or choose a file.</span>
      </button>
      <input ref={input} type="file" accept={ACCEPT} className="sr-only" tabIndex={-1} onChange={(e) => { pick(e.target.files?.[0]); e.target.value = ''; }} />
      {error && <p role="alert" className="mt-2 text-sm text-danger">{error}</p>}
    </div>
  );
}
