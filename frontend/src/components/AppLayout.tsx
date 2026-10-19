import { NavLink, Outlet, Link } from 'react-router-dom';
import { BarChart3, Bell, Briefcase, FileSearch, Gauge, KanbanSquare, MessagesSquare, Settings, Users } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useAuth } from '@/auth/AuthProvider';
import { useUnreadCount } from '@/api/queries';
import { cn } from '@/lib/cn';
import { Logo } from './Logo';
import { DemoBanner } from './Demo';

const NAV: { to: string; label: string; icon: LucideIcon }[] = [
  { to: '/jobs', label: 'Jobs', icon: Briefcase },
  { to: '/analyzer', label: 'JD Analyzer', icon: FileSearch },
  { to: '/referrals', label: 'Referrals', icon: Users },
  { to: '/tracker', label: 'Tracker', icon: KanbanSquare },
  { to: '/notifications', label: 'Inbox', icon: Bell },
];

/** Second group: useful, but not daily. On phones they live in the top bar so the tab bar stays at five. */
const MORE: { to: string; label: string; icon: LucideIcon }[] = [
  { to: '/interview', label: 'Interview prep', icon: MessagesSquare },
  { to: '/insights', label: 'Insights', icon: BarChart3 },
];

export function AppLayout() {
  const { user } = useAuth();
  const unread = useUnreadCount().data ?? 0;
  const more = user?.role === 'ADMIN' ? [...MORE, { to: '/admin', label: 'Admin', icon: Gauge }] : MORE;
  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[15rem_minmax(0,1fr)]">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-surface focus:px-3 focus:py-2">
        Skip to content
      </a>

      {/* Desktop rail */}
      <aside className="sticky top-0 hidden h-screen flex-col border-r border-line bg-surface px-4 py-6 lg:flex">
        <Link to="/jobs" className="px-2"><Logo /></Link>
        <nav aria-label="Main" className="mt-10 flex-1 space-y-1">
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} className={({ isActive }) => cn(
              'flex items-center gap-3 rounded px-3 py-2 text-sm font-medium text-slate hover:bg-paper hover:text-ink',
              isActive && 'bg-paper text-ink')}>
              <n.icon className="h-4 w-4" aria-hidden />
              <span className="flex-1">{n.label}</span>
              {n.to === '/notifications' && unread > 0 && <UnreadDot count={unread} />}
            </NavLink>
          ))}
          <div className="my-4 border-t border-line" />
          {more.map((n) => (
            <NavLink key={n.to} to={n.to} className={({ isActive }) => cn(
              'flex items-center gap-3 rounded px-3 py-2 text-sm font-medium text-slate hover:bg-paper hover:text-ink',
              isActive && 'bg-paper text-ink')}>
              <n.icon className="h-4 w-4" aria-hidden />
              <span className="flex-1">{n.label}</span>
            </NavLink>
          ))}
        </nav>
        <PlanLine plan={user?.plan} />
        <NavLink to="/settings" className={({ isActive }) => cn(
          'flex items-center gap-3 rounded px-3 py-2 text-sm text-slate hover:bg-paper hover:text-ink', isActive && 'bg-paper text-ink')}>
          <Settings className="h-4 w-4" aria-hidden />
          <span className="min-w-0 flex-1 truncate">{user?.fullName ?? 'Settings'}</span>
        </NavLink>
      </aside>

      {/* Mobile top bar */}
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-line bg-surface/95 px-4 py-3 backdrop-blur lg:hidden">
        <Link to="/jobs"><Logo /></Link>
        <div className="flex items-center">
          {more.map((n) => (
            <NavLink key={n.to} to={n.to} aria-label={n.label} className={({ isActive }) => cn('rounded p-2 text-slate hover:text-ink', isActive && 'text-ink')}>
              <n.icon className="h-5 w-5" aria-hidden />
            </NavLink>
          ))}
          <Link to="/settings" aria-label="Settings" className="rounded p-2 text-slate hover:text-ink"><Settings className="h-5 w-5" /></Link>
        </div>
      </header>

      <main id="main" className="px-4 pb-28 pt-6 sm:px-8 lg:px-12 lg:pb-16 lg:pt-10">
        <div className="mx-auto max-w-content"><DemoBanner /><Outlet /></div>
      </main>

      {/* Mobile tab bar */}
      <nav aria-label="Main" className="fixed inset-x-0 bottom-0 z-30 grid grid-cols-5 border-t border-line bg-surface pb-[env(safe-area-inset-bottom)] lg:hidden">
        {NAV.map((n) => (
          <NavLink key={n.to} to={n.to} className={({ isActive }) => cn(
            'relative flex flex-col items-center gap-1 py-2.5 text-[0.7rem] font-medium text-slate', isActive && 'text-ink')}>
            <n.icon className="h-5 w-5" aria-hidden />
            {n.label === 'JD Analyzer' ? 'Analyzer' : n.label}
            {n.to === '/notifications' && unread > 0 && <span className="absolute right-[30%] top-2 h-2 w-2 rounded-full bg-marigold" aria-label={`${unread} unread`} />}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}

function UnreadDot({ count }: { count: number }) {
  return <span className="rounded-full bg-marigold px-1.5 text-xs font-semibold tabular text-ink" aria-label={`${count} unread`}>{count > 99 ? '99+' : count}</span>;
}

/** Free users see a quiet way to Pro; Pro users see that they have it. */
function PlanLine({ plan }: { plan?: 'PRO' | 'FREE' }) {
  if (plan === 'PRO') {
    return <p className="mb-2 px-3 text-xs text-slate">Pro plan</p>;
  }
  return (
    <Link to="/pro" className="mb-2 rounded px-3 py-2 text-sm text-slate hover:bg-paper hover:text-ink">
      Free plan. <span className="font-medium text-ink underline decoration-line-strong underline-offset-4">Go Pro</span>
    </Link>
  );
}
