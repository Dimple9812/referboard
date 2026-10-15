import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AppLayout } from '@/components/AppLayout';
import { RedirectIfAuthed, RequireAuth } from '@/auth/RequireAuth';
import { FullPageSpinner } from '@/components/ui/Spinner';
import { LandingPage } from '@/pages/LandingPage';
import { LoginPage, SignupPage } from '@/pages/AuthPages';

// Signed-in screens load on demand; the landing and sign-in pages stay in the first bundle.
const OnboardingPage = lazy(() => import('@/pages/OnboardingPage'));
const JobFeedPage = lazy(() => import('@/pages/JobFeedPage'));
const JobDetailPage = lazy(() => import('@/pages/JobDetailPage'));
const AnalyzerPage = lazy(() => import('@/pages/AnalyzerPage'));
const ReferralAssistantPage = lazy(() => import('@/pages/ReferralAssistantPage'));
const ReferralsPage = lazy(() => import('@/pages/ReferralsPage'));
const TrackerPage = lazy(() => import('@/pages/TrackerPage'));
const NotificationsPage = lazy(() => import('@/pages/NotificationsPage'));
const SettingsPage = lazy(() => import('@/pages/SettingsPage'));
const CoverLetterPage = lazy(() => import('@/pages/CoverLetterPage'));
const ConnectExtensionPage = lazy(() => import('@/pages/ConnectExtensionPage'));

export default function App() {
  return (
    <Suspense fallback={<FullPageSpinner />}>
      <Routes>
        <Route element={<RedirectIfAuthed />}>
          <Route path="/" element={<LandingPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
        </Route>
        <Route element={<RequireAuth />}>
          <Route path="/onboarding" element={<OnboardingPage />} />
          <Route element={<AppLayout />}>
            <Route path="/jobs" element={<JobFeedPage />} />
            <Route path="/jobs/:id" element={<JobDetailPage />} />
            <Route path="/analyzer" element={<AnalyzerPage />} />
            <Route path="/analyzer/:id" element={<AnalyzerPage />} />
            <Route path="/referrals" element={<ReferralsPage />} />
            <Route path="/referrals/contacts/:contactId" element={<ReferralsPage />} />
            <Route path="/referrals/jobs/:jobId" element={<ReferralAssistantPage />} />
            <Route path="/tracker" element={<TrackerPage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/cover-letters/:id" element={<CoverLetterPage />} />
            <Route path="/connect-extension" element={<ConnectExtensionPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Suspense>
  );
}
