import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ChevronDown, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { useDeletePrep, usePrep, useSavePrepAnswer } from '@/api/queries';
import type { Confidence, InterviewPrep, PrepItem, PrepSection } from '@/api/types';
import { CopyButton } from '@/components/CopyButton';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Sheet';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { cn } from '@/lib/cn';

const SECTIONS: { key: PrepSection; title: string; intro: string }[] = [
  { key: 'INTRO', title: 'Opening', intro: 'Nearly every interview starts here. An outline from your resume, not a script.' },
  { key: 'TECHNICAL', title: 'Technical questions', intro: 'For the skills this role asks for that your resume shows.' },
  { key: 'ROLE', title: 'The work itself', intro: "From the posting's responsibilities: have a real example ready for each." },
  { key: 'BEHAVIOURAL', title: 'Behavioural questions', intro: 'Answer with a real situation: what happened, what you did, and the result.' },
  { key: 'GAPS', title: 'Gaps to address honestly', intro: "Skills the posting asks for that your resume doesn't show. Don't bluff; connect them to what you have done." },
  { key: 'ASK', title: 'Questions to ask them', intro: 'Pick two or three. Good questions show you thought about the work.' },
];

const CONFIDENCE: { value: Confidence; label: string }[] = [
  { value: 'NEW', label: 'Not started' },
  { value: 'PRACTISING', label: 'Practising' },
  { value: 'CONFIDENT', label: 'Confident' },
];

export default function InterviewPrepPage() {
  const { id = '' } = useParams();
  const q = usePrep(id);
  if (q.isPending) {
    return <div className="space-y-4" role="status" aria-label="Loading prep"><Skeleton className="h-8 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  }
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  return <Prep prep={q.data} />;
}

function Prep({ prep }: { prep: InterviewPrep }) {
  const navigate = useNavigate();
  const del = useDeletePrep();
  const [onlyNotReady, setOnlyNotReady] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const practised = prep.items.filter((i) => i.section !== 'ASK');
  const p = {
    total: practised.length,
    ready: practised.filter((i) => i.confidence === 'CONFIDENT').length,
    shaky: practised.filter((i) => i.confidence === 'PRACTISING').length,
  };
  const target = [prep.jobTitle, prep.companyName].filter(Boolean).join(', ');
  const visible = (i: PrepItem) => !onlyNotReady || i.section === 'ASK' || i.confidence !== 'CONFIDENT';

  return (
    <article className="max-w-3xl">
      <Link to={prep.jobId ? `/interview?jobId=${prep.jobId}` : '/interview'} className="text-sm text-slate hover:text-ink">All prep packs</Link>
      <header className="mt-4">
        <h1 className="text-2xl font-semibold sm:text-3xl">Interview prep</h1>
        {target && <p className="mt-1 text-slate">For {target}</p>}
        <div className="mt-6 max-w-md">
          <p className="text-sm"><span className="font-medium tabular">{p.ready}</span> of <span className="tabular">{p.total}</span> you feel confident about
            {p.shaky > 0 && <>, <span className="tabular">{p.shaky}</span> in practice</>}</p>
          <div className="mt-2 flex h-1.5 overflow-hidden rounded-full bg-ink/10" role="progressbar" aria-label="Questions you feel confident about"
            aria-valuemin={0} aria-valuemax={p.total} aria-valuenow={p.ready}>
            <div className="bg-ink" style={{ width: `${(p.ready / Math.max(1, p.total)) * 100}%` }} />
            <div className="bg-ink/35" style={{ width: `${(p.shaky / Math.max(1, p.total)) * 100}%` }} />
          </div>
        </div>
        {prep.notices && prep.notices.length > 0 && <ul className="mt-4 text-sm text-slate">{prep.notices.map((n) => <li key={n}>{n}</li>)}</ul>}
        <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" className="h-4 w-4 accent-ink" checked={onlyNotReady} onChange={(e) => setOnlyNotReady(e.target.checked)} />
            Hide the ones I'm confident about
          </label>
          <Button variant="ghost" size="sm" className="text-danger hover:text-danger" onClick={() => setConfirmDelete(true)}>
            <Trash2 className="h-3.5 w-3.5" aria-hidden />Delete pack
          </Button>
        </div>
      </header>

      <nav aria-label="Sections" className="mt-6 flex flex-wrap gap-x-5 gap-y-2 border-y border-line py-3 text-sm">
        {SECTIONS.filter((s) => prep.items.some((i) => i.section === s.key)).map((s) => (
          <a key={s.key} href={`#${s.key.toLowerCase()}`} className="text-slate hover:text-ink">{s.title}</a>
        ))}
      </nav>

      {SECTIONS.map((s) => {
        const items = prep.items.filter((i) => i.section === s.key);
        if (items.length === 0) return null;
        const shown = items.filter(visible);
        return (
          <section key={s.key} id={s.key.toLowerCase()} aria-labelledby={`h-${s.key}`} className="scroll-mt-6 pt-10">
            <div className="flex flex-wrap items-end justify-between gap-3">
              <h2 id={`h-${s.key}`} className="text-lg font-semibold">{s.title}</h2>
              {s.key === 'ASK' && <CopyButton text={items.map((i) => `- ${i.question}`).join('\n')} label="Copy all" />}
            </div>
            <p className="mt-1 max-w-2xl text-sm text-slate">{s.intro}</p>
            {shown.length === 0
              ? <p className="mt-4 text-sm text-slate">You're confident about all of these.</p>
              : (
                <ul className="mt-4 divide-y divide-line border-y border-line">
                  {shown.map((i) => s.key === 'ASK' ? <AskRow key={i.id} item={i} /> : <ItemRow key={i.id} prepId={prep.id} item={i} />)}
                </ul>
              )}
          </section>
        );
      })}

      <Modal open={confirmDelete} onOpenChange={setConfirmDelete} title="Delete this prep pack?"
        description="Your notes for these questions will be deleted too.">
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setConfirmDelete(false)}>Keep it</Button>
          <Button variant="danger" loading={del.isPending} onClick={() => del.mutate(prep.id, {
            onSuccess: () => {
              toast.success('Prep pack deleted');
              navigate('/interview', { replace: true });
            },
          })}>Delete pack</Button>
        </div>
      </Modal>
    </article>
  );
}

function AskRow({ item }: { item: PrepItem }) {
  return (
    <li className="py-3">
      <p className="font-medium">{item.question}</p>

    </li>
  );
}

const STATUS_DOT: Record<Confidence, string> = { NEW: 'border border-line-strong', PRACTISING: 'bg-ink/35', CONFIDENT: 'bg-ink' };

function ItemRow({ prepId, item }: { prepId: string; item: PrepItem }) {
  const save = useSavePrepAnswer(prepId);
  const [open, setOpen] = useState(false);
  const [notes, setNotes] = useState(item.notes ?? '');
  const [state, setState] = useState<'idle' | 'saving' | 'saved' | 'failed'>('idle');
  const timer = useRef<ReturnType<typeof setTimeout>>();

  // One save in flight at a time. Changes made meanwhile (a blur saving notes, then a click on Confident) are merged
  // and sent next, with the version the server just returned, so they never conflict with each other.
  const version = useRef(item.version);
  const confidence = useRef(item.confidence);
  const notesRef = useRef(notes);
  notesRef.current = notes;
  const inflight = useRef(false);
  const pending = useRef<{ notes?: string; confidence?: Confidence } | null>(null);

  const flush = () => {
    const next = pending.current;
    if (!next) return;
    pending.current = null;
    inflight.current = true;
    setState('saving');
    save.mutate({ itemId: item.id, notes: next.notes ?? notesRef.current, confidence: next.confidence ?? confidence.current,
      version: version.current }, {
      onSuccess: (saved) => {
        version.current = saved.version;
        confidence.current = saved.confidence;
        inflight.current = false;
        if (pending.current) flush();
        else setState('saved');
      },
      onError: () => {
        inflight.current = false;
        setState('failed');
      },
    });
  };

  const persist = (next: { notes?: string; confidence?: Confidence }) => {
    clearTimeout(timer.current);
    pending.current = { ...pending.current, ...next };
    if (!inflight.current) flush();
  };

  // Notes save themselves a moment after you stop typing.
  useEffect(() => () => clearTimeout(timer.current), []);
  const onNotes = (v: string) => {
    setNotes(v);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => persist({ notes: v }), 900);
  };

  const panel = `panel-${item.id}`;
  return (
    <li>
      <button type="button" aria-expanded={open} aria-controls={panel} onClick={() => setOpen((o) => !o)}
        className="flex w-full items-start gap-3 py-4 text-left">
        <span className={cn('mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full', STATUS_DOT[item.confidence])}
          aria-label={CONFIDENCE.find((c) => c.value === item.confidence)!.label} />
        <span className="flex-1">
          <span className="block font-medium leading-snug">{item.question}</span>
          {(item.skill || item.origin === 'AI') && (
            <span className="mt-1.5 flex flex-wrap gap-1.5">
              {item.skill && <Badge tone="outline">{item.skill}</Badge>}
              {item.origin === 'AI' && <Badge tone="outline">Written for this posting by AI</Badge>}
            </span>
          )}
        </span>
        <ChevronDown className={cn('mt-1 h-4 w-4 shrink-0 text-slate transition-transform', open && 'rotate-180')} aria-hidden />
      </button>
      {open && (
        <div id={panel} className="space-y-4 pb-5 pl-5">
          {item.checks && <p className="text-sm text-ink-soft"><span className="font-medium text-ink">What they're checking: </span>{item.checks}</p>}
          {item.tip && <p className="text-sm text-ink-soft">{item.tip}</p>}
          {item.evidence && (
            <blockquote className="border-l-2 border-ink pl-3 text-sm">
              <p>{item.evidence}</p>
              <footer className="mt-1 text-xs text-slate">From your resume{item.evidenceFrom ? `: ${item.evidenceFrom}` : ''}</footer>
            </blockquote>
          )}
          <div>
            <label htmlFor={`notes-${item.id}`} className="text-sm font-medium">Your notes</label>
            <textarea id={`notes-${item.id}`} rows={4} maxLength={4000} value={notes} onChange={(e) => onNotes(e.target.value)}
              onBlur={() => { if (notes !== (item.notes ?? '')) { clearTimeout(timer.current); persist({}); } }}
              placeholder="Key points in your own words. Only you see these."
              className="mt-1.5 w-full rounded border border-line-strong bg-surface px-3 py-2 text-sm leading-relaxed focus-visible:border-ink" />
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <div role="radiogroup" aria-label="How ready are you?" className="inline-flex rounded border border-line-strong p-0.5">
              {CONFIDENCE.map((c) => (
                <button key={c.value} type="button" role="radio" aria-checked={item.confidence === c.value}
                  onClick={() => persist({ confidence: c.value })}
                  className={cn('rounded px-3 py-1 text-sm', item.confidence === c.value ? 'bg-ink text-paper' : 'text-slate hover:text-ink')}>
                  {c.label}
                </button>
              ))}
            </div>
            <span className={cn('text-xs', state === 'failed' ? 'text-danger' : 'text-slate')} role="status">
              {state === 'saving' ? 'Saving' : state === 'saved' ? 'Saved' : state === 'failed' ? "Couldn't save. Check your connection." : ''}
            </span>
          </div>
        </div>
      )}
    </li>
  );
}
