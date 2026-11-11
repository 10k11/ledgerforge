import { cn } from '@/lib/utils';

export function Logo({ className, inverted }: { className?: string; inverted?: boolean }) {
  return (
    <svg viewBox="0 0 32 32" className={cn('size-7', className)} aria-hidden>
      <rect width="32" height="32" rx="8" fill={inverted ? '#fff' : '#172033'} />
      <path d="M9 9h4v10h10v4H9z" fill={inverted ? '#172033' : '#fff'} />
      <path d="M17 9h6v6h-6z" fill="#2563EB" />
    </svg>
  );
}

export function Wordmark({ className, inverted }: { className?: string; inverted?: boolean }) {
  return (
    <span className={cn('flex items-center gap-2.5', className)}>
      <Logo inverted={inverted} />
      <span className={cn('text-[15px] font-semibold tracking-tight', inverted ? 'text-white' : 'text-ink')}>LedgerForge</span>
    </span>
  );
}
