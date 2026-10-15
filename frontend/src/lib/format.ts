const rtf = new Intl.RelativeTimeFormat('en-IN', { numeric: 'auto' });
const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60],
];

/** "3 days ago", "in 2 hours", "just now". */
export function timeAgo(iso?: string | null): string {
  if (!iso) return '';
  const diff = (new Date(iso).getTime() - Date.now()) / 1000;
  for (const [unit, secs] of UNITS) {
    if (Math.abs(diff) >= secs) return rtf.format(Math.round(diff / secs), unit);
  }
  return 'just now';
}

export function shortDate(iso?: string | null): string {
  if (!iso) return '';
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

/** ₹ in lakhs per annum, the way Indian job posts talk about pay: "₹18–24 LPA". */
export function salaryRange(min?: number | null, max?: number | null, currency?: string | null): string | null {
  if (!min && !max) return null;
  if (currency && currency !== 'INR') {
    const f = (v: number) => `${Math.round(v / 1000)}k`;
    return `${currency} ${min ? f(min) : ''}${min && max ? '–' : ''}${max ? f(max) : ''}`;
  }
  const lpa = (v: number) => {
    const l = v / 100000;
    return l >= 10 ? Math.round(l).toString() : l.toFixed(1).replace(/\.0$/, '');
  };
  if (min && max) return `₹${lpa(min)}–${lpa(max)} LPA`;
  return min ? `From ₹${lpa(min)} LPA` : `Up to ₹${lpa(max!)} LPA`;
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}
