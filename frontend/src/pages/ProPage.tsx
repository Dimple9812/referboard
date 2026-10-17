import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Check } from 'lucide-react';
import { toast } from 'sonner';
import { useQueryClient } from '@tanstack/react-query';
import { errorMessage } from '@/api/client';
import { createOrder, keys, useBilling, usePlans, verifyPayment } from '@/api/queries';
import type { Plan } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { PageHeader } from '@/components/PageHeader';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { CheckoutDismissed, payWithRazorpay } from '@/lib/razorpay';
import { rupees, shortDate } from '@/lib/format';
import { cn } from '@/lib/cn';

const COMPARE: { label: string; free: string; pro: string }[] = [
  { label: 'JD analyses', free: '5 a month', pro: 'Unlimited' },
  { label: 'Interview prep packs', free: '3 a month', pro: 'Unlimited' },
  { label: 'AI-written cover letters', free: '20 a day', pro: '100 a day' },
  { label: 'Jobs, referral assistant, tracker, extension', free: 'Included', pro: 'Included' },
];

type Step = 'idle' | 'paying' | 'confirming';

export default function ProPage() {
  const plans = usePlans();
  const billing = useBilling();
  const { user, reload } = useAuth();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>('idle');
  const [buying, setBuying] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const buy = async (plan: Plan) => {
    setProblem(null);
    setBuying(plan.id);
    setStep('paying');
    try {
      const order = await createOrder(plan.id);
      const result = await payWithRazorpay({
        key: order.keyId, orderId: order.razorpayOrderId, amountPaise: order.amountPaise, currency: order.currency,
        name: order.name, description: order.description, prefill: { name: user?.fullName, email: user?.email },
      });
      setStep('confirming');
      const status = await verifyPayment(order.orderId, result.razorpay_payment_id, result.razorpay_signature);
      qc.setQueryData(keys.billing, status);
      // The PRO role reaches the sign-in service through an event; wait briefly for it, then refresh the token.
      for (let i = 0; i < 12; i++) {
        const u = await reload();
        if (u.plan === 'PRO') break;
        await new Promise((r) => setTimeout(r, 800));
      }
      qc.invalidateQueries({ queryKey: keys.usage });
      qc.invalidateQueries({ queryKey: keys.prepUsage });
      toast.success(`Pro is on until ${shortDate(status.proUntil)}`);
      navigate('/settings#billing');
    } catch (e) {
      if (!(e instanceof CheckoutDismissed)) setProblem(e instanceof Error && !('isAxiosError' in e) ? e.message : errorMessage(e));
    } finally {
      setStep('idle');
      setBuying(null);
    }
  };

  const status = billing.data;
  return (
    <div className="max-w-3xl">
      <PageHeader title="ReferBoard Pro">
        For the weeks you're interviewing a lot. One payment, no subscription: Pro simply ends when your pass does.
      </PageHeader>

      {status?.plan === 'PRO' && (
        <p className="mb-8 rounded border border-line bg-surface px-4 py-3 text-sm">
          You have Pro until <span className="font-medium">{shortDate(status.proUntil)}</span>. Buying another pass adds its days on top.
        </p>
      )}

      <table className="w-full text-left text-sm">
        <caption className="sr-only">Free and Pro compared</caption>
        <thead>
          <tr className="border-b border-line text-slate"><th className="py-2 font-normal" scope="col">What you get</th>
            <th className="py-2 font-normal" scope="col">Free</th><th className="py-2 font-medium text-ink" scope="col">Pro</th></tr>
        </thead>
        <tbody>
          {COMPARE.map((r) => (
            <tr key={r.label} className="border-b border-line">
              <th scope="row" className="py-3 pr-4 font-normal">{r.label}</th>
              <td className="py-3 pr-4 text-slate">{r.free}</td>
              <td className="py-3 font-medium">{r.pro}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <section aria-labelledby="plans" className="mt-10">
        <h2 id="plans" className="text-lg font-semibold">Choose a pass</h2>
        {plans.isPending ? <Skeleton className="mt-4 h-32" />
          : plans.isError ? <ErrorState error={plans.error} onRetry={() => plans.refetch()} />
          : (
            <>
              {!plans.data.available && (
                <p className="mt-3 rounded border border-line bg-surface px-3 py-2 text-sm text-slate">
                  Payments aren't set up on this ReferBoard yet, so Pro can't be bought here.
                </p>
              )}
              {plans.data.available && plans.data.testMode && (
                <p className="mt-3 text-sm text-slate">Test mode: use Razorpay's test cards or UPI IDs. No real money moves.</p>
              )}
              <div className="mt-4 grid gap-4 sm:grid-cols-2">
                {plans.data.plans.map((p) => {
                  const perMonth = p.days > 31 ? Math.round(p.amountPaise / (p.days / 30)) : null;
                  return (
                    <div key={p.id} className={cn('flex flex-col rounded-lg border bg-surface p-5', p.days > 31 ? 'border-ink' : 'border-line')}>
                      <h3 className="font-medium">{p.name}</h3>
                      <p className="mt-2"><span className="font-display text-3xl font-semibold tabular">{rupees(p.amountPaise)}</span></p>
                      <p className="mt-1 text-sm text-slate">{perMonth ? `About ${rupees(perMonth)} a month. ` : ''}{p.days} days of Pro, paid once.</p>
                      <Button className="mt-5" variant={p.days > 31 ? 'primary' : 'outline'} disabled={!plans.data.available || step !== 'idle'}
                        loading={buying === p.id} onClick={() => buy(p)}>
                        {buying === p.id ? (step === 'confirming' ? 'Confirming payment' : 'Opening Razorpay') : `Pay ${rupees(p.amountPaise)}`}
                      </Button>
                    </div>
                  );
                })}
              </div>
            </>
          )}
        {problem && <p role="alert" className="mt-4 text-sm text-danger">{problem}</p>}
      </section>

      <ul className="mt-10 space-y-2 text-sm text-slate">
        {['Payments are handled by Razorpay: UPI, cards, net banking and wallets. ReferBoard never sees your card or bank details.',
          'Razorpay emails you a receipt for every payment.',
          'No auto-renewal. Nothing to cancel.'].map((t) => (
          <li key={t} className="flex gap-2"><Check className="mt-0.5 h-4 w-4 shrink-0 text-ink" aria-hidden />{t}</li>
        ))}
      </ul>
      <p className="mt-8 text-sm"><Link to="/settings#billing" className="text-slate underline decoration-line-strong underline-offset-4 hover:text-ink">Your payments</Link></p>
    </div>
  );
}
