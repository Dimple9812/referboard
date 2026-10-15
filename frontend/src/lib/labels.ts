import type { ContactStatus, PersonType, Stage, Verdict } from '@/api/types';

export const STATUS_LABEL: Record<ContactStatus, string> = {
  NOT_CONTACTED: 'Not contacted',
  REQUEST_SENT: 'Request sent',
  CONNECTED: 'Connected',
  REFERRAL_ASKED: 'Referral asked',
  REFERRED: 'Referred',
  NO_RESPONSE: 'No response',
};

/** What the user did, for the button that records it. */
export const STATUS_ACTION: Record<ContactStatus, string> = {
  NOT_CONTACTED: 'Back to not contacted',
  REQUEST_SENT: 'I sent a request',
  CONNECTED: 'They accepted',
  REFERRAL_ASKED: 'I asked for a referral',
  REFERRED: 'They referred me',
  NO_RESPONSE: 'No response',
};

export const PIPELINE: ContactStatus[] = ['NOT_CONTACTED', 'REQUEST_SENT', 'CONNECTED', 'REFERRAL_ASKED', 'REFERRED'];

export const PERSON_LABEL: Record<PersonType, string> = {
  RECRUITER: 'Recruiter',
  ENGINEERING_MANAGER: 'Engineering manager',
  TEAM_MEMBER: 'Team member',
  ALUMNI: 'Alum',
  EX_COLLEAGUE: 'Former colleague',
  OTHER: 'Other',
};

export const STAGE_LABEL: Record<Stage, string> = {
  SAVED: 'Saved',
  APPLIED: 'Applied',
  REFERRAL_REQUESTED: 'Referral requested',
  REFERRED: 'Referred',
  INTERVIEW: 'Interview',
  OFFER: 'Offer',
  REJECTED: 'Rejected',
};

export const VERDICT_LABEL: Record<Verdict, string> = {
  STRONG: 'Strong match',
  GOOD: 'Good match',
  PARTIAL: 'Partial match',
  WEAK: 'Weak match',
};
