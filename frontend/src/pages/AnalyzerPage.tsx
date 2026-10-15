import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AlertTriangle, CheckCircle2, Download, XCircle } from 'lucide-react';
import { toast } from 'sonner';
import { errorCode, errorMessage } from '@/api/client';
import {
  downloadVersion, useAnalyses, useAnalysis, useAnalyze, useGenerateResume, useJob, useResumes, useUsage, useVersions,
  type ResumeChoice,
} from '@/api/queries';
import type { AnalysisReport, AtsCheck, ResumeSuggestion, ResumeVersion } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { ScoreBar } from '@/components/Bar';
import { FileDrop } from '@/components/ResumeDrop';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Checkbox, Field, Input, Select, Textarea } from '@/components/ui/Field';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/Tabs';
import { VERDICT_LABEL } from '@/lib/labels';
import { shortDate, timeAgo } from '@/lib/format';
import { cn } from '@/lib/cn';

export default function AnalyzerPage() {
  const { id } = useParams();
  return id ? <ReportView id={id} /> : <NewAnalysis />;
}

// ---------------------------------------------------------------------------------------------------------------

function NewAnalysis() {
  const [params] = useSearchParams();
  const jobId = params.get('jobId');
  const navigate = useNavigate();
  const analyze = useAnalyze();
  const usage = useUsage();
  const [tab, setTab] = useState(jobId ? 'job' : 'paste');
  const [jdText, setJdText] = useState('');
  const [jobTitle, setJobTitle] = useState('');
  const [company, setCompany] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [resume, setResume] = useState<ResumeChoice>({ source: 'PROFILE_RESUME' });
  const [limitHit, setLimitHit] = useState<string | null>(null);

  const go = (input: Parameters<typeof analyze.mutate>[0]) => analyze.mutate(input, {
    onSuccess: (r) => {
      if (r.reused) toast.message('Showing your analysis from earlier today. It didn’t count toward your limit.');
      navigate(`/analyzer/${r.id}`);
    },
    onError: (err) => {
      if (errorCode(err) === 'ANALYSIS_LIMIT') setLimitHit(errorMessage(err));
    },
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (tab === 'job' && jobId) go({ kind: 'job', jobId, resume });
    else if (tab === 'upload' && file) go({ kind: 'file', file, resume });
    else if (tab === 'paste') go({ kind: 'text', jdText, jobTitle, companyName: company, resume });
  };
  const ready = (tab === 'job' && !!jobId) || (tab === 'upload' && !!file) || (tab === 'paste' && jdText.trim().length >= 200);

  return (
    <>
      <PageHeader title="JD Analyzer">
        Compare your resume with a job description. You'll get a score with the reasons behind it, what's missing, and
        edits you can accept one by one.
      </PageHeader>

      <form onSubmit={submit} className="grid gap-10 lg:grid-cols-[1fr_18rem]">
        <div>
          <Tabs value={tab} onValueChange={setTab}>
            <TabsList aria-label="Job description source">
              {jobId && <TabsTrigger value="job">This job</TabsTrigger>}
              <TabsTrigger value="paste">Paste the description</TabsTrigger>
              <TabsTrigger value="upload">Upload a file</TabsTrigger>
            </TabsList>
            {jobId && <TabsContent value="job" className="pt-6"><JobChoice jobId={jobId} /></TabsContent>}
            <TabsContent value="paste" className="space-y-4 pt-6">
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Job title (optional)">{(p) => <Input {...p} value={jobTitle} onChange={(e) => setJobTitle(e.target.value)} />}</Field>
                <Field label="Company (optional)">{(p) => <Input {...p} value={company} onChange={(e) => setCompany(e.target.value)} />}</Field>
              </div>
              <Field label="Job description" hint={`${jdText.trim().length} characters. Paste the whole posting, including requirements.`}>
                {(p) => <Textarea {...p} rows={14} value={jdText} onChange={(e) => setJdText(e.target.value)} placeholder="Paste the full job description here" />}
              </Field>
            </TabsContent>
            <TabsContent value="upload" className="pt-6">
              <FileDrop label={file ? file.name : 'Upload the job description'} maxMb={2} onFile={setFile} />
            </TabsContent>
          </Tabs>
        </div>

        <aside className="space-y-6">
          <ResumePicker value={resume} onChange={setResume} />
          <UsageLine />
          {limitHit && <p role="alert" className="text-sm text-danger">{limitHit}</p>}
          <Button type="submit" size="lg" className="w-full" disabled={!ready || usage.data?.remaining === 0} loading={analyze.isPending}>
            {analyze.isPending ? 'Analysing' : 'Analyse'}
          </Button>
          {analyze.isPending && <p className="text-sm text-slate" role="status">Reading the job description and comparing it with your resume. This can take up to a minute.</p>}
        </aside>
      </form>

      <History />
    </>
  );
}

function JobChoice({ jobId }: { jobId: string }) {
  const job = useJob(jobId);
  if (job.isLoading) return <Skeleton className="h-12 w-2/3" />;
  if (!job.data) return <p className="text-sm text-slate">That job couldn't be loaded. Paste its description instead.</p>;
  return (
    <div className="rounded-lg border border-line bg-surface p-5">
      <p className="font-medium">{job.data.job.title}</p>
      <p className="text-sm text-slate">{job.data.job.companyName}{job.data.job.location ? `, ${job.data.job.location}` : ''}</p>
      <p className="mt-3 text-sm text-slate">We'll use the description from the posting.</p>
    </div>
  );
}

function ResumePicker({ value, onChange }: { value: ResumeChoice; onChange: (r: ResumeChoice) => void }) {
  const resumes = useResumes();
  const versions = useVersions();
  const parsed = (resumes.data ?? []).filter((r) => r.parseStatus === 'PARSED');
  const current = value.id ? `${value.source}:${value.id}` : '';
  if (resumes.isSuccess && parsed.length === 0) {
    return <p className="rounded border border-marigold/40 bg-marigold-soft p-4 text-sm">You need a resume first. <Link className="font-medium underline" to="/onboarding">Add your resume</Link></p>;
  }
  return (
    <Field label="Compare with" hint="Your primary resume, another upload, or a tailored version.">
      {(p) => (
        <Select {...p} value={current} onChange={(e) => {
          const [source, id] = e.target.value.split(':');
          onChange(e.target.value ? { source: source as ResumeChoice['source'], id } : { source: 'PROFILE_RESUME' });
        }}>
          <option value="">Primary resume</option>
          {parsed.length > 1 && <optgroup label="Uploaded resumes">{parsed.map((r) => <option key={r.id} value={`PROFILE_RESUME:${r.id}`}>{r.fileName}{r.primary ? ' (primary)' : ''}</option>)}</optgroup>}
          {(versions.data?.length ?? 0) > 0 && <optgroup label="Tailored versions">{versions.data!.map((v) => <option key={v.id} value={`VERSION:${v.id}`}>{v.name}</option>)}</optgroup>}
        </Select>
      )}
    </Field>
  );
}

function UsageLine() {
  const usage = useUsage();
  if (!usage.data || usage.data.unlimited) return null;
  const { remaining, limit, resetsOn } = usage.data;
  return (
    <p className={cn('text-sm', remaining === 0 ? 'text-danger' : 'text-slate')}>
      {remaining === 0 ? `You've used all ${limit} free analyses this month. They reset on ${shortDate(resetsOn)}.`
        : `${remaining} of ${limit} free analyses left this month. Re-checking the same job and resume within a day is free.`}
    </p>
  );
}

function History() {
  const list = useAnalyses();
  if (!list.data?.length) return null;
  return (
    <section className="mt-16">
      <h2 className="text-lg font-semibold">Earlier analyses</h2>
      <ul className="mt-4 divide-y divide-line border-y border-line">
        {list.data.map((a) => (
          <li key={a.id}>
            <Link to={`/analyzer/${a.id}`} className="flex items-center gap-4 py-3 hover:bg-surface">
              <span className="w-12 font-display text-xl font-semibold tabular text-fit">{a.score}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium">{a.jobTitle ?? 'Pasted job description'}{a.companyName ? `, ${a.companyName}` : ''}</span>
                <span className="text-sm text-slate">{timeAgo(a.createdAt)}{a.resumeName ? `, with ${a.resumeName}` : ''}</span>
              </span>
              <span className="hidden text-sm text-slate sm:block">{VERDICT_LABEL[a.verdict]}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------------------------

function ReportView({ id }: { id: string }) {
  const report = useAnalysis(id);
  if (report.isLoading) return <div className="space-y-4" role="status" aria-label="Loading analysis"><Skeleton className="h-10 w-1/2" /><Skeleton className="h-40 w-full" /></div>;
  if (report.isError) return <ErrorState error={report.error} onRetry={() => report.refetch()} />;
  return <Report r={report.data!} />;
}

function Report({ r }: { r: AnalysisReport }) {
  const title = r.job.title ?? 'Pasted job description';
  return (
    <article>
      <Link to="/analyzer" className="text-sm text-slate hover:text-ink">New analysis</Link>
      <header className="mt-4 border-b border-line pb-8">
        <h1 className="text-2xl font-semibold sm:text-3xl">{title}{r.job.companyName ? <span className="text-slate">, {r.job.companyName}</span> : null}</h1>
        <p className="mt-2 text-sm text-slate">Compared with {r.resume.name ?? 'your resume'} on {shortDate(r.createdAt)}.
          {r.job.jobId && <> <Link className="underline decoration-line-strong underline-offset-4 hover:text-ink" to={`/jobs/${r.job.jobId}`}>View the job</Link></>}</p>

        <div className="mt-8 flex flex-wrap items-end gap-x-12 gap-y-6">
          <div>
            <p className="text-sm text-slate">Resume match score</p>
            <p className="font-display text-6xl font-semibold leading-none tabular text-fit">{r.score}</p>
            <p className="mt-2 font-medium">{VERDICT_LABEL[r.verdict]}</p>
          </div>
          {r.projectedScore > r.score && (
            <div className="pb-1">
              <p className="text-sm text-slate">With the suggested edits</p>
              <p className="font-display text-3xl font-semibold tabular text-ink">{r.projectedScore}</p>
              {r.projectedScoreWithConfirmed > r.projectedScore && (
                <p className="mt-1 text-sm text-slate">{r.projectedScoreWithConfirmed} if you also add the skills you confirm you have</p>
              )}
            </div>
          )}
        </div>
        {r.notices.length > 0 && <ul className="mt-6 space-y-1 text-sm text-slate">{r.notices.map((n) => <li key={n}>{n}</li>)}</ul>}
      </header>

      <div className="mt-10 grid gap-12 lg:grid-cols-[1fr_20rem]">
        <div className="space-y-12">
          <Suggestions r={r} />
        </div>
        <aside className="space-y-10">
          <section aria-labelledby="breakdown">
            <h2 id="breakdown" className="text-lg font-semibold">How the score is made</h2>
            <div className="mt-4 space-y-5">
              {r.breakdown.map((c) => (
                <ScoreBar key={c.key} label={`${c.label} (${c.weight}%)`} value={c.score} detail={c.score == null ? `${c.detail} Not counted.` : c.detail} />
              ))}
            </div>
          </section>
          <Skills r={r} />
          <Ats checks={r.atsChecks} />
        </aside>
      </div>
    </article>
  );
}

function Skills({ r }: { r: AnalysisReport }) {
  const { required, preferred, weaklyEvidenced } = r.missingSkills;
  return (
    <section aria-labelledby="skills">
      <h2 id="skills" className="text-lg font-semibold">Skills</h2>
      {r.matchedSkills.length > 0 && (
        <div className="mt-4">
          <h3 className="text-sm font-medium">On your resume</h3>
          <ul className="mt-2 space-y-1.5 text-sm">
            {r.matchedSkills.map((s) => (
              <li key={s.name} className="flex items-baseline justify-between gap-3">
                <span>{s.label}{s.required ? '' : <span className="text-slate"> (preferred)</span>}</span>
                <span className="text-right text-xs text-slate">{s.evidence === 'STRONG' ? (s.usedIn ? `Used at ${s.usedIn.split(' at ').pop()}` : 'Used in a role') : 'Listed only'}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {(required.length > 0 || preferred.length > 0) && (
        <div className="mt-5">
          <h3 className="text-sm font-medium">Missing</h3>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {required.map((s) => <Badge key={s.name} tone="danger">{s.label}</Badge>)}
            {preferred.map((s) => <Badge key={s.name} tone="outline">{s.label} (preferred)</Badge>)}
          </div>
        </div>
      )}
      {weaklyEvidenced.length > 0 && (
        <p className="mt-4 text-sm text-slate">Listed but not shown in any role: {weaklyEvidenced.map((s) => s.label).join(', ')}.</p>
      )}
    </section>
  );
}

const ATS_ICON = { PASS: CheckCircle2, WARN: AlertTriangle, FAIL: XCircle } as const;
const ATS_COLOR = { PASS: 'text-fit', WARN: 'text-warn', FAIL: 'text-danger' } as const;

function Ats({ checks }: { checks: AtsCheck[] }) {
  const sorted = [...checks].sort((a, b) => ['FAIL', 'WARN', 'PASS'].indexOf(a.status) - ['FAIL', 'WARN', 'PASS'].indexOf(b.status));
  return (
    <section aria-labelledby="ats">
      <h2 id="ats" className="text-lg font-semibold">ATS checks</h2>
      <p className="mt-1 text-sm text-slate">What applicant tracking systems trip on.</p>
      <ul className="mt-4 space-y-3">
        {sorted.map((c) => {
          const Icon = ATS_ICON[c.status];
          return (
            <li key={c.id} className="flex gap-2.5 text-sm">
              <Icon className={cn('mt-0.5 h-4 w-4 shrink-0', ATS_COLOR[c.status])} aria-label={c.status.toLowerCase()} />
              <span><span className="font-medium">{c.label}.</span> <span className="text-slate">{c.detail}</span></span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------------------------

type Decision = { accepted: boolean; confirmed: boolean };

function Suggestions({ r }: { r: AnalysisReport }) {
  const edits = r.suggestions.filter((s) => s.applicable);
  const advice = r.suggestions.filter((s) => !s.applicable);
  // Safe edits start accepted; anything that adds a claim starts unaccepted and needs a confirmation.
  // Mechanical edits (skill order, the posting's skill names) start ticked; anything that replaces your own sentences doesn't.
  const initial = useMemo(() => Object.fromEntries(edits.map((s) => [s.id, {
    accepted: !s.requiresConfirmation && (s.type === 'SKILLS_REORDER' || s.type === 'SKILL_WORDING' || !s.before), confirmed: false,
  }])), [r.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const [decisions, setDecisions] = useState<Record<string, Decision>>(initial);
  useEffect(() => setDecisions(initial), [initial]);
  const set = (id: string, d: Partial<Decision>) => setDecisions((x) => ({ ...x, [id]: { ...x[id], ...d } }));

  return (
    <>
      {advice.length > 0 && (
        <section aria-labelledby="advice">
          <h2 id="advice" className="text-lg font-semibold">What to know first</h2>
          <ul className="mt-4 space-y-4">
            {advice.map((s) => (
              <li key={s.id} className="border-l-2 border-ink pl-4">
                <p className="font-medium">{s.title}</p>
                <p className="mt-1 text-sm leading-relaxed text-ink-soft">{s.rationale}</p>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="edits">
        <h2 id="edits" className="text-lg font-semibold">Suggested edits</h2>
        {edits.length === 0 ? (
          <p className="mt-2 text-slate">No edits to suggest. Your resume already covers what this job asks for.</p>
        ) : (
          <>
            <p className="mt-1 text-sm text-slate">Accept the ones that are true for you. Edits that add something new need you to confirm it.</p>
            <ul className="mt-5 divide-y divide-line border-y border-line">
              {edits.map((s) => <EditRow key={s.id} s={s} d={decisions[s.id] ?? { accepted: false, confirmed: false }} onChange={(d) => set(s.id, d)} />)}
            </ul>
          </>
        )}
      </section>

      <Generate r={r} decisions={decisions} />
    </>
  );
}

function EditRow({ s, d, onChange }: { s: ResumeSuggestion; d: Decision; onChange: (d: Partial<Decision>) => void }) {
  return (
    <li className="py-5">
      <div className="flex items-start justify-between gap-4">
        <Checkbox checked={d.accepted} onChange={(e) => onChange({ accepted: e.target.checked })}
          label={s.title} description={s.rationale} />
        <div className="flex shrink-0 flex-col items-end gap-1">
          {s.impact != null && s.impact > 0 && <span className="text-sm font-semibold tabular text-fit">+{s.impact}</span>}
          {s.priority === 'HIGH' && <Badge tone="neutral">High impact</Badge>}
        </div>
      </div>
      {(s.before || s.after) && (
        <div className="ml-7 mt-3 grid gap-2 text-sm sm:grid-cols-2">
          {s.before && <div className="rounded bg-paper p-3 text-slate"><span className="sr-only">Before: </span><del className="no-underline">{s.before}</del></div>}
          {s.after && <div className={cn('rounded border p-3 text-ink', s.before ? 'border-fit/30 bg-fit-soft' : 'border-line bg-surface sm:col-span-2')}><span className="sr-only">After: </span>{s.after}</div>}
        </div>
      )}
      {s.requiresConfirmation && (
        <div className="ml-7 mt-3 rounded border border-marigold/50 bg-marigold-soft p-3">
          <Checkbox checked={d.confirmed} onChange={(e) => onChange({ confirmed: e.target.checked, accepted: e.target.checked || d.accepted })}
            label={s.confirmationLabel ?? 'Add only if you have this experience'}
            description="Tick this to confirm it's true. It won't be added otherwise." />
        </div>
      )}
    </li>
  );
}

function Generate({ r, decisions }: { r: AnalysisReport; decisions: Record<string, Decision> }) {
  const generate = useGenerateResume();
  const [format, setFormat] = useState<'DOCX' | 'PDF'>('DOCX');
  const [name, setName] = useState('');
  const [created, setCreated] = useState<ResumeVersion | null>(null);
  const accepted = Object.values(decisions).filter((d) => d.accepted).length;
  const unconfirmed = r.suggestions.filter((s) => s.requiresConfirmation && decisions[s.id]?.accepted && !decisions[s.id]?.confirmed);

  const submit = () => generate.mutate({
    analysisId: r.id, format, name: name.trim() || undefined,
    decisions: Object.entries(decisions).map(([suggestionId, d]) => ({ suggestionId, accepted: d.accepted, confirmed: d.confirmed })),
  }, { onSuccess: (v) => { setCreated(v); toast.success('Tailored resume created'); } });

  return (
    <section aria-labelledby="generate" className="rounded-lg border border-line bg-surface p-6">
      <h2 id="generate" className="text-lg font-semibold">Create a tailored resume</h2>
      {created ? (
        <div className="mt-3">
          <p className="text-ink-soft">{created.name}{created.scoreAfter != null && <> scores <span className="font-semibold text-fit tabular">{created.scoreAfter}</span> for this job</>}</p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button onClick={() => downloadVersion(created.id, 'DOCX')}><Download className="h-4 w-4" aria-hidden />Download DOCX</Button>
            <Button variant="outline" onClick={() => downloadVersion(created.id, 'PDF')}><Download className="h-4 w-4" aria-hidden />Download PDF</Button>
          </div>
          {r.job.jobId && <p className="mt-4 text-sm text-slate">It's attached to this job on your <Link className="underline underline-offset-4" to="/tracker">tracker</Link>.</p>}
        </div>
      ) : (
        <>
          <p className="mt-1 text-sm text-slate">{accepted === 0 ? 'Your resume as it is' : `Your resume with the ${accepted} accepted edit${accepted === 1 ? '' : 's'}`}, in a clean single-column layout that applicant tracking systems read well.</p>
          <div className="mt-5 grid gap-4 sm:grid-cols-[1fr_10rem]">
            <Field label="Name (optional)">{(p) => <Input {...p} value={name} onChange={(e) => setName(e.target.value)} placeholder={r.job.companyName ? `${r.job.companyName} resume` : 'Tailored resume'} />}</Field>
            <Field label="Format">{(p) => <Select {...p} value={format} onChange={(e) => setFormat(e.target.value as 'DOCX' | 'PDF')}><option value="DOCX">Word (DOCX)</option><option value="PDF">PDF</option></Select>}</Field>
          </div>
          {unconfirmed.length > 0 && <p className="mt-3 text-sm text-danger">Confirm or untick: {unconfirmed.map((s) => s.title).join('; ')}.</p>}
          <Button className="mt-5" onClick={submit} loading={generate.isPending} disabled={unconfirmed.length > 0}>Create tailored resume</Button>
        </>
      )}
    </section>
  );
}
