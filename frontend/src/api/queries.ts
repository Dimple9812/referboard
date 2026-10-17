import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type {
  AnalysisReport, AnalysisSummary, AppNotification, Board, CardDetail, Contact, ContactDetail, ContactPage, CoverLetter,
  LetterLength,
  AdminMetrics, BillingStatus, Checkout, Confidence, ContactStatus, DraftSet, ExtensionConnection, Insights, InterviewPrep,
  PlansResponse, PrepItem, PrepSummary, EmailPreferences, JobDetail, JobPage, JobSearch, NotificationPage, PersonType, Pipeline,
  Preferences, Profile, Reminder, Resume, ResumeVersion, Stage, Suggestions, Usage,
} from './types';

const get = <T,>(url: string, params?: object) => api.get<T>(url, { params }).then((r) => r.data);

export const keys = {
  profile: ['profile'] as const,
  resumes: ['resumes'] as const,
  jobs: (s: JobSearch) => ['jobs', s] as const,
  recommended: ['jobs', 'recommended'] as const,
  job: (id: string) => ['job', id] as const,
  suggestions: (jobId: string) => ['referrals', 'suggestions', jobId] as const,
  jobContacts: (jobId: string) => ['referrals', 'job', jobId] as const,
  contacts: (status?: ContactStatus[]) => ['referrals', 'contacts', status ?? []] as const,
  contact: (id: string) => ['referrals', 'contact', id] as const,
  pipeline: ['referrals', 'pipeline'] as const,
  reminders: ['referrals', 'reminders'] as const,
  board: ['board'] as const,
  card: (id: string) => ['card', id] as const,
  cardByJob: (jobId: string) => ['card', 'job', jobId] as const,
  analyses: ['analyses'] as const,
  analysis: (id: string) => ['analysis', id] as const,
  usage: ['analyses', 'usage'] as const,
  versions: ['versions'] as const,
  letters: (jobId?: string) => ['letters', jobId ?? 'all'] as const,
  letter: (id: string) => ['letter', id] as const,
  extensions: ['extensions'] as const,
  preps: (jobId?: string) => ['preps', jobId ?? 'all'] as const,
  prep: (id: string) => ['prep', id] as const,
  prepUsage: ['preps', 'usage'] as const,
  billing: ['billing'] as const,
  plans: ['billing', 'plans'] as const,
  insights: ['insights'] as const,
  adminMetrics: ['insights', 'admin'] as const,
  notifications: ['notifications'] as const,
  unread: ['notifications', 'unread'] as const,
  emailPrefs: ['notifications', 'prefs'] as const,
};

// ---------- profile ----------
export const useProfile = () => useQuery({ queryKey: keys.profile, queryFn: () => get<Profile>('/api/profile') });

export const useResumes = (poll = false) =>
  useQuery({
    queryKey: keys.resumes,
    queryFn: () => get<Resume[]>('/api/profile/resumes'),
    // While a resume is being read, check every 2s.
    refetchInterval: (q) => (poll || q.state.data?.some((r) => r.parseStatus === 'PENDING') ? 2000 : false),
  });

export function useUploadResume() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append('file', file);
      return api.post<Resume>('/api/profile/resumes', form).then((r) => r.data);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.resumes }),
  });
}

export function useMakePrimary() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post(`/api/profile/resumes/${id}/primary`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.resumes });
      qc.invalidateQueries({ queryKey: keys.profile });
    },
  });
}

export function useSavePreferences() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: Preferences) => api.put<Preferences>('/api/profile/preferences', p).then((r) => r.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.profile });
      qc.invalidateQueries({ queryKey: ['jobs'] });
    },
  });
}

export function useSaveProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: unknown) => api.put<Profile>('/api/profile', body).then((r) => r.data),
    onSuccess: (p) => {
      qc.setQueryData(keys.profile, p);
      qc.invalidateQueries({ queryKey: ['jobs'] });
    },
  });
}

// ---------- jobs ----------
export const useJobSearch = (search: JobSearch, enabled = true) =>
  useInfiniteQuery({
    queryKey: keys.jobs(search),
    queryFn: ({ pageParam }) => get<JobPage>('/api/jobs', { ...search, page: pageParam, size: 20 }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
    placeholderData: keepPreviousData,
    enabled,
  });

export const useRecommended = (enabled = true) =>
  useInfiniteQuery({
    queryKey: keys.recommended,
    queryFn: ({ pageParam }) => get<JobPage>('/api/jobs/recommended', { page: pageParam, size: 20 }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
    enabled,
  });

export const useJob = (id: string) => useQuery({ queryKey: keys.job(id), queryFn: () => get<JobDetail>(`/api/jobs/${id}`) });

// ---------- referrals ----------
export const useSuggestions = (jobId: string) =>
  useQuery({ queryKey: keys.suggestions(jobId), queryFn: () => get<Suggestions>(`/api/referrals/jobs/${jobId}/suggestions`) });

export const useJobContacts = (jobId: string) =>
  useQuery({ queryKey: keys.jobContacts(jobId), queryFn: () => get<Contact[]>(`/api/referrals/jobs/${jobId}/contacts`) });

export const useContacts = (status?: ContactStatus[]) =>
  useQuery({
    queryKey: keys.contacts(status),
    queryFn: () => api.get<ContactPage>('/api/referrals/contacts', {
      params: { status, size: 100 }, paramsSerializer: { indexes: null },
    }).then((r) => r.data),
  });

export const useContact = (id: string | null) =>
  useQuery({ queryKey: keys.contact(id ?? ''), queryFn: () => get<ContactDetail>(`/api/referrals/contacts/${id}`), enabled: !!id });

export const usePipeline = () => useQuery({ queryKey: keys.pipeline, queryFn: () => get<Pipeline>('/api/referrals/pipeline') });

export const useReminders = () => useQuery({ queryKey: keys.reminders, queryFn: () => get<Reminder[]>('/api/referrals/reminders') });

function invalidateReferrals(qc: ReturnType<typeof useQueryClient>) {
  qc.invalidateQueries({ queryKey: ['referrals'] });
  qc.invalidateQueries({ queryKey: keys.board });   // referral progress moves tracker cards
}

export function useAddContact(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { fullName: string; profileUrl?: string; headline?: string; personType: PersonType; notes?: string }) =>
      api.post<Contact>(`/api/referrals/jobs/${jobId}/contacts`, body).then((r) => r.data),
    onSuccess: () => invalidateReferrals(qc),
  });
}

export function useChangeContactStatus() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: ContactStatus }) =>
      api.post<Contact>(`/api/referrals/contacts/${id}/status`, { status }).then((r) => r.data),
    onSuccess: () => {
      invalidateReferrals(qc);
      // The tracker updates from an event a moment later.
      setTimeout(() => qc.invalidateQueries({ queryKey: keys.board }), 1500);
    },
  });
}

export function useDeleteContact() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/api/referrals/contacts/${id}`),
    onSuccess: () => invalidateReferrals(qc),
  });
}

export function useGenerateDrafts() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ contactId, type, context }: { contactId: string; type: 'INTRO' | 'FOLLOW_UP'; context?: string }) =>
      api.post<DraftSet>(`/api/referrals/contacts/${contactId}/drafts`, { type, context: context || undefined }).then((r) => r.data),
    onSuccess: (_d, v) => qc.invalidateQueries({ queryKey: keys.contact(v.contactId) }),
  });
}

export const markDraftCopied = (draftId: string) => api.post(`/api/referrals/drafts/${draftId}/copied`).catch(() => undefined);

export function useUpdateReminder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: 'DONE' | 'DISMISSED' }) =>
      api.patch<Reminder>(`/api/referrals/reminders/${id}`, { status }).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['referrals'] }),
  });
}

// ---------- tracker ----------
export const useBoard = () => useQuery({ queryKey: keys.board, queryFn: () => get<Board>('/api/applications/board') });

export const useCard = (id: string | null) =>
  useQuery({ queryKey: keys.card(id ?? ''), queryFn: () => get<CardDetail>(`/api/applications/${id}`), enabled: !!id });

export const useCardByJob = (jobId: string) =>
  useQuery({
    queryKey: keys.cardByJob(jobId),
    queryFn: () => api.get(`/api/applications/by-job/${jobId}`, { validateStatus: (s) => s === 200 || s === 404 })
      .then((r) => (r.status === 404 ? null : (r.data as CardDetail['card']))),
  });

export function useTrackJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { jobId?: string; jobTitle?: string; companyName?: string; location?: string; applyUrl?: string; stage?: Stage }) =>
      api.post('/api/applications', body).then((r) => r.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.board });
      qc.invalidateQueries({ queryKey: ['card'] });
    },
  });
}

export function useMoveCard() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, stage, index }: { id: string; stage: Stage; index: number }) =>
      api.post(`/api/applications/${id}/move`, { stage, index }).then((r) => r.data),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.board });
      qc.invalidateQueries({ queryKey: ['card'] });
    },
  });
}

export function useUpdateCard() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string; notes?: string; nextAction?: string; nextActionAt?: string; clearNextAction?: boolean; appliedAt?: string }) =>
      api.patch(`/api/applications/${id}`, body).then((r) => r.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.board });
      qc.invalidateQueries({ queryKey: ['card'] });
    },
  });
}

export function useAddCardNote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, text }: { id: string; text: string }) => api.post<CardDetail>(`/api/applications/${id}/notes`, { text }).then((r) => r.data),
    onSuccess: (d) => qc.setQueryData(keys.card(d.card.id), d),
  });
}

export function useDeleteCard() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/api/applications/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.board });
      qc.invalidateQueries({ queryKey: ['card'] });
    },
  });
}

// ---------- analyzer ----------
export const useAnalyses = (jobId?: string) =>
  useQuery({
    queryKey: [...keys.analyses, jobId ?? 'all'],
    queryFn: () => get<{ items: AnalysisSummary[] }>('/api/analyses', { jobId, size: 20 }).then((d) => d.items),
  });

export const useAnalysis = (id: string | undefined) =>
  useQuery({ queryKey: keys.analysis(id ?? ''), queryFn: () => get<AnalysisReport>(`/api/analyses/${id}`), enabled: !!id });

export const useUsage = () => useQuery({ queryKey: keys.usage, queryFn: () => get<Usage>('/api/analyses/usage') });

export type ResumeChoice = { source: 'PROFILE_RESUME' | 'VERSION'; id?: string };

export function useAnalyze() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input:
      | { kind: 'text'; jdText: string; jobTitle?: string; companyName?: string; resume: ResumeChoice }
      | { kind: 'job'; jobId: string; resume: ResumeChoice }
      | { kind: 'file'; file: File; resume: ResumeChoice }) => {
      if (input.kind === 'file') {
        const form = new FormData();
        form.append('file', input.file);
        form.append('resumeSource', input.resume.source);
        if (input.resume.id) form.append('resumeId', input.resume.id);
        return api.post<AnalysisReport>('/api/analyses/upload', form).then((r) => r.data);
      }
      const body = input.kind === 'job'
        ? { jobId: input.jobId, resume: input.resume }
        : { jdText: input.jdText, jobTitle: input.jobTitle || undefined, companyName: input.companyName || undefined, resume: input.resume };
      return api.post<AnalysisReport>('/api/analyses', body).then((r) => r.data);
    },
    onSuccess: (report) => {
      qc.setQueryData(keys.analysis(report.id), report);
      qc.invalidateQueries({ queryKey: keys.analyses });
    },
  });
}

export function useGenerateResume() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ analysisId, ...body }: {
      analysisId: string; decisions: { suggestionId: string; accepted: boolean; confirmed: boolean }[];
      format: 'DOCX' | 'PDF'; name?: string;
    }) => api.post<ResumeVersion>(`/api/analyses/${analysisId}/resume`, body).then((r) => r.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.versions });
      setTimeout(() => qc.invalidateQueries({ queryKey: keys.board }), 1500);
    },
  });
}

export const useVersions = () => useQuery({ queryKey: keys.versions, queryFn: () => get<ResumeVersion[]>('/api/resume-versions') });

/** Downloads through the API client (the token is in memory, so a plain link can't authenticate). */
async function downloadFile(path: string, format: 'DOCX' | 'PDF', fallbackName: string) {
  const r = await api.get<Blob>(path, { params: { format }, responseType: 'blob' });
  const disposition = r.headers['content-disposition'] as string | undefined;
  const name = /filename="?([^";]+)"?/.exec(disposition ?? '')?.[1] ?? `${fallbackName}.${format.toLowerCase()}`;
  const url = URL.createObjectURL(r.data);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export const downloadVersion = (id: string, format: 'DOCX' | 'PDF') =>
  downloadFile(`/api/resume-versions/${id}/file`, format, 'resume');

// ---------- cover letters ----------
export const useCoverLetters = (jobId?: string) =>
  useQuery({ queryKey: keys.letters(jobId), queryFn: () => get<CoverLetter[]>('/api/cover-letters', jobId ? { jobId } : undefined) });

export const useCoverLetter = (id: string) =>
  useQuery({ queryKey: keys.letter(id), queryFn: () => get<CoverLetter>(`/api/cover-letters/${id}`) });

export function useWriteLetter(analysisId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { length: LetterLength; motivation?: string }) =>
      api.post<CoverLetter>(`/api/analyses/${analysisId}/cover-letters`, body).then((r) => r.data),
    onSuccess: (l) => {
      qc.setQueryData(keys.letter(l.id), l);
      qc.invalidateQueries({ queryKey: ['letters'] });
    },
  });
}

export function useSaveLetter(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { body: string; version: number }) => api.put<CoverLetter>(`/api/cover-letters/${id}`, body).then((r) => r.data),
    onSuccess: (l) => {
      // Keep the notices from creation; the server only returns them once.
      qc.setQueryData<CoverLetter>(keys.letter(id), (old) => ({ ...l, notices: old?.notices ?? null }));
      qc.invalidateQueries({ queryKey: ['letters'] });
    },
  });
}

export function useDeleteLetter() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/api/cover-letters/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['letters'] }),
  });
}

export const downloadLetter = (id: string, format: 'DOCX' | 'PDF') =>
  downloadFile(`/api/cover-letters/${id}/file`, format, 'cover-letter');

// ---------- interview prep ----------
export const usePreps = (jobId?: string, enabled = true) =>
  useQuery({ queryKey: keys.preps(jobId), queryFn: () => get<PrepSummary[]>('/api/interview-preps', jobId ? { jobId } : undefined), enabled });

export const usePrep = (id: string) =>
  useQuery({ queryKey: keys.prep(id), queryFn: () => get<InterviewPrep>(`/api/interview-preps/${id}`) });

export const usePrepUsage = () =>
  useQuery({ queryKey: keys.prepUsage, queryFn: () => get<Usage>('/api/interview-preps/usage') });

export function useMakePrep() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (analysisId: string) => api.post<InterviewPrep>('/api/interview-preps', { analysisId }).then((r) => r.data),
    onSuccess: (p) => {
      qc.setQueryData(keys.prep(p.id), p);
      qc.invalidateQueries({ queryKey: ['preps'] });
    },
  });
}

/** Saves one question's notes or confidence (with its version) and updates the cached pack in place. */
export function useSavePrepAnswer(prepId: string) {
  const qc = useQueryClient();
  return useMutation({
    meta: { silent: true },
    mutationFn: (v: { itemId: string; notes?: string; confidence?: Confidence; version: number }) =>
      api.patch<PrepItem>(`/api/interview-preps/${prepId}/items/${v.itemId}`,
        { notes: v.notes, confidence: v.confidence, version: v.version }).then((r) => r.data),
    onSuccess: (item) => {
      qc.setQueryData<InterviewPrep>(keys.prep(prepId), (old) =>
        old && { ...old, items: old.items.map((i) => (i.id === item.id ? item : i)) });
      qc.invalidateQueries({ queryKey: keys.preps() });
    },
  });
}

export function useDeletePrep() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/api/interview-preps/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['preps'] }),
  });
}

// ---------- billing ----------
export const usePlans = () => useQuery({ queryKey: keys.plans, queryFn: () => get<PlansResponse>('/api/billing/plans'), staleTime: 300_000 });
export const useBilling = () => useQuery({ queryKey: keys.billing, queryFn: () => get<BillingStatus>('/api/billing/me') });
export const createOrder = (plan: string) => api.post<Checkout>('/api/billing/orders', { plan }).then((r) => r.data);
export const verifyPayment = (orderId: string, razorpayPaymentId: string, razorpaySignature: string) =>
  api.post<BillingStatus>(`/api/billing/orders/${orderId}/verify`, { razorpayPaymentId, razorpaySignature }).then((r) => r.data);

// ---------- insights ----------
export const useInsights = () => useQuery({ queryKey: keys.insights, queryFn: () => get<Insights>('/api/insights', { weeks: 12 }) });
export const useAdminMetrics = () => useQuery({ queryKey: keys.adminMetrics, queryFn: () => get<AdminMetrics>('/api/insights/admin', { weeks: 12 }) });

// ---------- browser extension ----------
export const useExtensionConnections = () =>
  useQuery({ queryKey: keys.extensions, queryFn: () => get<ExtensionConnection[]>('/api/auth/extension/connections') });

export function useDisconnectExtension() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/api/auth/extension/connections/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.extensions }),
  });
}

// ---------- notifications ----------
export const useNotifications = () =>
  useQuery({ queryKey: keys.notifications, queryFn: () => get<NotificationPage>('/api/notifications', { size: 50 }) });

export const useUnreadCount = () =>
  useQuery({
    queryKey: keys.unread,
    queryFn: () => get<{ count: number }>('/api/notifications/unread-count').then((d) => d.count),
    refetchInterval: 60_000,
  });

export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<AppNotification>(`/api/notifications/${id}/read`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.notifications }),
  });
}

export function useMarkAllRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/api/notifications/read-all'),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.notifications }),
  });
}

export const useEmailPrefs = () =>
  useQuery({ queryKey: keys.emailPrefs, queryFn: () => get<EmailPreferences>('/api/notifications/preferences') });

export function useSaveEmailPrefs() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: Partial<EmailPreferences>) => api.put<EmailPreferences>('/api/notifications/preferences', p).then((r) => r.data),
    onSuccess: (p) => qc.setQueryData(keys.emailPrefs, p),
  });
}
