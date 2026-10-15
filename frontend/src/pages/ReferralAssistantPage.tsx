import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ExternalLink, Plus } from 'lucide-react';
import { toast } from 'sonner';
import { errorMessage } from '@/api/client';
import { useAddContact, useJobContacts, useSuggestions } from '@/api/queries';
import type { PersonType } from '@/api/types';
import { ReferralThread, StatusThread } from '@/components/ReferralThread';
import { ContactRowBadge, ContactSheet } from '@/components/ContactPanel';
import { Button } from '@/components/ui/Button';
import { Field, Input, Select, Textarea } from '@/components/ui/Field';
import { Modal } from '@/components/ui/Sheet';
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { PERSON_LABEL } from '@/lib/labels';

export default function ReferralAssistantPage() {
  const { jobId = '' } = useParams();
  const suggestions = useSuggestions(jobId);
  const contacts = useJobContacts(jobId);
  const [adding, setAdding] = useState<PersonType | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const s = suggestions.data;
  const first = contacts.data?.[0];

  if (suggestions.isError) return <ErrorState error={suggestions.error} onRetry={() => suggestions.refetch()} />;

  return (
    <>
      <Link to={`/jobs/${jobId}`} className="text-sm text-slate hover:text-ink">Back to the job</Link>
      <header className="mt-4 grid gap-8 border-b border-line pb-10 lg:grid-cols-[1fr_24rem] lg:items-end">
        <div>
          <h1 className="text-2xl font-semibold sm:text-3xl">Get referred{s ? ` at ${s.companyName}` : ''}</h1>
          {s ? <p className="mt-2 text-slate">For {s.jobTitle}. Find someone below, add them here, and we'll draft what to send.</p> : <Skeleton className="mt-3 h-4 w-1/2" />}
        </div>
        <ReferralThread className="max-w-sm" nodes={[
          { label: 'You', you: true },
          { label: first?.fullName ?? 'Someone there', sub: first ? PERSON_LABEL[first.personType] : 'Find them below' },
          { label: s?.companyName ?? 'The company', sub: 'Referral' },
        ]} />
      </header>

      <div className="mt-10 grid gap-12 lg:grid-cols-[1fr_22rem]">
        <section aria-labelledby="who">
          <h2 id="who" className="text-lg font-semibold">Who to reach</h2>
          {s && <p className="mt-1 max-w-2xl text-sm text-slate">{s.notice}</p>}
          {!s ? <div className="mt-4"><ListSkeleton rows={4} /></div> : (
            <ul className="mt-5 divide-y divide-line border-y border-line">
              {s.people.map((p) => (
                <li key={`${p.personType}-${p.searchKeywords}`} className="py-5">
                  <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                    <div className="max-w-xl">
                      <h3 className="font-sans text-base font-semibold">{p.title}</h3>
                      <p className="mt-1 text-sm text-ink-soft">{p.why}</p>
                      <p className="mt-1 text-sm text-slate">{p.tip}</p>
                    </div>
                    <div className="flex shrink-0 gap-2">
                      <Button size="sm" variant="outline" asChild>
                        <a href={p.linkedinSearchUrl} target="_blank" rel="noopener noreferrer">Search LinkedIn <ExternalLink className="h-3.5 w-3.5" aria-hidden /></a>
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => setAdding(p.personType)}><Plus className="h-4 w-4" aria-hidden />Add</Button>
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section aria-labelledby="mine">
          <div className="flex items-center justify-between">
            <h2 id="mine" className="text-lg font-semibold">Your contacts</h2>
            <Button size="sm" variant="ghost" onClick={() => setAdding('OTHER')}><Plus className="h-4 w-4" aria-hidden />Add</Button>
          </div>
          {contacts.isLoading ? <div className="mt-4"><ListSkeleton rows={2} /></div>
            : !contacts.data?.length ? (
              <p className="mt-4 text-sm text-slate">No one yet. Search LinkedIn for one of the groups on the left, then add the people you choose.</p>
            ) : (
              <ul className="mt-4 divide-y divide-line border-y border-line">
                {contacts.data.map((c) => (
                  <li key={c.id}>
                    <button onClick={() => setOpen(c.id)} className="w-full py-4 text-left hover:bg-surface">
                      <span className="flex items-center justify-between gap-3">
                        <span className="font-medium">{c.fullName}</span>
                        <ContactRowBadge status={c.status} />
                      </span>
                      <span className="mt-1 flex items-center justify-between gap-3 text-sm text-slate">
                        <span>{PERSON_LABEL[c.personType]}</span>
                        <StatusThread status={c.status} />
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
        </section>
      </div>

      <AddContactModal jobId={jobId} personType={adding} onClose={() => setAdding(null)} onAdded={(id) => { setAdding(null); setOpen(id); }} />
      <ContactSheet contactId={open} onClose={() => setOpen(null)} />
    </>
  );
}

const TYPES: PersonType[] = ['RECRUITER', 'ENGINEERING_MANAGER', 'TEAM_MEMBER', 'ALUMNI', 'EX_COLLEAGUE', 'OTHER'];

function AddContactModal({ jobId, personType, onClose, onAdded }: { jobId: string; personType: PersonType | null; onClose: () => void; onAdded: (id: string) => void }) {
  const add = useAddContact(jobId);
  const [form, setForm] = useState({ fullName: '', profileUrl: '', headline: '', notes: '', personType: 'OTHER' as PersonType });
  const [error, setError] = useState<string | null>(null);
  const [lastType, setLastType] = useState<PersonType | null>(null);
  if (personType && personType !== lastType) {
    setLastType(personType);
    setForm((f) => ({ ...f, personType }));
  }
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    add.mutate({ fullName: form.fullName.trim(), profileUrl: form.profileUrl.trim() || undefined, headline: form.headline.trim() || undefined,
      notes: form.notes.trim() || undefined, personType: form.personType }, {
      onSuccess: (c) => { toast.success(`${c.fullName} added`); setForm({ fullName: '', profileUrl: '', headline: '', notes: '', personType: 'OTHER' }); setLastType(null); onAdded(c.id); },
      onError: (err) => setError(errorMessage(err)),
    });
  };
  return (
    <Modal open={!!personType} onOpenChange={(o) => { if (!o) { setLastType(null); onClose(); } }} title="Add someone you found"
      description="Copy their name and headline from LinkedIn. We never visit their profile.">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Name">{(p) => <Input {...p} required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />}</Field>
        <Field label="Who they are">
          {(p) => <Select {...p} value={form.personType} onChange={(e) => setForm({ ...form, personType: e.target.value as PersonType })}>
            {TYPES.map((t) => <option key={t} value={t}>{PERSON_LABEL[t]}</option>)}</Select>}
        </Field>
        <Field label="Headline (optional)" hint="Helps the drafts: e.g. “SDE II at Tidepool | ex-Flipkart”.">{(p) => <Input {...p} value={form.headline} onChange={(e) => setForm({ ...form, headline: e.target.value })} />}</Field>
        <Field label="Profile link (optional)">{(p) => <Input {...p} type="url" placeholder="https://www.linkedin.com/in/…" value={form.profileUrl} onChange={(e) => setForm({ ...form, profileUrl: e.target.value })} />}</Field>
        <Field label="Notes (optional)">{(p) => <Textarea {...p} rows={2} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} className="min-h-0" />}</Field>
        {error && <p role="alert" className="text-sm text-danger">{error}</p>}
        <div className="flex justify-end gap-2"><Button type="button" variant="ghost" onClick={onClose}>Cancel</Button><Button type="submit" loading={add.isPending} disabled={!form.fullName.trim()}>Add contact</Button></div>
      </form>
    </Modal>
  );
}
