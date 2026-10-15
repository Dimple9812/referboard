import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ExternalLink, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { markDraftCopied, useChangeContactStatus, useContact, useDeleteContact, useGenerateDrafts } from '@/api/queries';
import type { Contact, ContactStatus, Draft } from '@/api/types';
import { PERSON_LABEL, STATUS_ACTION, STATUS_LABEL } from '@/lib/labels';
import { shortDate, timeAgo } from '@/lib/format';
import { cn } from '@/lib/cn';
import { Button } from './ui/Button';
import { Badge } from './ui/Badge';
import { Field, Textarea } from './ui/Field';
import { Sheet } from './ui/Sheet';
import { Spinner } from './ui/Spinner';
import { CopyButton } from './CopyButton';
import { StatusThread } from './ReferralThread';

const NOTE_LIMIT = 300;

/** Everything about one contact: record what you did, get drafts to send yourself, see the history. */
export function ContactSheet({ contactId, onClose }: { contactId: string | null; onClose: () => void }) {
  const detail = useContact(contactId);
  const c = detail.data?.contact;
  return (
    <Sheet open={!!contactId} onOpenChange={(o) => !o && onClose()} wide
      title={c?.fullName ?? 'Contact'}
      description={c ? `${PERSON_LABEL[c.personType]}${c.companyName ? ` at ${c.companyName}` : ''}${c.jobTitle ? `, for ${c.jobTitle}` : ''}` : undefined}>
      {detail.isLoading || !detail.data ? <Spinner /> : (
        <div className="space-y-10">
          <StatusSection contact={detail.data.contact} />
          <DraftsSection contact={detail.data.contact} drafts={detail.data.drafts} />
          <section aria-labelledby="history">
            <h3 id="history" className="text-base font-semibold">History</h3>
            <ol className="mt-3 space-y-2 text-sm">
              {[...detail.data.history].reverse().map((h, i) => (
                <li key={i} className="flex justify-between gap-4"><span>{STATUS_LABEL[h.toStatus]}</span><span className="text-slate">{shortDate(h.changedAt)}</span></li>
              ))}
            </ol>
            {detail.data.reminders.filter((r) => r.status === 'PENDING').map((r) => (
              <p key={r.id} className="mt-3 text-sm text-slate">Reminder {timeAgo(r.dueAt)}: {r.type === 'FOLLOW_UP' ? 'follow up if there’s no reply' : 'send your referral message'}.</p>
            ))}
          </section>
          <DeleteContact contact={detail.data.contact} onDeleted={onClose} />
        </div>
      )}
    </Sheet>
  );
}

function StatusSection({ contact }: { contact: Contact }) {
  const change = useChangeContactStatus();
  const next = contact.allowedNextStatuses.filter((s) => s !== 'NOT_CONTACTED');
  return (
    <section aria-labelledby="status">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 id="status" className="text-base font-semibold">{STATUS_LABEL[contact.status]}</h3>
        <StatusThread status={contact.status} />
      </div>
      {contact.headline && <p className="mt-1 text-sm text-slate">{contact.headline}</p>}
      {contact.profileUrl && (
        <a href={contact.profileUrl} target="_blank" rel="noopener noreferrer" className="mt-2 inline-flex items-center gap-1 text-sm underline decoration-line-strong underline-offset-4 hover:text-ink">
          Open their profile <ExternalLink className="h-3.5 w-3.5" aria-hidden />
        </a>
      )}
      {next.length > 0 && (
        <>
          <p className="mt-4 text-sm text-slate">Record what happened on LinkedIn:</p>
          <div className="mt-2 flex flex-wrap gap-2">
            {next.map((s: ContactStatus) => (
              <Button key={s} size="sm" variant={s === 'NO_RESPONSE' ? 'ghost' : 'outline'} loading={change.isPending && change.variables?.status === s}
                onClick={() => change.mutate({ id: contact.id, status: s }, { onSuccess: () => toast.success(STATUS_LABEL[s]) })}>
                {STATUS_ACTION[s]}
              </Button>
            ))}
          </div>
        </>
      )}
      {contact.status === 'REFERRED' && <p className="mt-4 text-sm text-fit">Referred. It's on your tracker too.</p>}
    </section>
  );
}

function DraftsSection({ contact, drafts }: { contact: Contact; drafts: Draft[] }) {
  const generate = useGenerateDrafts();
  const followUp = ['REFERRAL_ASKED', 'NO_RESPONSE'].includes(contact.status);
  const [context, setContext] = useState('');
  const [notices, setNotices] = useState<string[]>([]);
  const latest = latestByKindTone(drafts);
  const kinds = followUp ? (['FOLLOW_UP', 'REFERRAL_REQUEST'] as const) : (['CONNECTION_NOTE', 'REFERRAL_REQUEST'] as const);

  return (
    <section aria-labelledby="drafts">
      <h3 id="drafts" className="text-base font-semibold">Messages to send yourself</h3>
      <p className="mt-1 text-sm text-slate">Drafted from your profile and this job. Edit them as you like, then paste them into LinkedIn.</p>
      <Field label="Anything true to mention? (optional)" className="mt-4" hint="For example: “we met at JSConf India”. It's used exactly as you write it.">
        {(p) => <Textarea {...p} rows={2} maxLength={300} value={context} onChange={(e) => setContext(e.target.value)} className="min-h-0" />}
      </Field>
      <Button className="mt-3" variant="referral" loading={generate.isPending}
        onClick={() => generate.mutate({ contactId: contact.id, type: followUp ? 'FOLLOW_UP' : 'INTRO', context },
          { onSuccess: (d) => setNotices(d.notices.filter((n) => !n.startsWith('Review before sending'))) })}>
        {Object.keys(latest).length ? 'Write new drafts' : followUp ? 'Draft a follow-up' : 'Draft a connection note and referral request'}
      </Button>
      {notices.length > 0 && <ul className="mt-3 space-y-1 text-sm text-slate">{notices.map((n) => <li key={n}>{n}</li>)}</ul>}

      <div className="mt-6 space-y-6">
        {kinds.map((kind) => {
          const pair = (['WARM', 'CONCISE'] as const).map((t) => latest[`${kind}:${t}`]).filter(Boolean) as Draft[];
          if (!pair.length) return null;
          return (
            <div key={kind}>
              <h4 className="text-sm font-semibold">{kind === 'CONNECTION_NOTE' ? 'Connection note' : kind === 'REFERRAL_REQUEST' ? 'Referral request (after they accept)' : 'Follow-up'}</h4>
              <div className="mt-2 grid gap-3 md:grid-cols-2">
                {pair.map((d) => <DraftCard key={d.id} draft={d} />)}
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function DraftCard({ draft }: { draft: Draft }) {
  const [text, setText] = useState(draft.content);
  const over = draft.kind === 'CONNECTION_NOTE' && text.length > NOTE_LIMIT;
  return (
    <div className="flex flex-col rounded border border-line bg-paper p-3">
      <div className="flex items-center justify-between text-xs text-slate">
        <span className="flex items-center gap-2">{draft.tone === 'WARM' ? 'Warm' : 'Concise'}{draft.model !== 'template' && <Badge tone="outline">AI draft</Badge>}</span>
        {draft.kind === 'CONNECTION_NOTE' && <span className={cn('tabular', over && 'font-semibold text-danger')}>{text.length}/{NOTE_LIMIT}</span>}
      </div>
      <Textarea aria-label={`${draft.tone.toLowerCase()} draft`} value={text} onChange={(e) => setText(e.target.value)}
        rows={draft.kind === 'CONNECTION_NOTE' ? 5 : 10} className="mt-2 flex-1 bg-surface" />
      {over && <p className="mt-1 text-xs text-danger">LinkedIn connection notes are limited to 300 characters.</p>}
      <div className="mt-2 flex justify-end"><CopyButton text={text} onCopied={() => markDraftCopied(draft.id)} /></div>
    </div>
  );
}

function DeleteContact({ contact, onDeleted }: { contact: Contact; onDeleted: () => void }) {
  const del = useDeleteContact();
  const [confirm, setConfirm] = useState(false);
  return (
    <div className="border-t border-line pt-6">
      {confirm ? (
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span>Remove {contact.fullName} and their drafts?</span>
          <Button size="sm" variant="danger" loading={del.isPending} onClick={() => del.mutate(contact.id, { onSuccess: () => { toast.success('Contact removed'); onDeleted(); } })}>Remove</Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirm(false)}>Keep</Button>
        </div>
      ) : (
        <Button size="sm" variant="danger" onClick={() => setConfirm(true)}><Trash2 className="h-4 w-4" aria-hidden />Remove contact</Button>
      )}
      {contact.jobId && <p className="mt-4 text-sm"><Link className="underline decoration-line-strong underline-offset-4" to={`/referrals/jobs/${contact.jobId}`}>All people for this job</Link></p>}
    </div>
  );
}

function latestByKindTone(drafts: Draft[]): Record<string, Draft> {
  const out: Record<string, Draft> = {};
  for (const d of drafts) {   // newest first from the API
    const k = `${d.kind}:${d.tone}`;
    if (!out[k]) out[k] = d;
  }
  return out;
}

export function ContactRowBadge({ status }: { status: ContactStatus }) {
  return <Badge tone={status === 'REFERRED' ? 'fit' : status === 'NO_RESPONSE' ? 'outline' : status === 'NOT_CONTACTED' ? 'neutral' : 'referral'}>{STATUS_LABEL[status]}</Badge>;
}
