import { cva, type VariantProps } from 'class-variance-authority';
import type { HTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

const badge = cva('inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs font-medium', {
  variants: {
    tone: {
      neutral: 'bg-ink/5 text-ink-soft',
      fit: 'bg-fit-soft text-fit',
      referral: 'bg-marigold-soft text-marigold-deep',
      warn: 'bg-warn-soft text-warn',
      danger: 'bg-danger-soft text-danger',
      outline: 'border border-line-strong text-slate',
    },
  },
  defaultVariants: { tone: 'neutral' },
});

export function Badge({ className, tone, ...p }: HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badge>) {
  return <span className={cn(badge({ tone }), className)} {...p} />;
}
