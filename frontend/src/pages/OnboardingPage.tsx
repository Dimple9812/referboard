import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { useProfile, useResumes, useSavePreferences, useSaveProfile, useUploadResume } from '@/api/queries';
import type { Profile } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Field, Input } from '@/components/ui/Field';
import { ChipsInput } from '@/components/ChipsInput';
import { PreferencesFields, usePreferencesDraft } from '@/components/PreferencesForm';
import { FileDrop } from '@/components/ResumeDrop';
import { Logo } from '@/components/Logo';
import { Spinner } from '@/components/ui/Spinner';
import { Badge } from '@/components/ui/Badge';
import { cn } from '@/lib/cn';

const STEPS = ['Your resume', 'Check what we read', 'What you’re looking for'];

export default function OnboardingPage() {
  const [step, setStep] = useState(0);
  const navigate = useNavigate();
  return (
    <div className="min-h-screen bg-paper">
      <header className="border-b border-line bg-surface">
        <div className="mx-auto flex max-w-3xl items-center justify-between px-5 py-4">
          <Logo />
          <Button variant="ghost" size="sm" onClick={() => navigate('/jobs')}>Skip for now</Button>
        </div>
      </header>
      <main className="mx-auto max-w-3xl px-5 py-10">
        <ol className="mb-10 flex gap-6 text-sm" aria-label="Setup steps">
          {STEPS.map((s, i) => (
            <li key={s} aria-current={i === step ? 'step' : undefined}
              className={cn('flex items-center gap-2', i === step ? 'font-medium text-ink' : 'text-slate')}>
              <span className={cn('grid h-6 w-6 place-items-center rounded-full text-xs tabular',
                i < step ? 'bg-ink text-white' : i === step ? 'border-2 border-ink' : 'border border-line-strong')}>{i + 1}</span>
              <span className="hidden sm:inline">{s}</span>
            </li>
          ))}
        </ol>
        {step === 0 && <UploadStep onDone={() => setStep(1)} />}
        {step === 1 && <ReviewStep onBack={() => setStep(0)} onDone={() => setStep(2)} />}
        {step === 2 && <PreferencesStep onBack={() => setStep(1)} onDone={() => navigate('/jobs', { replace: true })} />}
      </main>
    </div>
  );
}

function UploadStep({ onDone }: { onDone: () => void }) {
  const upload = useUploadResume();
  const resumes = useResumes();
  const latest = resumes.data?.[0];
  const parsing = upload.isSuccess && latest?.parseStatus === 'PENDING';
  const failed = upload.isSuccess && latest?.parseStatus === 'FAILED';

  useEffect(() => {
    if (upload.isSuccess && latest?.parseStatus === 'PARSED') onDone();
  }, [upload.isSuccess, latest?.parseStatus, onDone]);

  return (
    <section>
      <h1 className="text-2xl font-semibold">Start with your resume</h1>
      <p className="mt-2 max-w-xl text-slate">We read it to match you with jobs and to suggest people who can refer you. You'll check
        everything we read on the next step.</p>
      <div className="mt-8">
        <FileDrop label="Upload your resume" busy={upload.isPending || parsing} onFile={(f) => upload.mutate(f)} />
      </div>
      {(upload.isPending || parsing) && (
        <p className="mt-4 flex items-center gap-2 text-sm text-slate" role="status"><Spinner className="h-4 w-4" />
          {upload.isPending ? 'Uploading…' : 'Reading your resume. This takes a few seconds.'}</p>
      )}
      {failed && <p role="alert" className="mt-4 text-sm text-danger">We couldn't read that file{latest?.parseError ? `: ${latest.parseError}` : ''}. Try a PDF or DOCX with selectable text.</p>}
    </section>
  );
}

/** Lets the user fix the parse before it drives matching. Saves the full profile (PUT is a replacement). */
function ReviewStep({ onBack, onDone }: { onBack: () => void; onDone: () => void }) {
  const profile = useProfile();
  const save = useSaveProfile();
  const [draft, setDraft] = useState<Profile | null>(null);
  useEffect(() => {
    if (profile.data && !draft) setDraft(profile.data);
  }, [profile.data, draft]);

  if (!draft) return <div className="py-10"><Spinner /></div>;
  const missingDates = draft.experiences.some((e) => !e.startDate);

  const submit = () => {
    save.mutate({
      fullName: draft.fullName, email: draft.email, phone: draft.phone, headline: draft.headline, summary: draft.summary,
      currentTitle: draft.currentTitle, currentCompany: draft.currentCompany, location: draft.location,
      college: draft.college, highestDegree: draft.highestDegree, links: draft.links, certifications: draft.certifications,
      skills: draft.skills.map((s) => ({ name: s.displayName || s.name, years: s.years ?? null })),
      experiences: draft.experiences.map((e) => ({
        company: e.company, title: e.title, location: e.location, startDate: e.startDate, endDate: e.current ? null : e.endDate,
        current: e.current, description: e.description, bullets: e.bullets,
      })),
      educations: draft.educations.map((e) => ({
        institution: e.institution, degree: e.degree, fieldOfStudy: e.fieldOfStudy, startYear: e.startYear, endYear: e.endYear, grade: e.grade,
      })),
    }, { onSuccess: () => { toast.success('Profile saved'); onDone(); } });
  };

  return (
    <section>
      <h1 className="text-2xl font-semibold">Check what we read</h1>
      <p className="mt-2 max-w-xl text-slate">Fix anything that's wrong. Your matches and drafted messages only use what's here.</p>
      {draft.parseSource === 'HEURISTIC' && (
        <p className="mt-4 rounded border border-warn/30 bg-warn-soft px-4 py-3 text-sm text-ink">
          This was read without AI, so check it carefully, especially job titles and dates.</p>
      )}
      <div className="mt-8 grid gap-5 sm:grid-cols-2">
        <Field label="Full name">{(p) => <Input {...p} value={draft.fullName ?? ''} onChange={(e) => setDraft({ ...draft, fullName: e.target.value })} />}</Field>
        <Field label="Headline">{(p) => <Input {...p} value={draft.headline ?? ''} onChange={(e) => setDraft({ ...draft, headline: e.target.value })} />}</Field>
        <Field label="Current title">{(p) => <Input {...p} value={draft.currentTitle ?? ''} onChange={(e) => setDraft({ ...draft, currentTitle: e.target.value })} />}</Field>
        <Field label="Current company">{(p) => <Input {...p} value={draft.currentCompany ?? ''} onChange={(e) => setDraft({ ...draft, currentCompany: e.target.value })} />}</Field>
        <Field label="College" hint="Used to find alumni who can refer you.">{(p) => <Input {...p} value={draft.college ?? ''} onChange={(e) => setDraft({ ...draft, college: e.target.value })} />}</Field>
        <Field label="Location">{(p) => <Input {...p} value={draft.location ?? ''} onChange={(e) => setDraft({ ...draft, location: e.target.value })} />}</Field>
        <Field label="Skills" className="sm:col-span-2" hint="Press Enter to add. Only list skills you can talk about in an interview.">
          {(p) => <ChipsInput id={p.id} describedBy={p['aria-describedby']} max={100} value={draft.skills.map((s) => s.displayName || s.name)}
            onChange={(v) => setDraft({ ...draft, skills: v.map((name) => draft.skills.find((s) => (s.displayName || s.name) === name) ?? { name, displayName: name }) })} />}
        </Field>
      </div>

      <h2 className="mt-10 text-lg font-semibold">Experience</h2>
      {draft.totalExperienceYears != null && <p className="mt-1 text-sm text-slate">{draft.totalExperienceYears} years in total, from the dates below.</p>}
      <ul className="mt-4 divide-y divide-line border-y border-line">
        {draft.experiences.map((e, i) => (
          <li key={i} className="flex flex-col gap-3 py-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <p className="font-medium">{e.title} <span className="font-normal text-slate">at {e.company}</span></p>
              <p className="text-sm text-slate">{e.bullets.length} bullet{e.bullets.length === 1 ? '' : 's'}</p>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <Input type="month" aria-label={`Start of ${e.title} at ${e.company}`} className="w-40" value={e.startDate ?? ''}
                onChange={(ev) => setDraft({ ...draft, experiences: draft.experiences.map((x, j) => j === i ? { ...x, startDate: ev.target.value } : x) })} />
              <span className="text-slate">to</span>
              {e.current ? <Badge tone="fit">Present</Badge> : (
                <Input type="month" aria-label={`End of ${e.title} at ${e.company}`} className="w-40" value={e.endDate ?? ''}
                  onChange={(ev) => setDraft({ ...draft, experiences: draft.experiences.map((x, j) => j === i ? { ...x, endDate: ev.target.value } : x) })} />
              )}
            </div>
          </li>
        ))}
        {draft.experiences.length === 0 && <li className="py-4 text-sm text-slate">No roles found. You can add them later in Settings.</li>}
      </ul>
      {missingDates && <p className="mt-3 text-sm text-warn">Add a start month for every role so your years of experience are right.</p>}

      <div className="mt-10 flex justify-between">
        <Button variant="ghost" onClick={onBack}>Upload a different file</Button>
        <Button onClick={submit} loading={save.isPending} disabled={missingDates}>Save and continue</Button>
      </div>
    </section>
  );
}

function PreferencesStep({ onBack, onDone }: { onBack: () => void; onDone: () => void }) {
  const profile = useProfile();
  const save = useSavePreferences();
  const draft = usePreferencesDraft(profile.data);
  const submit = () => save.mutate(draft.value(), { onSuccess: () => { toast.success('Preferences saved'); onDone(); } });

  return (
    <section>
      <h1 className="text-2xl font-semibold">What you're looking for</h1>
      <p className="mt-2 max-w-xl text-slate">These shape your recommended jobs. You can change them any time in Settings.</p>
      <div className="mt-8"><PreferencesFields draft={draft} /></div>
      <div className="mt-10 flex justify-between">
        <Button variant="ghost" onClick={onBack}>Back</Button>
        <Button onClick={submit} loading={save.isPending}>Show my jobs</Button>
      </div>
    </section>
  );
}
