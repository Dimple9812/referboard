import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/cn';

const button = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded font-medium transition-colors disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        primary: 'bg-ink text-white hover:bg-ink-soft',
        referral: 'bg-marigold text-ink hover:bg-[#D19306]',
        outline: 'border border-line-strong bg-surface text-ink hover:border-ink',
        ghost: 'text-ink hover:bg-ink/5',
        danger: 'text-danger hover:bg-danger-soft',
        link: 'h-auto px-0 text-ink underline decoration-line-strong underline-offset-4 hover:decoration-ink',
      },
      size: { sm: 'h-8 px-3 text-sm', md: 'h-10 px-4 text-sm', lg: 'h-12 px-6 text-base', icon: 'h-9 w-9' },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
);

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof button> {
  asChild?: boolean;
  loading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild, loading, children, disabled, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button';
    return (
      <Comp ref={ref} className={cn(button({ variant, size }), className)} disabled={disabled || loading} {...props}>
        {asChild ? children : (<>{loading && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}{children}</>)}
      </Comp>
    );
  },
);
Button.displayName = 'Button';
