import { ArrowDownRight, ArrowUpRight, Eye, EyeOff, Minus } from 'lucide-react';
import * as React from 'react';
import { CopyButton } from '@/components/shared';
import { formatMoney } from '@/lib/money';
import { cn } from '@/lib/utils';

// ---------------------------------------------------------------- Derivations (pure, tested)

export interface DayFlow {
  date: string;
  inMinor: number;
  outMinor: number;
}

/**
 * Reconstructs the end-of-day balance for each day of the series by walking backwards from the
 * current balance: balance(d-1) = balance(d) - in(d) + out(d). Exact integer arithmetic.
 */
export function balanceTrajectory(days: DayFlow[], currentBalanceMinor: number): number[] {
  const out = new Array<number>(days.length);
  let running = currentBalanceMinor;
  for (let i = days.length - 1; i >= 0; i--) {
    out[i] = running;
    running = running - days[i]!.inMinor + days[i]!.outMinor;
  }
  return out;
}

/** Sum of the last `n` days and the `n` days before that, for a series of values. */
export function windowTotals(values: number[], n: number): { current: number; previous: number } {
  const current = values.slice(-n).reduce((a, b) => a + b, 0);
  const previous = values.slice(-2 * n, -n).reduce((a, b) => a + b, 0);
  return { current, previous };
}

/** Percentage change, or null when there is no baseline to compare against. */
export function percentChange(current: number, previous: number): number | null {
  if (previous === 0) return current === 0 ? 0 : null;
  return Math.round(((current - previous) / previous) * 1000) / 10;
}

// ---------------------------------------------------------------- Sparkline

export function Sparkline({
  values,
  className,
  stroke = '#93c5fd',
  fill = 'rgba(147,197,253,0.18)',
}: {
  values: number[];
  className?: string;
  stroke?: string;
  fill?: string;
}) {
  const id = React.useId();
  if (values.length < 2) return null;
  const w = 300;
  const h = 64;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const pts = values.map((v, i) => [(i / (values.length - 1)) * w, h - 4 - ((v - min) / span) * (h - 8)] as const);
  const line = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `${line} L${w},${h} L0,${h} Z`;
  return (
    <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className={cn('h-16 w-full', className)} aria-hidden>
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={fill} />
          <stop offset="100%" stopColor="transparent" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${id})`} />
      <path d={line} fill="none" stroke={stroke} strokeWidth="2" vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
      <circle cx={pts[pts.length - 1]![0]} cy={pts[pts.length - 1]![1]} r="3.5" fill={stroke} />
    </svg>
  );
}

// ---------------------------------------------------------------- Delta

export function DeltaBadge({ value, invert, label = 'vs prior 7d' }: { value: number | null; invert?: boolean; label?: string }) {
  if (value === null) return <span className="text-xs text-muted">New activity {label.replace('vs ', 'vs ')}</span>;
  const up = value > 0;
  const flat = value === 0;
  const good = flat ? null : invert ? !up : up;
  const Icon = flat ? Minus : up ? ArrowUpRight : ArrowDownRight;
  return (
    <span className="flex items-center gap-1.5 text-xs text-muted">
      <span
        className={cn(
          'inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 font-semibold tabular',
          good === null && 'bg-subtle text-ink-soft',
          good === true && 'bg-success-soft text-success',
          good === false && 'bg-danger-soft text-danger-strong',
        )}
      >
        <Icon className="size-3" aria-hidden />
        {Math.abs(value)}%
      </span>
      {label}
    </span>
  );
}

// ---------------------------------------------------------------- Balance hero

export function BalanceHero({
  label,
  balanceMinor,
  currency,
  accountNumber,
  trajectory,
  footer,
  actions,
}: {
  label: string;
  balanceMinor: number;
  currency: string;
  accountNumber: string;
  trajectory: number[];
  footer?: React.ReactNode;
  actions?: React.ReactNode;
}) {
  const [hidden, setHidden] = React.useState(false);
  return (
    <div className="relative overflow-hidden rounded-2xl bg-ink p-6 text-white shadow-[0_10px_30px_-12px_rgb(23_32_51/0.6)]">
      <div
        className="pointer-events-none absolute inset-0 opacity-[0.06]"
        style={{ backgroundImage: 'radial-gradient(#fff 1px, transparent 1px)', backgroundSize: '18px 18px' }}
        aria-hidden
      />
      <div className="pointer-events-none absolute -right-24 -top-24 size-72 rounded-full bg-primary/30 blur-3xl" aria-hidden />
      <div className="relative">
        <div className="flex items-center justify-between">
          <p className="text-[13px] font-medium text-slate-300">{label}</p>
          <button
            onClick={() => setHidden((h) => !h)}
            className="grid size-8 place-items-center rounded-lg text-slate-300 hover:bg-white/10 hover:text-white"
            aria-label={hidden ? 'Show balance' : 'Hide balance'}
            aria-pressed={hidden}
          >
            {hidden ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
          </button>
        </div>
        <p className="tabular mt-2 text-[40px] font-semibold leading-none tracking-tight">
          {hidden ? '••••••' : formatMoney(balanceMinor, currency)}
        </p>
        <div className="mt-3 flex items-center gap-1 text-[13px] text-slate-400">
          <span className="font-mono tracking-wide">{accountNumber.replace(/(.{2})(.{4})(.{4})(.{4})/, '$1 $2 $3 $4')}</span>
          <span className="text-slate-300 [&_button]:text-slate-300 [&_button:hover]:bg-white/10 [&_button:hover]:text-white">
            <CopyButton value={accountNumber} label="Copy account number" />
          </span>
        </div>
        <Sparkline values={trajectory} className="mt-4" />
        {footer && <div className="mt-2 text-xs text-slate-400">{footer}</div>}
        {actions && <div className="mt-5 flex flex-wrap gap-2">{actions}</div>}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- Breakdown donut

export function BreakdownDonut({
  segments,
  currency,
  centerLabel,
}: {
  segments: { label: string; value: number; color: string }[];
  currency: string;
  centerLabel: string;
}) {
  segments = segments.filter((s) => s.value > 0);
  const total = segments.reduce((a, s) => a + s.value, 0);
  const r = 42;
  const c = 2 * Math.PI * r;
  let offset = 0;
  return (
    <div className="flex flex-col items-center gap-5">
      <div className="relative size-40 shrink-0">
        <svg viewBox="0 0 100 100" className="size-full -rotate-90" role="img" aria-label={`${centerLabel} breakdown`}>
          <circle cx="50" cy="50" r={r} fill="none" stroke="#eef1f6" strokeWidth="11" />
          {total > 0 &&
            segments.map((s) => {
              const len = (s.value / total) * c;
              const el = (
                <circle
                  key={s.label}
                  cx="50"
                  cy="50"
                  r={r}
                  fill="none"
                  stroke={s.color}
                  strokeWidth="11"
                  strokeDasharray={`${Math.max(0, len - (segments.length > 1 ? 2 : 0))} ${c}`}
                  strokeDashoffset={-offset}
                />
              );
              offset += len;
              return el;
            })}
        </svg>
        <div className="absolute inset-0 grid place-items-center text-center">
          <div>
            <p className="text-[11px] text-muted">{centerLabel}</p>
            <p className="tabular text-sm font-semibold">{formatMoney(total, currency, { compact: true })}</p>
          </div>
        </div>
      </div>
      <ul className="w-full space-y-2.5">
        {segments.map((s) => (
          <li key={s.label} className="flex items-center gap-2.5 text-[13px]">
            <span className="size-2.5 rounded-sm" style={{ background: s.color }} aria-hidden />
            <span className="flex-1 text-ink-soft">{s.label}</span>
            <span className="tabular font-medium text-ink">{formatMoney(s.value, currency)}</span>
            <span className="tabular w-10 text-right text-xs text-muted">{total ? Math.round((s.value / total) * 100) : 0}%</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// ---------------------------------------------------------------- Ring

export function RateRing({ value, label, sublabel }: { value: number | null; label: string; sublabel: string }) {
  const r = 40;
  const c = 2 * Math.PI * r;
  const pct = value ?? 0;
  return (
    <div className="flex items-center gap-5">
      <div className="relative size-28 shrink-0">
        <svg viewBox="0 0 100 100" className="size-full -rotate-90" role="img" aria-label={`${label}: ${value ?? 0}%`}>
          <circle cx="50" cy="50" r={r} fill="none" stroke="#eef1f6" strokeWidth="10" />
          <circle cx="50" cy="50" r={r} fill="none" stroke="#059669" strokeWidth="10" strokeLinecap="round" strokeDasharray={`${(pct / 100) * c} ${c}`} />
        </svg>
        <p className="tabular absolute inset-0 grid place-items-center text-lg font-semibold">{value === null ? '—' : `${value}%`}</p>
      </div>
      <div>
        <p className="text-sm font-semibold text-ink">{label}</p>
        <p className="mt-1 text-[13px] text-muted">{sublabel}</p>
      </div>
    </div>
  );
}
