import { ArrowRight, ListOrdered } from 'lucide-react';
import { useNavigate } from 'react-router';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { EmptyState, Money, StatusBadge, TxTypeIcon, typeLabel } from '@/components/shared';
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/primitives';
import { formatDateTime, formatShortDay } from '@/lib/format';
import { formatMoney } from '@/lib/money';
import type { PaymentIntent, TransactionSummary } from '@/types/api';

/** Ledger-backed activity table: amounts are signed from the viewer's perspective. */
export function TransactionsTable({ items, emptyAction }: { items: TransactionSummary[]; emptyAction?: React.ReactNode }) {
  const navigate = useNavigate();
  if (items.length === 0) {
    return (
      <EmptyState
        icon={ListOrdered}
        title="No transactions yet"
        description="Activity appears here as soon as money moves in or out of your account."
        action={emptyAction}
      />
    );
  }
  return (
    <Table>
      <THead>
        <TR>
          <TH>Description</TH>
          <TH className="hidden md:table-cell">Reference</TH>
          <TH className="hidden sm:table-cell">Type</TH>
          <TH className="hidden lg:table-cell">Date</TH>
          <TH className="text-right">Amount</TH>
          <TH className="hidden sm:table-cell">Status</TH>
        </TR>
      </THead>
      <TBody>
        {items.map((t) => (
          <TR
            key={t.id}
            tabIndex={0}
            role="link"
            aria-label={`${typeLabel(t.type)} ${t.reference}`}
            onClick={() => navigate(`/transactions/${t.id}`)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') navigate(`/transactions/${t.id}`);
            }}
            className="cursor-pointer transition-colors hover:bg-subtle/60 focus-visible:bg-subtle/60 focus-visible:outline-none"
          >
            <TD>
              <div className="flex items-center gap-3">
                <TxTypeIcon type={t.type} direction={t.direction} />
                <div className="min-w-0">
                  <p className="max-w-[140px] truncate font-medium text-ink sm:max-w-none">{t.counterparty ?? typeLabel(t.type)}</p>
                  <p className="max-w-[140px] truncate text-xs text-muted sm:max-w-[260px]">{t.description}</p>
                </div>
              </div>
            </TD>
            <TD className="hidden font-mono text-xs text-ink-soft md:table-cell">{t.reference}</TD>
            <TD className="hidden text-ink-soft sm:table-cell">{typeLabel(t.type)}</TD>
            <TD className="hidden whitespace-nowrap text-ink-soft lg:table-cell">{formatDateTime(t.createdAt)}</TD>
            <TD className="text-right font-medium">
              <Money minor={t.amountMinor} currency={t.currency} direction={t.direction} />
            </TD>
            <TD className="hidden sm:table-cell">
              <StatusBadge status={t.status} />
            </TD>
          </TR>
        ))}
      </TBody>
    </Table>
  );
}

export function PaymentsTable({ items, perspective }: { items: PaymentIntent[]; perspective: 'CUSTOMER' | 'MERCHANT' | 'ADMIN' }) {
  const navigate = useNavigate();
  return (
    <Table>
      <THead>
        <TR>
          <TH>Payment</TH>
          <TH className="hidden md:table-cell">{perspective === 'CUSTOMER' ? 'Merchant' : 'Customer'}</TH>
          <TH className="text-right">Amount</TH>
          <TH className="hidden text-right lg:table-cell">Fee</TH>
          <TH className="hidden text-right lg:table-cell">Net</TH>
          <TH className="hidden sm:table-cell">Created</TH>
          <TH>Status</TH>
        </TR>
      </THead>
      <TBody>
        {items.map((p) => (
          <TR
            key={p.id}
            tabIndex={0}
            role="link"
            aria-label={`Payment ${p.reference}`}
            onClick={() => navigate(`/payments/${p.id}`)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') navigate(`/payments/${p.id}`);
            }}
            className="cursor-pointer transition-colors hover:bg-subtle/60 focus-visible:bg-subtle/60 focus-visible:outline-none"
          >
            <TD>
              <p className="font-mono text-xs font-medium text-ink">{p.reference}</p>
              <p className="max-w-[220px] truncate text-xs text-muted">{p.description ?? 'No description'}</p>
            </TD>
            <TD className="hidden text-ink-soft md:table-cell">
              {perspective === 'CUSTOMER' ? p.merchant?.name : p.customer?.name}
              {perspective !== 'CUSTOMER' && p.customer?.maskedEmail && (
                <span className="block text-xs text-muted">{p.customer.maskedEmail}</span>
              )}
            </TD>
            <TD className="text-right font-medium">
              <Money minor={p.amountMinor} currency={p.currency} />
            </TD>
            <TD className="hidden text-right text-ink-soft lg:table-cell">
              <Money minor={p.platformFeeMinor} currency={p.currency} />
            </TD>
            <TD className="hidden text-right text-ink-soft lg:table-cell">
              <Money minor={p.merchantNetMinor} currency={p.currency} />
            </TD>
            <TD className="hidden whitespace-nowrap text-ink-soft sm:table-cell">{formatDateTime(p.createdAt)}</TD>
            <TD>
              <StatusBadge status={p.status} />
            </TD>
          </TR>
        ))}
      </TBody>
    </Table>
  );
}

function ChartTooltip({
  active,
  payload,
  label,
  currency,
  labels,
}: {
  active?: boolean;
  payload?: { dataKey: string; value: number; color: string }[];
  label?: string;
  currency: string;
  labels: Record<string, string>;
}) {
  if (!active || !payload?.length || !label) return null;
  return (
    <div className="rounded-lg border border-line bg-surface px-3 py-2 text-xs shadow-pop">
      <p className="mb-1.5 font-medium text-ink">{formatShortDay(label)}</p>
      {payload.map((p) => (
        <p key={p.dataKey} className="flex items-center gap-2 text-ink-soft">
          <span className="size-2 rounded-full" style={{ background: p.color }} />
          <span className="w-16">{labels[p.dataKey]}</span>
          <span className="tabular font-medium text-ink">{formatMoney(p.value, currency)}</span>
        </p>
      ))}
    </div>
  );
}

const axisMoney = (currency: string) => (v: number) => formatMoney(v, currency, { compact: true }).replace(/\.00$/, '');

/** 30-day money in / money out, plotted from ledger entries (minor units, formatted on display). */
export function ActivityChart({ data, currency }: { data: { date: string; inMinor: number; outMinor: number }[]; currency: string }) {
  return (
    <div className="h-64 w-full" role="img" aria-label="Money in and money out over the last 30 days">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 8, right: 8, left: 8, bottom: 0 }}>
          <defs>
            <linearGradient id="inFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#059669" stopOpacity={0.18} />
              <stop offset="100%" stopColor="#059669" stopOpacity={0} />
            </linearGradient>
            <linearGradient id="outFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#2563eb" stopOpacity={0.12} />
              <stop offset="100%" stopColor="#2563eb" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke="#eef1f6" vertical={false} />
          <XAxis
            dataKey="date"
            tickFormatter={formatShortDay}
            tick={{ fontSize: 11, fill: '#64748b' }}
            axisLine={false}
            tickLine={false}
            minTickGap={28}
          />
          <YAxis
            tickFormatter={axisMoney(currency)}
            tick={{ fontSize: 11, fill: '#64748b' }}
            axisLine={false}
            tickLine={false}
            width={72}
          />
          <Tooltip
            content={<ChartTooltip currency={currency} labels={{ inMinor: 'Money in', outMinor: 'Money out' }} />}
            cursor={{ stroke: '#cbd5e1' }}
          />
          <Area type="monotone" dataKey="inMinor" stroke="#059669" strokeWidth={2} fill="url(#inFill)" isAnimationActive={false} />
          <Area type="monotone" dataKey="outMinor" stroke="#2563eb" strokeWidth={2} fill="url(#outFill)" isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

export function VolumeChart({
  data,
  currency,
}: {
  data: { date: string; grossMinor: number; refundsMinor: number }[];
  currency: string;
}) {
  return (
    <div className="h-64 w-full" role="img" aria-label="Daily captured volume and refunds">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, left: 8, bottom: 0 }} barGap={2}>
          <CartesianGrid stroke="#eef1f6" vertical={false} />
          <XAxis
            dataKey="date"
            tickFormatter={formatShortDay}
            tick={{ fontSize: 11, fill: '#64748b' }}
            axisLine={false}
            tickLine={false}
            minTickGap={28}
          />
          <YAxis tickFormatter={axisMoney(currency)} tick={{ fontSize: 11, fill: '#64748b' }} axisLine={false} tickLine={false} width={72} />
          <Tooltip
            content={<ChartTooltip currency={currency} labels={{ grossMinor: 'Captured', refundsMinor: 'Refunded' }} />}
            cursor={{ fill: '#f1f4f9' }}
          />
          <Bar dataKey="grossMinor" fill="#2563eb" radius={[3, 3, 0, 0]} maxBarSize={18} isAnimationActive={false} />
          <Bar dataKey="refundsMinor" fill="#f59e0b" radius={[3, 3, 0, 0]} maxBarSize={18} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

export function ChartLegend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <div className="flex items-center gap-4 text-xs text-muted">
      {items.map((i) => (
        <span key={i.label} className="flex items-center gap-1.5">
          <span className="size-2 rounded-full" style={{ background: i.color }} aria-hidden />
          {i.label}
        </span>
      ))}
    </div>
  );
}

export function ViewAllLink({ to, label = 'View all' }: { to: string; label?: string }) {
  const navigate = useNavigate();
  return (
    <button onClick={() => navigate(to)} className="flex items-center gap-1 text-[13px] font-medium text-primary hover:underline">
      {label} <ArrowRight className="size-3.5" aria-hidden />
    </button>
  );
}
