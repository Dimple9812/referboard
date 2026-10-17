// Test-only stand-in for Razorpay, for the Playwright journey (Razorpay itself isn't reachable from CI).
//   - REST API the billing service calls (RAZORPAY_API_BASE_URL=http://localhost:9001): orders, payments, capture.
//   - /v1/checkout.js: a fake Checkout whose open() "pays" at once. The test routes
//     https://checkout.razorpay.com/** here, so the page loads it from the real Checkout URL (allowed by the CSP).
//   - /test/pay: creates a captured payment and signs it exactly as Razorpay does,
//     hmac_sha256(order_id + "|" + payment_id, key_secret), so the billing service's real checks run.
// Usage: RAZORPAY_KEY_SECRET=... node e2e/support/mock-razorpay.mjs [port]
import { createHmac, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';

const port = Number(process.argv[2] ?? 9001);
const secret = process.env.RAZORPAY_KEY_SECRET ?? 'e2e_key_secret';
const orders = new Map();
const payments = new Map();
const id = (p) => `${p}_${randomBytes(7).toString('hex')}`;

const checkoutJs = `
window.Razorpay = class {
  constructor(o) { this.o = o; this.failed = null; }
  on(event, cb) { if (event === 'payment.failed') this.failed = cb; }
  open() {
    fetch('https://checkout.razorpay.com/test/pay?order_id=' + encodeURIComponent(this.o.order_id))
      .then((r) => r.json()).then((r) => this.o.handler(r))
      .catch(() => this.o.modal && this.o.modal.ondismiss && this.o.modal.ondismiss());
  }
};`;

function send(res, status, body, type = 'application/json') {
  res.writeHead(status, { 'Content-Type': type, 'Access-Control-Allow-Origin': '*' });
  res.end(typeof body === 'string' ? body : JSON.stringify(body));
}

createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${port}`);
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    if (req.method === 'POST' && url.pathname === '/v1/orders') {
      const body = JSON.parse(raw || '{}');
      const order = { id: id('order'), amount: body.amount, currency: body.currency, receipt: body.receipt, status: 'created' };
      orders.set(order.id, order);
      return send(res, 200, order);
    }
    if (url.pathname === '/v1/checkout.js') return send(res, 200, checkoutJs, 'application/javascript');
    if (url.pathname === '/test/pay') {
      const order = orders.get(url.searchParams.get('order_id'));
      if (!order) return send(res, 404, { error: 'unknown order' });
      const payment = { id: id('pay'), order_id: order.id, amount: order.amount, currency: order.currency, status: 'captured', method: 'upi' };
      payments.set(payment.id, payment);
      const signature = createHmac('sha256', secret).update(`${order.id}|${payment.id}`).digest('hex');
      return send(res, 200, { razorpay_payment_id: payment.id, razorpay_order_id: order.id, razorpay_signature: signature });
    }
    const m = url.pathname.match(/^\/v1\/payments\/([^/]+)(\/capture)?$/);
    if (m) {
      const p = payments.get(m[1]);
      if (!p) return send(res, 400, { error: { code: 'BAD_REQUEST_ERROR' } });
      if (m[2]) p.status = 'captured';
      return send(res, 200, p);
    }
    send(res, 404, { error: 'not found' });
  });
}).listen(port, () => console.log(`mock razorpay on :${port}`));
