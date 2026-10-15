import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  closestCorners, DndContext, DragOverlay, KeyboardSensor, PointerSensor, useDroppable, useSensor, useSensors,
  type DragEndEvent, type DragOverEvent, type DragStartEvent,
} from '@dnd-kit/core';
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { FileText, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { useAddCardNote, useBoard, useCard, useDeleteCard, useMoveCard, useTrackJob, useUpdateCard } from '@/api/queries';
import type { Card, Stage } from '@/api/types';
import { moveCard, STAGES, stageOf, toColumns, type Columns } from '@/lib/board';
import { STAGE_LABEL } from '@/lib/labels';
import { shortDate, timeAgo } from '@/lib/format';
import { cn } from '@/lib/cn';
import { PageHeader } from '@/components/PageHeader';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { Field, Input, Textarea } from '@/components/ui/Field';
import { Modal, Sheet } from '@/components/ui/Sheet';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { Spinner } from '@/components/ui/Spinner';

export default function TrackerPage() {
  const board = useBoard();
  const move = useMoveCard();
  const [cols, setCols] = useState<Columns>(toColumns());
  const [activeId, setActiveId] = useState<string | null>(null);
  const [start, setStart] = useState<{ stage: Stage; index: number } | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    if (!activeId) setCols(toColumns(board.data));
  }, [board.data, activeId]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),   // a click still opens the card
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const onStart = (e: DragStartEvent) => {
    setActiveId(String(e.active.id));
    const id = String(e.active.id);
    const stage = stageOf(cols, id);
    setStart(stage ? { stage, index: cols[stage].findIndex((c) => c.id === id) } : null);
  };
  const onOver = ({ active, over }: DragOverEvent) => {
    if (!over) return;
    const from = stageOf(cols, String(active.id));
    const to = stageOf(cols, String(over.id));
    if (!from || !to || from === to) return;
    const overIndex = cols[to].findIndex((c) => c.id === over.id);
    setCols((c) => moveCard(c, String(active.id), to, overIndex < 0 ? c[to].length : overIndex));
  };
  const onEnd = ({ active, over }: DragEndEvent) => {
    const id = String(active.id);
    setActiveId(null);
    if (!over) return setCols(toColumns(board.data));
    const stage = stageOf(cols, id)!;
    let index = cols[stage].findIndex((c) => c.id === id);
    const overIndex = cols[stage].findIndex((c) => c.id === over.id);
    if (overIndex >= 0 && overIndex !== index) {
      setCols((c) => moveCard(c, id, stage, overIndex));
      index = overIndex;
    }
    if (start && stage === start.stage && index === start.index) return;   // dropped back where it was
    move.mutate({ id, stage, index }, {
      onSuccess: () => stage !== start?.stage && toast.success(`Moved to ${STAGE_LABEL[stage]}`),
      onError: () => setCols(toColumns(board.data)),
    });
  };

  const active = activeId ? STAGES.flatMap((s) => cols[s]).find((c) => c.id === activeId) : null;

  return (
    <>
      <PageHeader title="Tracker" actions={<Button variant="outline" onClick={() => setAdding(true)}><Plus className="h-4 w-4" aria-hidden />Add a job from elsewhere</Button>}>
        Every application from saved to offer. Drag a card to move it; referrals move cards for you.
      </PageHeader>
      {board.isError && <ErrorState error={board.error} onRetry={() => board.refetch()} />}
      {board.isLoading ? <BoardSkeleton /> : board.data && board.data.total === 0 ? (
        <div className="rounded-lg border border-line bg-surface p-8">
          <h2 className="text-lg font-semibold">Nothing tracked yet</h2>
          <p className="mt-2 max-w-lg text-slate">Save a job from its page, or add one you found elsewhere. Jobs you reach out about for a referral appear here on their own.</p>
          <div className="mt-5 flex gap-2"><Button asChild><Link to="/jobs">Browse jobs</Link></Button><Button variant="outline" onClick={() => setAdding(true)}>Add a job from elsewhere</Button></div>
        </div>
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCorners} onDragStart={onStart} onDragOver={onOver} onDragEnd={onEnd}
          onDragCancel={() => { setActiveId(null); setCols(toColumns(board.data)); }}
          accessibility={{ announcements: {
            onDragStart: ({ active: a }) => `Picked up ${titleOf(cols, String(a.id))}.`,
            onDragOver: ({ over }) => (over ? `Over ${STAGE_LABEL[stageOf(cols, String(over.id))!]}.` : ''),
            onDragEnd: ({ over }) => (over ? `Moved to ${STAGE_LABEL[stageOf(cols, String(over.id))!]}.` : 'Move cancelled.'),
            onDragCancel: () => 'Move cancelled.',
          } }}>
          <div className="-mx-4 flex snap-x gap-4 overflow-x-auto scroll-px-4 px-4 pb-4 sm:-mx-8 sm:scroll-px-8 sm:px-8 lg:-mx-12 lg:scroll-px-12 lg:px-12">
            {STAGES.map((s) => <Column key={s} stage={s} cards={cols[s]} onOpen={setOpen} />)}
          </div>
          <DragOverlay>{active ? <CardView card={active} dragging /> : null}</DragOverlay>
        </DndContext>
      )}
      <CardSheet id={open} onClose={() => setOpen(null)} />
      <AddJobModal open={adding} onClose={() => setAdding(false)} />
    </>
  );
}

function titleOf(cols: Columns, id: string) {
  return STAGES.flatMap((s) => cols[s]).find((c) => c.id === id)?.jobTitle ?? 'card';
}

function Column({ stage, cards, onOpen }: { stage: Stage; cards: Card[]; onOpen: (id: string) => void }) {
  const { setNodeRef, isOver } = useDroppable({ id: stage });
  return (
    <section aria-label={STAGE_LABEL[stage]} className="w-72 shrink-0 snap-start">
      <h2 className="flex items-baseline justify-between px-1 pb-3 font-sans text-sm font-semibold">
        {STAGE_LABEL[stage]}<span className="tabular font-normal text-slate">{cards.length}</span>
      </h2>
      <SortableContext id={stage} items={cards.map((c) => c.id)} strategy={verticalListSortingStrategy}>
        <ul ref={setNodeRef} className={cn('min-h-40 space-y-2 rounded-lg p-1.5 transition-colors',
          stage === 'REFERRED' || stage === 'REFERRAL_REQUESTED' ? 'bg-marigold-soft/50' : 'bg-ink/[0.03]', isOver && 'bg-ink/[0.07]')}>
          {cards.map((c) => <SortableCard key={c.id} card={c} onOpen={onOpen} />)}
        </ul>
      </SortableContext>
    </section>
  );
}

function SortableCard({ card, onOpen }: { card: Card; onOpen: (id: string) => void }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: card.id });
  return (
    <li ref={setNodeRef} style={{ transform: CSS.Transform.toString(transform), transition }} className={cn(isDragging && 'opacity-30')}
      {...attributes} {...listeners} onClick={() => onOpen(card.id)} onKeyDown={(e) => {
        listeners?.onKeyDown?.(e);
        if (e.key === 'Enter' && !e.defaultPrevented) onOpen(card.id);
      }} aria-roledescription="Draggable application. Space to pick up, arrows to move, Enter to open.">
      <CardView card={card} />
    </li>
  );
}

function CardView({ card, dragging }: { card: Card; dragging?: boolean }) {
  return (
    <div className={cn('cursor-grab rounded border border-line bg-surface p-3 text-left hover:border-line-strong',
      dragging && 'cursor-grabbing border-ink shadow-[0_12px_24px_-12px_rgba(27,36,64,0.4)]')}>
      <p className="text-sm font-medium leading-snug">{card.jobTitle}</p>
      <p className="mt-0.5 text-sm text-slate">{card.companyName}</p>
      {card.referralSummary && <p className="mt-2 text-xs text-marigold-deep">{card.referralSummary}</p>}
      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        {card.nextActionAt && <Badge tone="neutral">{card.nextAction ?? 'Next step'}: {shortDate(card.nextActionAt)}</Badge>}
        {card.resumeVersionId && <Badge tone="fit"><FileText className="h-3 w-3" aria-hidden />Tailored resume</Badge>}
        {card.jobClosed && <Badge tone="danger">Posting closed</Badge>}
      </div>
    </div>
  );
}

function CardSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const detail = useCard(id);
  const update = useUpdateCard();
  const addNote = useAddCardNote();
  const del = useDeleteCard();
  const card = detail.data?.card;
  const [notes, setNotes] = useState('');
  const [nextAction, setNextAction] = useState('');
  const [nextAt, setNextAt] = useState('');
  const [note, setNote] = useState('');
  useEffect(() => {
    if (card) {
      setNotes(card.notes ?? '');
      setNextAction(card.nextAction ?? '');
      setNextAt(card.nextActionAt ? card.nextActionAt.slice(0, 10) : '');
    }
  }, [card?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Sheet open={!!id} onOpenChange={(o) => !o && onClose()} title={card?.jobTitle ?? 'Application'}
      description={card ? `${card.companyName}, ${STAGE_LABEL[card.stage]}` : undefined}>
      {!card ? <Spinner /> : (
        <div className="space-y-8">
          <div className="flex flex-wrap gap-2">
            {card.jobId && <Button size="sm" variant="outline" asChild><Link to={`/jobs/${card.jobId}`}>View job</Link></Button>}
            {card.jobId && <Button size="sm" variant="outline" asChild><Link to={`/referrals/jobs/${card.jobId}`}>Referrals</Link></Button>}
            {card.applyUrl && <Button size="sm" variant="outline" asChild><a href={card.applyUrl} target="_blank" rel="noopener noreferrer">Posting</a></Button>}
          </div>
          {card.appliedAt && <p className="text-sm text-slate">Applied on {shortDate(card.appliedAt)}.</p>}

          <section className="space-y-3">
            <h3 className="text-base font-semibold">Next step</h3>
            <div className="grid gap-3 sm:grid-cols-[1fr_10rem]">
              <Field label="What">{(p) => <Input {...p} value={nextAction} onChange={(e) => setNextAction(e.target.value)} placeholder="e.g. System design round" />}</Field>
              <Field label="When">{(p) => <Input {...p} type="date" value={nextAt} onChange={(e) => setNextAt(e.target.value)} />}</Field>
            </div>
            <Field label="Notes">{(p) => <Textarea {...p} rows={4} value={notes} onChange={(e) => setNotes(e.target.value)} />}</Field>
            <Button size="sm" loading={update.isPending} onClick={() => update.mutate({
              id: card.id, notes,
              ...(nextAction || nextAt ? { nextAction: nextAction || undefined, nextActionAt: nextAt ? new Date(`${nextAt}T09:00:00+05:30`).toISOString() : undefined } : { clearNextAction: true }),
            }, { onSuccess: () => toast.success('Saved') })}>Save</Button>
          </section>

          <section>
            <h3 className="text-base font-semibold">Timeline</h3>
            <form className="mt-3 flex gap-2" onSubmit={(e: FormEvent) => {
              e.preventDefault();
              if (note.trim()) addNote.mutate({ id: card.id, text: note.trim() }, { onSuccess: () => setNote('') });
            }}>
              <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Add a dated note" aria-label="Add a dated note" />
              <Button type="submit" variant="outline" loading={addNote.isPending}>Add</Button>
            </form>
            <ol className="mt-4 space-y-3 border-l border-line pl-4">
              {[...(detail.data?.timeline ?? [])].reverse().map((t) => (
                <li key={t.id} className="text-sm">
                  <p className={cn(t.type === 'REFERRAL_UPDATE' && 'text-marigold-deep', t.type === 'NOTE' && 'text-ink')}>
                    {t.type === 'STAGE_CHANGED' && t.toStage ? `Moved to ${STAGE_LABEL[t.toStage]}` : t.message}
                  </p>
                  <p className="text-xs text-slate">{timeAgo(t.occurredAt)}</p>
                </li>
              ))}
            </ol>
          </section>

          <div className="border-t border-line pt-6">
            <Button size="sm" variant="danger" loading={del.isPending} onClick={() => del.mutate(card.id, { onSuccess: () => { toast.success('Removed from tracker'); onClose(); } })}>
              <Trash2 className="h-4 w-4" aria-hidden />Remove from tracker
            </Button>
          </div>
        </div>
      )}
    </Sheet>
  );
}

function AddJobModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const track = useTrackJob();
  const [f, setF] = useState({ jobTitle: '', companyName: '', applyUrl: '', applied: false });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    track.mutate({ jobTitle: f.jobTitle.trim(), companyName: f.companyName.trim(), applyUrl: f.applyUrl.trim() || undefined, stage: f.applied ? 'APPLIED' : 'SAVED' }, {
      onSuccess: () => { toast.success('Added to your tracker'); setF({ jobTitle: '', companyName: '', applyUrl: '', applied: false }); onClose(); },
    });
  };
  return (
    <Modal open={open} onOpenChange={(o) => !o && onClose()} title="Add a job from elsewhere" description="For roles you found outside ReferBoard.">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Job title">{(p) => <Input {...p} required value={f.jobTitle} onChange={(e) => setF({ ...f, jobTitle: e.target.value })} />}</Field>
        <Field label="Company">{(p) => <Input {...p} required value={f.companyName} onChange={(e) => setF({ ...f, companyName: e.target.value })} />}</Field>
        <Field label="Link (optional)">{(p) => <Input {...p} type="url" value={f.applyUrl} onChange={(e) => setF({ ...f, applyUrl: e.target.value })} />}</Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" className="h-4 w-4 accent-ink" checked={f.applied} onChange={(e) => setF({ ...f, applied: e.target.checked })} />I've already applied</label>
        <div className="flex justify-end gap-2"><Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={track.isPending} disabled={!f.jobTitle.trim() || !f.companyName.trim()}>Add job</Button></div>
      </form>
    </Modal>
  );
}

function BoardSkeleton() {
  return (
    <div className="flex gap-4 overflow-hidden" role="status" aria-label="Loading board">
      {STAGES.slice(0, 4).map((s) => <div key={s} className="w-72 shrink-0 space-y-2"><Skeleton className="h-4 w-24" /><Skeleton className="h-20 w-full" /><Skeleton className="h-20 w-full" /></div>)}
    </div>
  );
}
