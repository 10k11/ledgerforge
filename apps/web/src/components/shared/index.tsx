import {
  AlertTriangle,
  ArrowDownLeft,
  ArrowLeftRight,
  ArrowUpRight,
  Check,
  ChevronLeft,
  ChevronRight,
  Copy,
  Landmark,
  PlusCircle,
  Receipt,
  RotateCcw,
  Undo2,
  type LucideIcon,
} from 'lucide-react';
import * as React from 'react';
import { Link } from 'react-router';
import { Button } from '@/components/ui/button';
import { Badge, Card, Skeleton } from '@/components/ui/primitives';
import { humanize } from '@/lib/format';
import { formatMoney } from '@/lib/money';
import { cn } from '@/lib/utils';
import type { TransactionType } from '@/types/api';

// ---------------------------------------------------------------- Money

export function Money({
  minor,
  currency,
  direction,
  className,
  compact,
}: {
  minor: number;
  currency: string;
  direction?: 'IN' | 'OUT';
  className?: string;
  compact?: boolean;
}) {
  const signed = direction === 'OUT' ? -Math.abs(minor) : direction === 'IN' ? Math.abs(minor) : minor;
  return (
    <span
      className={cn(
        'tabular whitespace-nowrap',
        direction === 'IN' && 'text-success',
        direction === 'OUT' && 'text-ink',
        className,
      )}
    >
      {formatMoney(signed, currency, { sign: direction ? 'always' : 'auto', compact })}
    </span>
  );
}

// ---------------------------------------------------------------- Status

const STATUS_TONES: Record<string, 'neutral' | 'success' | 'warning' | 'danger' | 'info'> = {
  SUCCEEDED: 'success',
  POSTED: 'success',
  COMPLETED: 'success',
  PAID: 'success',
  ACTIVE: 'success',
  REFUNDED: 'neutral',
  PARTIALLY_REFUNDED: 'info',
  CREATED: 'warning',
  PROCESSING: 'info',
  PENDING: 'warning',
  RETRYING: 'warning',
  FAILED: 'danger',
  DEAD: 'danger',
  CANCELLED: 'neutral',
  DENIED: 'danger',
  FAILURE: 'danger',
  SUCCESS: 'success',
};

const STATUS_LABELS: Record<string, string> = {
  CREATED: 'Awaiting confirmation',
  DEAD: 'Failed (dead-lettered)',
};

export function StatusBadge({ status, className }: { status: string; className?: string }) {
  return (
    <Badge tone={STATUS_TONES[status] ?? 'neutral'} dot className={className}>
      {STATUS_LABELS[status] ?? humanize(status)}
    </Badge>
  );
}

// ---------------------------------------------------------------- Transaction type

const TYPE_META: Record<TransactionType, { label: string; icon: LucideIcon }> = {
  TOP_UP: { label: 'Top-up', icon: PlusCircle },
  TRANSFER: { label: 'Transfer', icon: ArrowLeftRight },
  PAYMENT: { label: 'Payment', icon: Receipt },
  REFUND: { label: 'Refund', icon: Undo2 },
  PAYOUT: { label: 'Payout', icon: Landmark },
  REVERSAL: { label: 'Reversal', icon: RotateCcw },
};

export function typeLabel(type: string) {
  return TYPE_META[type as TransactionType]?.label ?? humanize(type);
}

export function TxTypeIcon({ type, direction }: { type: TransactionType; direction?: 'IN' | 'OUT' }) {
  const Icon = TYPE_META[type]?.icon ?? (direction === 'IN' ? ArrowDownLeft : ArrowUpRight);
  return (
    <span
      className={cn(
        'grid size-8 shrink-0 place-items-center rounded-lg ring-1 ring-inset',
        direction === 'IN' ? 'bg-success-soft text-success ring-emerald-100' : 'bg-subtle text-ink-soft ring-line',
      )}
      aria-hidden
    >
      <Icon className="size-4" />
    </span>
  );
}

// ---------------------------------------------------------------- Page header

export function PageHeader({
  title,
  description,
  breadcrumbs,
  actions,
}: {
  title: string;
  description?: React.ReactNode;
  breadcrumbs?: { label: string; to?: string }[];
  actions?: React.ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        {breadcrumbs && (
          <nav aria-label="Breadcrumb" className="mb-1.5 flex items-center gap-1.5 text-[13px] text-muted">
            {breadcrumbs.map((b, i) => (
              <React.Fragment key={b.label}>
                {i > 0 && <ChevronRight className="size-3.5 text-faint" aria-hidden />}
                {b.to ? (
                  <Link to={b.to} className="hover:text-ink">
                    {b.label}
                  </Link>
                ) : (
                  <span aria-current="page" className="truncate text-ink-soft">
                    {b.label}
                  </span>
                )}
              </React.Fragment>
            ))}
          </nav>
        )}
        <h1 className="text-[22px] font-semibold tracking-tight text-ink">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

// ---------------------------------------------------------------- States

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: {
  icon: LucideIcon;
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('flex flex-col items-center px-6 py-12 text-center', className)}>
      <span className="grid size-11 place-items-center rounded-xl bg-subtle text-muted ring-1 ring-inset ring-line">
        <Icon className="size-5" aria-hidden />
      </span>
      <p className="mt-4 text-sm font-medium text-ink">{title}</p>
      {description && <p className="mt-1 max-w-sm text-[13px] text-muted">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : 'Something went wrong';
  const correlationId = (error as { correlationId?: string } | null)?.correlationId;
  return (
    <div role="alert" className="flex flex-col items-center px-6 py-10 text-center">
      <span className="grid size-11 place-items-center rounded-xl bg-danger-soft text-danger-strong ring-1 ring-inset ring-red-100">
        <AlertTriangle className="size-5" aria-hidden />
      </span>
      <p className="mt-4 text-sm font-medium text-ink">Couldn't load this data</p>
      <p className="mt-1 max-w-md text-[13px] text-muted">{message}</p>
      {correlationId && <p className="mt-1 font-mono text-[11px] text-faint">Request ID {correlationId}</p>}
      {onRetry && (
        <Button variant="outline" size="sm" className="mt-5" onClick={onRetry}>
          <RotateCcw /> Try again
        </Button>
      )}
    </div>
  );
}

export function TableSkeleton({ rows = 5, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <div className="divide-y divide-line" aria-label="Loading" role="status">
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} className="flex items-center gap-4 px-5 py-4">
          <Skeleton className="size-8 rounded-lg" />
          {Array.from({ length: cols - 1 }).map((__, c) => (
            <Skeleton key={c} className={cn('h-3.5', c === 0 ? 'w-40' : 'w-20', c > 1 && 'hidden sm:block')} />
          ))}
        </div>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- Pagination

export function Pagination({
  page,
  totalPages,
  totalItems,
  size,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  size: number;
  onPage: (page: number) => void;
}) {
  if (totalItems === 0) return null;
  const from = page * size + 1;
  const to = Math.min(totalItems, (page + 1) * size);
  return (
    <div className="flex items-center justify-between border-t border-line px-5 py-3">
      <p className="text-[13px] text-muted">
        <span className="tabular font-medium text-ink-soft">
          {from}–{to}
        </span>{' '}
        of <span className="tabular font-medium text-ink-soft">{totalItems}</span>
      </p>
      <div className="flex items-center gap-1.5">
        <Button variant="outline" size="icon-sm" aria-label="Previous page" disabled={page === 0} onClick={() => onPage(page - 1)}>
          <ChevronLeft />
        </Button>
        <span className="tabular px-2 text-[13px] text-muted">
          {page + 1} / {Math.max(totalPages, 1)}
        </span>
        <Button
          variant="outline"
          size="icon-sm"
          aria-label="Next page"
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          <ChevronRight />
        </Button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- Copy

export function CopyButton({ value, label = 'Copy' }: { value: string; label?: string }) {
  const [copied, setCopied] = React.useState(false);
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={copied ? 'Copied' : label}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        } catch {
          /* clipboard unavailable (e.g. insecure context) */
        }
      }}
    >
      {copied ? <Check className="text-success" /> : <Copy />}
    </Button>
  );
}

// ---------------------------------------------------------------- Stat card

export function StatCard({
  label,
  value,
  hint,
  icon: Icon,
  loading,
  emphasis,
}: {
  label: string;
  value: React.ReactNode;
  hint?: React.ReactNode;
  icon?: LucideIcon;
  loading?: boolean;
  emphasis?: boolean;
}) {
  return (
    <Card className={cn('p-5', emphasis && 'border-primary/20 bg-gradient-to-b from-primary-soft/60 to-surface')}>
      <div className="flex items-center justify-between">
        <p className="text-[13px] font-medium text-muted">{label}</p>
        {Icon && <Icon className="size-4 text-faint" aria-hidden />}
      </div>
      {loading ? (
        <Skeleton className="mt-3 h-7 w-32" />
      ) : (
        <p className={cn('mt-2 tabular font-semibold tracking-tight text-ink', emphasis ? 'text-[26px]' : 'text-[22px]')}>
          {value}
        </p>
      )}
      {hint && <p className="mt-1 text-xs text-muted">{hint}</p>}
    </Card>
  );
}

// ---------------------------------------------------------------- JSON viewer

/**
 * Read-only JSON viewer with lightweight syntax highlighting. Tokens are rendered as React text
 * nodes (never innerHTML), so payload content cannot inject markup.
 */
export function JsonViewer({ value, className }: { value: unknown; className?: string }) {
  const json = React.useMemo(() => JSON.stringify(value, null, 2) ?? 'null', [value]);
  const tokens = React.useMemo(() => tokenize(json), [json]);
  return (
    <pre
      className={cn(
        'max-h-96 overflow-auto rounded-lg border border-line bg-[#0f172a] p-4 font-mono text-[12.5px] leading-relaxed text-slate-300',
        className,
      )}
    >
      <code>
        {tokens.map((t, i) => (
          <span key={i} className={TOKEN_CLASS[t.kind]}>
            {t.text}
          </span>
        ))}
      </code>
    </pre>
  );
}

type TokenKind = 'key' | 'string' | 'number' | 'literal' | 'punct';
const TOKEN_CLASS: Record<TokenKind, string> = {
  key: 'text-sky-300',
  string: 'text-emerald-300',
  number: 'text-amber-300',
  literal: 'text-violet-300',
  punct: 'text-slate-400',
};

function tokenize(json: string): { kind: TokenKind; text: string }[] {
  const out: { kind: TokenKind; text: string }[] = [];
  const re = /("(?:\\.|[^"\\])*")(\s*:)?|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(true|false|null)|([{}[\],:\s]+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(json)) !== null) {
    if (m[1] !== undefined) {
      out.push({ kind: m[2] ? 'key' : 'string', text: m[1] });
      if (m[2]) out.push({ kind: 'punct', text: m[2] });
    } else if (m[3] !== undefined) out.push({ kind: 'number', text: m[3] });
    else if (m[4] !== undefined) out.push({ kind: 'literal', text: m[4] });
    else out.push({ kind: 'punct', text: m[5]! });
  }
  return out;
}

export function DefinitionList({
  items,
  compact,
}: {
  items: { label: string; value: React.ReactNode }[];
  compact?: boolean;
}) {
  return (
    <dl className="divide-y divide-line">
      {items.map((it) => (
        <div
          key={it.label}
          className={cn('grid grid-cols-1 gap-1 py-3 sm:gap-4', compact ? 'sm:grid-cols-[112px_1fr]' : 'sm:grid-cols-[180px_1fr]')}
        >
          <dt className="text-[13px] text-muted">{it.label}</dt>
          <dd className="min-w-0 break-words text-sm text-ink">{it.value}</dd>
        </div>
      ))}
    </dl>
  );
}
