import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { useContacts, usePipeline, useReminders, useUpdateReminder } from '@/api/queries';
import type { ContactStatus } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { ContactRowBadge, ContactSheet } from '@/components/ContactPanel';
import { StatusThread } from '@/components/ReferralThread';
import { CopyButton } from '@/components/CopyButton';
import { Button } from '@/components/ui/Button';
import { ListSkeleton } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { PERSON_LABEL, STATUS_LABEL } from '@/lib/labels';
import { timeAgo } from '@/lib/format';
import { cn } from '@/lib/cn';

const FILTERS: (ContactStatus | 'ALL')[] = ['ALL', 'NOT_CONTACTED', 'REQUEST_SENT', 'CONNECTED', 'REFERRAL_ASKED', 'REFERRED', 'NO_RESPONSE'];

export default function ReferralsPage() {
  const { contactId } = useParams();
  const navigate = useNavigate();
  const [filter, setFilter] = useState<ContactStatus | 'ALL'>('ALL');
  const contacts = useContacts(filter === 'ALL' ? undefined : [filter]);
  const pipeline = usePipeline();

  return (
    <>
      <PageHeader title="Referrals">Everyone you're reaching out to, and where each conversation stands.</PageHeader>
      <DueReminders />

      <div role="tablist" aria-label="Filter by status" className="flex gap-5 overflow-x-auto border-b border-line">
        {FILTERS.map((f) => {
          const n = f === 'ALL' ? pipeline.data?.total : pipeline.data?.byStatus[f];
          return (
            <button key={f} role="tab" aria-selected={filter === f} onClick={() => setFilter(f)}
              className={cn('-mb-px flex shrink-0 items-center gap-1.5 border-b-2 pb-2.5 text-sm font-medium',
                filter === f ? 'border-ink text-ink' : 'border-transparent text-slate hover:text-ink')}>
              {f === 'ALL' ? 'All' : STATUS_LABEL[f]}{n != null && <span className="tabular text-slate">{n}</span>}
            </button>
          );
        })}
      </div>

      {contacts.isLoading ? <div className="mt-2"><ListSkeleton /></div>
        : contacts.isError ? <div className="mt-4"><ErrorState error={contacts.error} onRetry={() => contacts.refetch()} /></div>
          : !contacts.data?.items.length ? (
            filter === 'ALL' ? (
              <EmptyState title="No contacts yet" action={<Button asChild><Link to="/jobs">Find a job to start with</Link></Button>}>
                Open a job and choose “Find people to ask for a referral”. We'll show you who to look for on LinkedIn.
              </EmptyState>
            ) : <EmptyState title={`No one is at “${STATUS_LABEL[filter as ContactStatus]}”`} />
          ) : (
            <ul className="divide-y divide-line border-b border-line">
              {contacts.data.items.map((c) => (
                <li key={c.id}>
                  <button onClick={() => navigate(`/referrals/contacts/${c.id}`)} className="grid w-full grid-cols-1 gap-2 py-4 text-left hover:bg-surface sm:grid-cols-[1fr_12rem_9rem] sm:items-center sm:gap-6">
                    <span>
                      <span className="block font-medium">{c.fullName}</span>
                      <span className="text-sm text-slate">{PERSON_LABEL[c.personType]}{c.companyName ? ` at ${c.companyName}` : ''}{c.jobTitle ? `, for ${c.jobTitle}` : ''}</span>
                    </span>
                    <span className="flex items-center gap-3"><StatusThread status={c.status} /><ContactRowBadge status={c.status} /></span>
                    <span className="text-sm text-slate sm:text-right">{timeAgo(c.updatedAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}

      <ContactSheet contactId={contactId ?? null} onClose={() => navigate('/referrals')} />
    </>
  );
}

function DueReminders() {
  const reminders = useReminders();
  const update = useUpdateReminder();
  const due = reminders.data ?? [];
  if (!due.length) return null;
  return (
    <section aria-labelledby="due" className="mb-10 rounded-lg border border-marigold/50 bg-marigold-soft p-5">
      <h2 id="due" className="text-base font-semibold">Due now</h2>
      <ul className="mt-3 divide-y divide-marigold/30">
        {due.map((r) => (
          <li key={r.id} className="py-4">
            <p className="font-medium">{r.message}</p>
            {r.draftedFollowUp && (
              <details className="mt-2">
                <summary className="cursor-pointer text-sm text-ink-soft">See the drafted message</summary>
                <p className="mt-2 whitespace-pre-line rounded bg-surface p-3 text-sm">{r.draftedFollowUp}</p>
              </details>
            )}
            <div className="mt-3 flex flex-wrap gap-2">
              {r.draftedFollowUp && <CopyButton text={r.draftedFollowUp} label="Copy message" />}
              <Button size="sm" variant="outline" asChild><Link to={`/referrals/contacts/${r.contactId}`}>Open contact</Link></Button>
              <Button size="sm" variant="ghost" onClick={() => update.mutate({ id: r.id, status: 'DONE' }, { onSuccess: () => toast.success('Marked done') })}>Done</Button>
              <Button size="sm" variant="ghost" onClick={() => update.mutate({ id: r.id, status: 'DISMISSED' })}>Dismiss</Button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
