/** Wordmark: two people joined by a marigold thread, then the name. */
export function Logo({ className }: { className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2 ${className ?? ''}`}>
      <svg viewBox="0 0 32 32" className="h-7 w-7" aria-hidden>
        <rect width="32" height="32" rx="7" fill="#1B2440" />
        <circle cx="9" cy="16" r="4" fill="#F6F7FA" />
        <circle cx="23" cy="16" r="4" fill="#E3A008" />
        <path d="M13 16h6" stroke="#E3A008" strokeWidth="2.5" strokeLinecap="round" />
      </svg>
      <span className="font-display text-lg font-semibold tracking-tight text-ink">ReferBoard</span>
    </span>
  );
}
