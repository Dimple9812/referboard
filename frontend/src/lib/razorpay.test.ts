import { CheckoutDismissed, payWithRazorpay } from './razorpay';

type Handlers = { handler: (r: unknown) => void; modal: { ondismiss: () => void } };
let last: { options: Handlers; failed?: (r: { error?: { description?: string } }) => void };

beforeEach(() => {
  (window as unknown as { Razorpay: unknown }).Razorpay = class {
    constructor(options: Handlers) {
      last = { options };
    }
    on(_: string, cb: (r: { error?: { description?: string } }) => void) {
      last.failed = cb;
    }
    open() {}
  };
});

const opts = { key: 'rzp_test_x', orderId: 'order_1', amountPaise: 29900, currency: 'INR', name: 'ReferBoard', description: 'Pro', prefill: {} };

test('resolves with the signed result', async () => {
  const p = payWithRazorpay(opts);
  await Promise.resolve();
  last.options.handler({ razorpay_payment_id: 'pay_1', razorpay_order_id: 'order_1', razorpay_signature: 'sig' });
  await expect(p).resolves.toMatchObject({ razorpay_payment_id: 'pay_1' });
});

test('closing the window is a quiet cancel, not an error', async () => {
  const p = payWithRazorpay(opts);
  await Promise.resolve();
  last.options.modal.ondismiss();
  await expect(p).rejects.toBeInstanceOf(CheckoutDismissed);
});

test('a failed attempt keeps checkout open; closing afterwards reports why', async () => {
  const p = payWithRazorpay(opts);
  await Promise.resolve();
  last.failed!({ error: { description: 'Your bank declined the payment.' } });
  last.options.modal.ondismiss();
  await expect(p).rejects.toThrow("Your bank declined the payment. You haven't been charged.");
});
