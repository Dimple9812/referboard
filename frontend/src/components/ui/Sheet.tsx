import * as Dialog from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/** A side panel (full screen on phones) for detail views over a list. */
export function Sheet({ open, onOpenChange, title, description, children, wide }: {
  open: boolean; onOpenChange: (o: boolean) => void; title: ReactNode; description?: ReactNode; children: ReactNode; wide?: boolean;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-ink/30" />
        <Dialog.Content
          className={cn('fixed inset-y-0 right-0 z-50 flex w-full flex-col bg-surface shadow-[-12px_0_32px_-16px_rgba(27,36,64,0.35)]',
            wide ? 'sm:max-w-2xl' : 'sm:max-w-xl')}
        >
          <div className="flex items-start justify-between gap-4 border-b border-line px-6 py-5">
            <div>
              <Dialog.Title className="font-display text-xl font-semibold">{title}</Dialog.Title>
              {description && <Dialog.Description className="mt-1 text-sm text-slate">{description}</Dialog.Description>}
            </div>
            <Dialog.Close className="rounded p-1 text-slate hover:text-ink" aria-label="Close"><X className="h-5 w-5" /></Dialog.Close>
          </div>
          <div className="flex-1 overflow-y-auto px-6 py-5">{children}</div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** Centered dialog for short confirmations and forms. */
export function Modal({ open, onOpenChange, title, description, children }: {
  open: boolean; onOpenChange: (o: boolean) => void; title: ReactNode; description?: ReactNode; children: ReactNode;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-ink/30" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-lg bg-surface p-6 shadow-[0_24px_48px_-24px_rgba(27,36,64,0.45)]">
          <Dialog.Title className="font-display text-xl font-semibold">{title}</Dialog.Title>
          {description && <Dialog.Description className="mt-1 text-sm text-slate">{description}</Dialog.Description>}
          <div className="mt-5">{children}</div>
          <Dialog.Close className="absolute right-4 top-4 rounded p-1 text-slate hover:text-ink" aria-label="Close"><X className="h-5 w-5" /></Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
