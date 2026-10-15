import { useNavigate } from 'react-router-dom';
import { Bell, Handshake, Newspaper, Sparkles } from 'lucide-react';
import { useMarkAllRead, useMarkRead, useNotifications } from '@/api/queries';
import type { AppNotification } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { Button } from '@/components/ui/Button';
import { ListSkeleton } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { timeAgo } from '@/lib/format';
import { cn } from '@/lib/cn';

const ICON: Record<AppNotification['type'], typeof Bell> = {
  REFERRAL_REMINDER: Handshake,
  DAILY_DIGEST: Newspaper,
  WELCOME: Sparkles,
  SYSTEM: Bell,
};

/** In-app links are relative paths; anything else is ignored rather than followed. */
const internal = (link?: string | null) => (link && link.startsWith('/') && !link.startsWith('//') ? link : null);

export default function NotificationsPage() {
  const q = useNotifications();
  const markRead = useMarkRead();
  const markAll = useMarkAllRead();
  const navigate = useNavigate();
  const unread = q.data?.unreadCount ?? 0;

  const open = (n: AppNotification) => {
    if (!n.read) markRead.mutate(n.id);
    const to = internal(n.link);
    if (to) navigate(to);
  };

  return (
    <div className="max-w-3xl">
      <PageHeader title="Inbox"
        actions={unread > 0 && <Button variant="outline" size="sm" loading={markAll.isPending} onClick={() => markAll.mutate()}>Mark all as read</Button>}>
        {unread > 0 ? `${unread} unread.` : 'Reminders to follow up with contacts, and your daily job digest.'}
      </PageHeader>

      {q.isPending ? <ListSkeleton rows={6} />
        : q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} />
        : q.data.items.length === 0 ? (
          <EmptyState title="Nothing here yet">
            When a follow-up with one of your contacts is due, you'll see it here and by email.
          </EmptyState>
        ) : (
          <ul className="divide-y divide-line border-y border-line">
            {q.data.items.map((n) => {
              const Icon = ICON[n.type] ?? Bell;
              const referral = n.type === 'REFERRAL_REMINDER';
              return (
                <li key={n.id}>
                  <button type="button" onClick={() => open(n)}
                    className={cn('flex w-full gap-4 px-2 py-4 text-left transition-colors hover:bg-ink/[0.03] sm:px-4',
                      !n.read && 'bg-surface')}>
                    <span className={cn('mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full',
                      referral ? 'bg-marigold-soft text-marigold-deep' : 'bg-ink/5 text-ink-soft')}>
                      <Icon className="h-4 w-4" aria-hidden />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-baseline justify-between gap-3">
                        <span className={cn('text-ink', n.read ? 'font-normal' : 'font-semibold')}>{n.title}</span>
                        <time dateTime={n.createdAt} className="shrink-0 text-xs text-slate tabular">{timeAgo(n.createdAt)}</time>
                      </span>
                      <span className="mt-1 block text-sm text-slate">{n.body}</span>
                    </span>
                    {!n.read && <span className="mt-2 h-2 w-2 shrink-0 rounded-full bg-ink" aria-label="Unread" />}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
    </div>
  );
}
