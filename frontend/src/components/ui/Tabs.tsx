import * as T from '@radix-ui/react-tabs';
import type { ComponentProps } from 'react';
import { cn } from '@/lib/cn';

export const Tabs = T.Root;
export const TabsContent = T.Content;

export function TabsList({ className, ...p }: ComponentProps<typeof T.List>) {
  return <T.List className={cn('flex gap-6 overflow-x-auto border-b border-line', className)} {...p} />;
}

export function TabsTrigger({ className, ...p }: ComponentProps<typeof T.Trigger>) {
  return (
    <T.Trigger
      className={cn('-mb-px whitespace-nowrap border-b-2 border-transparent pb-2.5 text-sm font-medium text-slate hover:text-ink',
        'data-[state=active]:border-ink data-[state=active]:text-ink', className)}
      {...p}
    />
  );
}
