/**
 * Razorpay Checkout (https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/). The script is
 * loaded only when someone starts a payment. Card, UPI and bank details go to Razorpay, never to ReferBoard.
 */
const SCRIPT = 'https://checkout.razorpay.com/v1/checkout.js';

export interface RazorpayResult { razorpay_payment_id: string; razorpay_order_id: string; razorpay_signature: string }

interface RazorpayInstance {
  open(): void;
  on(event: 'payment.failed', cb: (r: { error?: { description?: string } }) => void): void;
}
type RazorpayCtor = new (options: Record<string, unknown>) => RazorpayInstance;

let loading: Promise<RazorpayCtor> | null = null;

export function loadRazorpay(): Promise<RazorpayCtor> {
  const existing = (window as unknown as { Razorpay?: RazorpayCtor }).Razorpay;
  if (existing) return Promise.resolve(existing);
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = SCRIPT;
      s.async = true;
      s.onload = () => {
        const ctor = (window as unknown as { Razorpay?: RazorpayCtor }).Razorpay;
        if (ctor) resolve(ctor);
        else reject(new Error('Razorpay Checkout did not load.'));
      };
      s.onerror = () => {
        loading = null;
        reject(new Error("Couldn't load Razorpay Checkout. Check your connection and try again."));
      };
      document.head.append(s);
    });
  }
  return loading;
}

export class CheckoutDismissed extends Error {
  constructor() {
    super('Payment window closed');
    this.name = 'CheckoutDismissed';
  }
}

/** Opens Checkout for an order; resolves with Razorpay's signed result, rejects if closed or the payment fails. */
export async function payWithRazorpay(options: {
  key: string; orderId: string; amountPaise: number; currency: string; name: string; description: string;
  prefill: { name?: string; email?: string };
}): Promise<RazorpayResult> {
  const Razorpay = await loadRazorpay();
  return new Promise((resolve, reject) => {
    // A failed attempt keeps Checkout open so the customer can try another method; only closing it ends the flow.
    let lastFailure: string | null = null;
    const rzp = new Razorpay({
      key: options.key, order_id: options.orderId, amount: options.amountPaise, currency: options.currency,
      name: options.name, description: options.description, prefill: options.prefill,
      theme: { color: '#1B2440' },
      handler: (r: RazorpayResult) => resolve(r),
      modal: {
        ondismiss: () => reject(lastFailure ? new Error(lastFailure + " You haven't been charged.") : new CheckoutDismissed()),
        confirm_close: true,
      },
    });
    rzp.on('payment.failed', (r) => {
      lastFailure = r.error?.description || "The payment didn't go through.";
    });
    rzp.open();
  });
}
