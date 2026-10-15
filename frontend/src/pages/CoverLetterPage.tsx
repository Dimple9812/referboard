import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Download, RotateCcw, ShieldCheck, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { errorMessage } from '@/api/client';
import { downloadLetter, useCoverLetter, useDeleteLetter, useSaveLetter } from '@/api/queries';
import type { CoverLetter } from '@/api/types';
import { CopyButton } from '@/components/CopyButton';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Sheet';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { shortDate } from '@/lib/format';
import { cn } from '@/lib/cn';

const TARGET: Record<CoverLetter['length'], { min: number; max: number; label: string }> = {
  STANDARD: { min: 0, max: 400, label: 'Up to 400 words fits on one page' },
  SHORT: { min: 0, max: 140, label: 'Up to 140 words fits an email or a form field' },
};

const words = (s: string) => (s.trim() ? s.trim().split(/\s+/).length : 0);

export default function CoverLetterPage() {
  const { id = '' } = useParams();
  const q = useCoverLetter(id);
  if (q.isPending) {
    return <div className="space-y-4" role="status" aria-label="Loading letter"><Skeleton className="h-8 w-1/2" /><Skeleton className="h-96 w-full" /></div>;
  }
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  return <Editor key={q.data.id} letter={q.data} />;
}

function Editor({ letter }: { letter: CoverLetter }) {
  const navigate = useNavigate();
  const save = useSaveLetter(letter.id);
  const del = useDeleteLetter();
  const [text, setText] = useState(letter.body);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [downloading, setDownloading] = useState<'DOCX' | 'PDF' | null>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const dirty = text !== letter.body;
  const target = TARGET[letter.length];
  const count = words(text);

  // The textarea grows with the letter, so it reads like a page instead of a scrolling box.
  useEffect(() => {
    const el = area.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight + 2}px`;
  }, [text]);

  // Don't lose edits to a closed tab.
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  const doSave = () => {
    if (!dirty || !text.trim() || save.isPending) return;
    save.mutate({ body: text, version: letter.version }, { onSuccess: () => toast.success('Letter saved') });
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        doSave();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const download = async (format: 'DOCX' | 'PDF') => {
    if (dirty) {
      toast.error('Save your changes first, so the file has them.');
      return;
    }
    setDownloading(format);
    try {
      await downloadLetter(letter.id, format);
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setDownloading(null);
    }
  };

  const target_ = [letter.jobTitle, letter.companyName].filter(Boolean).join(', ');
  const fallback = letter.notices?.some((n) => n.includes("resume doesn't") || n.includes('unavailable'));

  return (
    <article className="grid gap-8 lg:grid-cols-[1fr_16rem]">
      <div className="min-w-0">
        {letter.analysisId
          ? <Link to={`/analyzer/${letter.analysisId}`} className="text-sm text-slate hover:text-ink">Back to the analysis</Link>
          : <Link to="/settings" className="text-sm text-slate hover:text-ink">Back to settings</Link>}
        <h1 className="mt-4 text-2xl font-semibold sm:text-3xl">Cover letter</h1>
        {target_ && <p className="mt-1 text-slate">For {target_}</p>}

        <div className="mt-5 flex items-start gap-2.5 text-sm text-ink-soft">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-fit" aria-hidden />
          <p>{letter.origin === 'AI'
            ? 'Written with AI, then checked against your resume: it has no numbers or skills your resume doesn’t.'
            : 'Built only from your resume and the posting.'} Read it through and make it sound like you before you send it.</p>
        </div>
        {letter.notices && letter.notices.length > 0 && (
          <ul className={cn('mt-3 space-y-1 rounded border px-3 py-2 text-sm',
            fallback ? 'border-warn/30 bg-warn-soft text-ink' : 'border-line bg-surface text-slate')}>
            {letter.notices.map((n) => <li key={n}>{n}</li>)}
          </ul>
        )}

        <div className="mt-6 rounded-lg border border-line bg-surface px-5 py-6 shadow-[0_1px_2px_rgba(27,36,64,0.06)] sm:px-10 sm:py-10">
          <label htmlFor="letter-body" className="sr-only">Letter text</label>
          <textarea id="letter-body" ref={area} value={text} onChange={(e) => setText(e.target.value)} spellCheck
            className="block w-full resize-none border-0 bg-transparent p-0 text-[0.95rem] leading-7 text-ink focus:outline-none focus-visible:ring-0" />
        </div>
        <p className={cn('mt-2 text-sm tabular', count > target.max ? 'text-warn' : 'text-slate')}>
          {count} words. {target.label}.
        </p>
      </div>

      <aside className="lg:pt-20">
        <div className="space-y-3 lg:sticky lg:top-8">
          <Button className="w-full" onClick={doSave} disabled={!dirty || !text.trim()} loading={save.isPending}>
            {dirty ? 'Save changes' : 'Saved'}
          </Button>
          <div className="grid grid-cols-2 gap-2">
            {(['DOCX', 'PDF'] as const).map((f) => (
              <Button key={f} variant="outline" size="sm" loading={downloading === f} onClick={() => download(f)}
                aria-label={`Download as ${f}`}>
                <Download className="h-3.5 w-3.5" aria-hidden />{f}
              </Button>
            ))}
          </div>
          <div className="[&>button]:w-full"><CopyButton text={text} label="Copy text" /></div>
          {letter.generatedBody && text !== letter.generatedBody && (
            <Button variant="ghost" size="sm" className="w-full" onClick={() => setText(letter.generatedBody!)}>
              <RotateCcw className="h-3.5 w-3.5" aria-hidden />Restore the first draft
            </Button>
          )}
          <p className="pt-2 text-xs text-slate">
            Written {shortDate(letter.createdAt)}{letter.edited ? ', edited since' : ''}. Downloads use today's date.
          </p>
          <Button variant="ghost" size="sm" className="w-full text-danger hover:text-danger" onClick={() => setConfirmDelete(true)}>
            <Trash2 className="h-3.5 w-3.5" aria-hidden />Delete letter
          </Button>
        </div>
      </aside>

      <Modal open={confirmDelete} onOpenChange={setConfirmDelete} title="Delete this letter?"
        description="It can't be brought back. Files you already downloaded aren't affected.">
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setConfirmDelete(false)}>Keep it</Button>
          <Button variant="danger" loading={del.isPending} onClick={() => del.mutate(letter.id, {
            onSuccess: () => {
              toast.success('Letter deleted');
              navigate(letter.analysisId ? `/analyzer/${letter.analysisId}` : '/settings', { replace: true });
            },
          })}>Delete letter</Button>
        </div>
      </Modal>
    </article>
  );
}
