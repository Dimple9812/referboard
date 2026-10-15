// Shapes returned by the ReferBoard services (through the gateway). Kept in one place so a backend change shows up
// as a type error in every screen that uses it.

export interface ApiErrorBody {
  status: number;
  code: string;
  message: string;
  correlationId?: string;
  violations?: { field: string; message: string }[];
}

// ---------- auth ----------
export interface User {
  id: string;
  email: string;
  fullName: string;
  avatarUrl?: string | null;
  authProvider: string;
  emailVerified: boolean;
}
export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

// ---------- profile ----------
export interface SkillDto { name: string; displayName: string; years?: number | null; source?: string; evidence?: string | null }
export interface ExperienceDto {
  id?: string; company: string; title: string; location?: string | null; startDate?: string | null;
  endDate?: string | null; current: boolean; description?: string | null; bullets: string[];
}
export interface EducationDto {
  id?: string; institution: string; degree?: string | null; fieldOfStudy?: string | null;
  startYear?: number | null; endYear?: number | null; grade?: string | null;
}
export type ExperienceLevel = 'INTERN' | 'ENTRY' | 'MID' | 'SENIOR' | 'LEAD' | 'PRINCIPAL';
export interface Preferences {
  roles: string[]; locations: string[]; remoteOnly: boolean; openToRemote: boolean;
  minSalaryInr?: number | null; experienceLevel?: ExperienceLevel | null;
}
export interface Profile {
  userId: string; exists: boolean; fullName?: string | null; email?: string | null; phone?: string | null;
  headline?: string | null; summary?: string | null; currentTitle?: string | null; currentCompany?: string | null;
  location?: string | null; totalExperienceYears?: number | null; college?: string | null; highestDegree?: string | null;
  links: string[]; certifications: string[]; skills: SkillDto[]; experiences: ExperienceDto[];
  educations: EducationDto[]; preferences?: Preferences | null; profileVersion: number;
  parseSource?: 'LLM' | 'HEURISTIC' | 'MANUAL' | null; primaryResumeId?: string | null; embeddingReady: boolean;
  updatedAt?: string | null;
}
export interface Resume {
  id: string; fileName: string; contentType: string; sizeBytes: number;
  parseStatus: 'PENDING' | 'PARSED' | 'FAILED'; parseError?: string | null; parser?: string | null;
  primary: boolean; createdAt: string; parsedAt?: string | null;
}

// ---------- jobs ----------
export interface JobSkill { name: string; label: string }
export interface JobCard {
  id: string; title: string; companyName: string; location?: string | null; remote: boolean;
  seniority?: string | null; employmentType?: string | null; postedAt?: string | null;
  salaryMin?: number | null; salaryMax?: number | null; salaryCurrency?: string | null; skills: JobSkill[];
  ghostJobSuspected: boolean; applyUrl?: string | null; source?: string | null;
  matchPercent?: number | null; matchedSkills: JobSkill[];
}
export interface JobPage { items: JobCard[]; page: number; size: number; total: number; hasNext: boolean }
export interface MatchView {
  available: boolean; unavailableReason?: string | null; matchPercent?: number | null;
  components: Record<string, number | null>; weights: Record<string, number>;
  matchedSkills: JobSkill[]; missingSkills: JobSkill[]; reasons: string[];
}
export interface JobDetail {
  job: JobCard; descriptionHtml?: string | null; department?: string | null; country?: string | null;
  status: 'OPEN' | 'CLOSED' | string; closedAt?: string | null; firstSeenAt?: string | null; lastSeenAt?: string | null;
  match?: MatchView | null;
}
export type JobSort = 'RELEVANCE' | 'RECENT' | 'MATCH';
export interface JobSearch {
  q?: string; location?: string; remote?: boolean; postedWithinDays?: number; company?: string;
  hideGhostJobs?: boolean; sort?: JobSort;
}

// ---------- referrals ----------
export type PersonType = 'RECRUITER' | 'ENGINEERING_MANAGER' | 'TEAM_MEMBER' | 'ALUMNI' | 'EX_COLLEAGUE' | 'OTHER';
export type ContactStatus = 'NOT_CONTACTED' | 'REQUEST_SENT' | 'CONNECTED' | 'REFERRAL_ASKED' | 'REFERRED' | 'NO_RESPONSE';
export interface Suggestion {
  personType: PersonType; title: string; why: string; searchKeywords: string; linkedinSearchUrl: string; tip: string;
}
export interface Suggestions { jobId: string; jobTitle: string; companyName: string; people: Suggestion[]; notice: string }
export interface Contact {
  id: string; jobId: string; jobTitle?: string | null; companyName?: string | null; fullName: string;
  profileUrl?: string | null; headline?: string | null; personType: PersonType; status: ContactStatus;
  allowedNextStatuses: ContactStatus[]; requestSentAt?: string | null; connectedAt?: string | null;
  referralAskedAt?: string | null; referredAt?: string | null; noResponseAt?: string | null; notes?: string | null;
  createdAt: string; updatedAt: string;
}
export interface ContactPage { items: Contact[]; page: number; size: number; total: number; hasNext: boolean }
export type DraftKind = 'CONNECTION_NOTE' | 'REFERRAL_REQUEST' | 'FOLLOW_UP';
export type Tone = 'WARM' | 'CONCISE';
export interface Draft {
  id: string; kind: DraftKind; tone: Tone; content: string; charCount: number; model: string;
  createdAt?: string | null; copiedAt?: string | null;
}
export interface DraftSet { contactId: string; type: 'INTRO' | 'FOLLOW_UP'; generatedBy: 'AI' | 'TEMPLATE'; notices: string[]; drafts: Draft[] }
export interface Reminder {
  id: string; contactId: string; contactName?: string | null; jobId?: string | null; jobTitle?: string | null;
  companyName?: string | null; type: 'SEND_REFERRAL_MESSAGE' | 'FOLLOW_UP';
  status: 'PENDING' | 'FIRED' | 'DONE' | 'DISMISSED' | 'OBSOLETE'; dueAt: string; firedAt?: string | null;
  message?: string | null; draftedFollowUp?: string | null;
}
export interface ContactDetail {
  contact: Contact; history: { fromStatus?: ContactStatus | null; toStatus: ContactStatus; changedAt: string }[];
  drafts: Draft[]; reminders: Reminder[];
}
export interface Pipeline { byStatus: Record<ContactStatus, number>; total: number; activeReminders: number }

// ---------- tracker ----------
export type Stage = 'SAVED' | 'APPLIED' | 'REFERRAL_REQUESTED' | 'REFERRED' | 'INTERVIEW' | 'OFFER' | 'REJECTED';
export interface Card {
  id: string; jobId?: string | null; jobTitle: string; companyName: string; location?: string | null;
  applyUrl?: string | null; stage: Stage; position: number; source: 'MANUAL' | 'REFERRAL';
  resumeVersionId?: string | null; referralSummary?: string | null; notes?: string | null; appliedAt?: string | null;
  stageChangedAt: string; nextAction?: string | null; nextActionAt?: string | null; jobClosed: boolean;
  jobClosedAt?: string | null; createdAt?: string | null; updatedAt?: string | null;
}
export interface Board { columns: { stage: Stage; label: string; count: number; cards: Card[] }[]; total: number }
export interface TimelineEntry {
  id: string; type: string; fromStage?: Stage | null; toStage?: Stage | null; message?: string | null;
  details: Record<string, unknown>; occurredAt: string;
}
export interface CardDetail { card: Card; timeline: TimelineEntry[] }

// ---------- analyzer ----------
export type Verdict = 'STRONG' | 'GOOD' | 'PARTIAL' | 'WEAK';
export interface BreakdownComponent { key: string; label: string; weight: number; score: number | null; points: number; detail: string }
export interface SkillMatch { name: string; label: string; required: boolean; evidence: 'STRONG' | 'WEAK'; usedIn?: string | null }
export interface SkillRef { name: string; label: string }
export interface AtsCheck { id: string; label: string; status: 'PASS' | 'WARN' | 'FAIL'; detail: string }
export type SuggestionType = 'SKILLS_REORDER' | 'SKILL_WORDING' | 'ADD_SKILL' | 'BULLET_REWRITE' | 'SUMMARY_REWRITE'
  | 'EVIDENCE_ADVICE' | 'GAP_ADVICE' | 'SENIORITY_ADVICE';
export interface ResumeSuggestion {
  id: string; type: SuggestionType; priority: 'HIGH' | 'MEDIUM' | 'LOW'; title: string; rationale: string;
  before?: string | null; after?: string | null; skillsIntroduced: string[]; requiresConfirmation: boolean;
  confirmationLabel?: string | null; applicable: boolean; impact?: number | null; origin: 'RULES' | 'AI';
}
export interface AnalysisReport {
  id: string; createdAt: string;
  job: { jobId?: string | null; title?: string | null; companyName?: string | null; jdSource: string };
  resume: { source: 'PROFILE_RESUME' | 'VERSION'; id: string; name?: string | null };
  score: number; verdict: Verdict; breakdown: BreakdownComponent[]; matchedSkills: SkillMatch[];
  missingSkills: { required: SkillRef[]; preferred: SkillRef[]; weaklyEvidenced: SkillRef[] };
  atsChecks: AtsCheck[]; suggestions: ResumeSuggestion[]; projectedScore: number; projectedScoreWithConfirmed: number;
  jdParser: 'LLM' | 'HEURISTIC'; aiSuggestions: boolean; notices: string[]; reused: boolean;
}
export interface AnalysisSummary {
  id: string; createdAt: string; jobId?: string | null; jobTitle?: string | null; companyName?: string | null;
  score: number; verdict: Verdict; projectedScore: number; resumeName?: string | null;
}
export interface Usage { period: string; used: number; limit?: number | null; remaining?: number | null; unlimited: boolean; resetsOn?: string | null }
export interface ResumeVersion {
  id: string; name: string; jobId?: string | null; analysisId?: string | null; fileFormat: 'DOCX' | 'PDF';
  scoreBefore?: number | null; scoreAfter?: number | null; acceptedSuggestions: number; createdAt: string;
  downloadPath: string;
}

// ---------- notifications ----------
export interface AppNotification {
  id: string; type: 'REFERRAL_REMINDER' | 'DAILY_DIGEST' | 'WELCOME' | 'SYSTEM'; title: string; body: string;
  link?: string | null; data: Record<string, unknown>; read: boolean; readAt?: string | null; createdAt: string;
}
export interface NotificationPage { items: AppNotification[]; unreadCount: number; page: number; size: number; total: number; hasNext: boolean }
export interface EmailPreferences { emailReminders: boolean; emailDigest: boolean }
