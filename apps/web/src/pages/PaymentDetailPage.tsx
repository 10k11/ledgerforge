import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowUpRight, Ban, Check, CircleDot, Undo2, X } from 'lucide-react';
import * as React from 'react';
import { Link, useParams } from 'react-router';
import { toast } from 'sonner';
import { DefinitionList, ErrorState, Money, PageHeader, StatusBadge } from '@/components/shared';
import { Button } from '@/components/ui/button';
import { Field, Input, MoneyInput } from '@/components/ui/form';
import { ConfirmDialog } from '@/components/ui/overlay';
import { Card, CardContent, CardDescription, CardHeader, CardTitle, Skeleton, Table, TBody, TD, TH, THead, TR } from '@/components/ui/primitives';
import { useAuth } from '@/features/auth/AuthProvider';
import { invalidateMoney, useConfirmPayment } from '@/features/payments';
import { useDocumentTitle, useIdempotencyKey } from '@/hooks';
import { errorMessage } from '@/lib/api';
import { formatDateTime } from '@/lib/format';
import { currencySymbol, formatBps, formatMoney, parseMoneyInput } from '@/lib/money';
import { cn } from '@/lib/utils';
import { paymentService } from '@/services';
import type { PaymentIntent } from '@/types/api';

function Timeline({ p }: { p: PaymentIntent }) {
  const steps: { label: string; at?: string; state: 'done' | 'failed' | 'pending' }[] = [
    { label: 'Created', at: p.createdAt, state: 'done' },
  ];
  if (p.status === 'CANCELLED') steps.push({ label: 'Cancelled', at: p.cancelledAt, state: 'failed' });
  else if (p.status === 'FAILED') steps.push({ label: 'Declined', at: p.updatedAt, state: 'failed' });
  else if (p.capturedAt) steps.push({ label: 'Captured (ledger posted)', at: p.capturedAt, state: 'done' });
  else steps.push({ label: 'Awaiting confirmation', state: 'pending' });
  if (p.status === 'PARTIALLY_REFUNDED') steps.push({ label: 'Partially refunded', at: p.updatedAt, state: 'done' });
  if (p.status === 'REFUNDED') steps.push({ label: 'Fully refunded', at: p.updatedAt, state: 'done' });
  return (
    <ol className="space-y-4">
      {steps.map((s, i) => (
        <li key={s.label} className="relative flex gap-3">
          {i < steps.length - 1 && <span className="absolute left-[11px] top-6 h-full w-px bg-line" aria-hidden />}
          <span
            className={cn(
              'relative grid size-6 shrink-0 place-items-center rounded-full ring-1 ring-inset',
              s.state === 'done' && 'bg-success-soft text-success ring-emerald-200',
              s.state === 'failed' && 'bg-danger-soft text-danger-strong ring-red-200',
              s.state === 'pending' && 'bg-warning-soft text-warning ring-amber-200',
            )}
          >
            {s.state === 'done' ? <Check className="size-3.5" /> : s.state === 'failed' ? <X className="size-3.5" /> : <CircleDot className="size-3.5" />}
          </span>
          <div>
            <p className="text-sm font-medium text-ink">{s.label}</p>
            <p className="text-xs text-muted">{s.at ? formatDateTime(s.at) : 'Pending'}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}

function RefundDialog({ payment, open, onOpenChange }: { payment: PaymentIntent; open: boolean; onOpenChange: (o: boolean) => void }) {
  const queryClient = useQueryClient();
  const { key, reset } = useIdempotencyKey();
  const [mode, setMode] = React.useState<'full' | 'partial'>('full');
  const [amount, setAmount] = React.useState('');
  const [reason, setReason] = React.useState('');
  const [error, setError] = React.useState<string | null>(null);
  const refund = useMutation({
    mutationFn: (amountMinor?: number) => paymentService.refund(payment.id, { amountMinor, reason: reason.trim() || undefined }, key),
    onSuccess: (r) => {
      reset();
      invalidateMoney(queryClient);
      toast.success('Refund issued', { description: `${formatMoney(r.amountMinor, r.currency)} · ${r.reference}` });
      onOpenChange(false);
      setAmount('');
      setReason('');
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const submit = () => {
    if (mode === 'full') return refund.mutate(undefined);
    const parsed = parseMoneyInput(amount);
    if (!parsed.ok) return setError(parsed.error);
    if (parsed.minor > payment.refundableAmountMinor)
      return setError(`At most ${formatMoney(payment.refundableAmountMinor, payment.currency)} can be refunded`);
    refund.mutate(parsed.minor);
  };
  return (
    <ConfirmDialog
      open={open}
      onOpenChange={(o) => {
        if (!refund.isPending) {
          onOpenChange(o);
          setError(null);
        }
      }}
      title="Refund payment"
      description={`Refundable: ${formatMoney(payment.refundableAmountMinor, payment.currency)} of ${formatMoney(payment.amountMinor, payment.currency)}`}
      confirmLabel="Issue refund"
      tone="danger"
      loading={refund.isPending}
      onConfirm={submit}
    >
      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label="Refund type">
          {(['full', 'partial'] as const).map((m) => (
            <button
              key={m}
              type="button"
              role="radio"
              aria-checked={mode === m}
              onClick={() => { setMode(m); reset(); setError(null); }}
              className={cn('rounded-lg border px-3 py-2 text-sm font-medium', mode === m ? 'border-primary bg-primary-soft text-primary' : 'border-line-strong text-ink-soft hover:bg-subtle')}
            >
              {m === 'full' ? 'Full remaining' : 'Partial amount'}
            </button>
          ))}
        </div>
        {mode === 'partial' && (
          <Field id="refund-amount" label="Refund amount">
            <MoneyInput symbol={currencySymbol(payment.currency)} placeholder="0.00" value={amount} onChange={(e) => { setAmount(e.target.value); reset(); setError(null); }} />
          </Field>
        )}
        <Field id="refund-reason" label="Reason (optional)">
          <Input value={reason} maxLength={255} placeholder="Customer returned the item" onChange={(e) => setReason(e.target.value)} />
        </Field>
        <p className="rounded-lg bg-subtle px-3 py-2 text-xs text-muted">
          Posted as a reversal of the original ledger transaction: your payable is debited and the platform fee is
          reversed proportionally, so refunds always reconcile exactly with the original payment.
        </p>
        {error && (
          <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-[13px] text-danger-strong">
            {error}
          </p>
        )}
      </div>
    </ConfirmDialog>
  );
}

export default function PaymentDetailPage() {
  const { paymentId = '' } = useParams();
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const payment = useQuery({ queryKey: ['payment', paymentId], queryFn: () => paymentService.get(paymentId) });
  const refunds = useQuery({ queryKey: ['refunds', paymentId], queryFn: () => paymentService.refunds(paymentId), enabled: payment.isSuccess });
  const [refundOpen, setRefundOpen] = React.useState(false);
  const [cancelOpen, setCancelOpen] = React.useState(false);
  const confirm = useConfirmPayment();
  const cancel = useMutation({
    mutationFn: () => paymentService.cancel(paymentId),
    onSuccess: () => {
      invalidateMoney(queryClient);
      setCancelOpen(false);
      toast.success('Payment cancelled');
    },
    onError: (e) => toast.error('Could not cancel', { description: errorMessage(e) }),
  });
  useDocumentTitle(payment.data ? `Payment ${payment.data.reference}` : 'Payment');

  if (payment.isError)
    return (
      <Card>
        <ErrorState error={payment.error} onRetry={() => payment.refetch()} />
      </Card>
    );
  const p = payment.data;
  const isCustomer = user?.role === 'CUSTOMER';
  const canRefund = (user?.role === 'MERCHANT' || user?.role === 'ADMIN') && p && p.refundableAmountMinor > 0;

  return (
    <>
      <PageHeader
        title={p ? p.reference : 'Payment'}
        breadcrumbs={[{ label: 'Payments', to: '/payments' }, { label: p?.reference ?? '…' }]}
        actions={
          p && (
            <>
              {isCustomer && p.status === 'CREATED' && (
                <>
                  <Button variant="outline" onClick={() => setCancelOpen(true)}>
                    <Ban /> Cancel
                  </Button>
                  <Button
                    loading={confirm.isPending}
                    onClick={() =>
                      confirm.mutate(p.id, { onError: (e) => toast.error('Payment not completed', { description: errorMessage(e) }) })
                    }
                  >
                    Confirm payment
                  </Button>
                </>
              )}
              {canRefund && (
                <Button variant="outline" onClick={() => setRefundOpen(true)}>
                  <Undo2 /> Refund
                </Button>
              )}
              {p.ledgerTransactionId && (
                <Button variant="outline" asChild>
                  <Link to={`/transactions/${p.ledgerTransactionId}`}>
                    Ledger entries <ArrowUpRight />
                  </Link>
                </Button>
              )}
            </>
          )
        }
      />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-6">
          <Card>
            <CardContent>
              {!p ? (
                <Skeleton className="h-24 w-full" />
              ) : (
                <div className="flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
                  <div>
                    <p className="text-[13px] text-muted">{isCustomer ? `Paid to ${p.merchant?.name}` : `From ${p.customer?.name}`}</p>
                    <p className="tabular mt-1 text-[32px] font-semibold tracking-tight">{formatMoney(p.amountMinor, p.currency)}</p>
                    <div className="mt-2">
                      <StatusBadge status={p.status} />
                    </div>
                  </div>
                  <div className="grid grid-cols-3 gap-px overflow-hidden rounded-xl border border-line bg-line text-center">
                    {[
                      ['Gross', p.amountMinor],
                      [`Fee (${formatBps(p.feeBps)})`, p.platformFeeMinor],
                      ['Merchant net', p.merchantNetMinor],
                    ].map(([l, v]) => (
                      <div key={l as string} className="bg-surface px-4 py-3">
                        <p className="text-[11px] uppercase tracking-wide text-muted">{l}</p>
                        <p className="tabular mt-0.5 text-sm font-semibold">{formatMoney(v as number, p.currency)}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Details</CardTitle>
            </CardHeader>
            <CardContent className="pt-2">
              {p && (
                <DefinitionList
                  items={[
                    { label: 'Payment ID', value: <span className="font-mono text-[13px]">{p.id}</span> },
                    { label: 'Description', value: p.description ?? '—' },
                    { label: 'Merchant', value: p.merchant?.name ?? '—' },
                    {
                      label: 'Customer',
                      value: p.customer ? `${p.customer.name}${p.customer.maskedEmail ? ` · ${p.customer.maskedEmail}` : ''}` : '—',
                    },
                    { label: 'Refunded', value: <Money minor={p.refundedAmountMinor} currency={p.currency} /> },
                    { label: 'Refundable', value: <Money minor={p.refundableAmountMinor} currency={p.currency} /> },
                    ...(p.failureCode ? [{ label: 'Failure', value: `${p.failureCode}: ${p.failureMessage ?? ''}` }] : []),
                    {
                      label: 'Metadata',
                      value: Object.keys(p.metadata).length ? (
                        <span className="font-mono text-[13px]">
                          {Object.entries(p.metadata).map(([k, v]) => `${k}=${v}`).join(', ')}
                        </span>
                      ) : (
                        '—'
                      ),
                    },
                  ]}
                />
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-4">
              <div>
                <CardTitle>Refunds</CardTitle>
                <CardDescription>Each refund is a separate ledger transaction reversing part of the original.</CardDescription>
              </div>
            </CardHeader>
            <div className="border-t border-line">
              {refunds.data && refunds.data.length > 0 ? (
                <Table>
                  <THead>
                    <TR>
                      <TH>Refund</TH>
                      <TH className="text-right">Amount</TH>
                      <TH className="hidden text-right sm:table-cell">Fee reversed</TH>
                      <TH className="hidden md:table-cell">Created</TH>
                    </TR>
                  </THead>
                  <TBody>
                    {refunds.data.map((r) => (
                      <TR key={r.id}>
                        <TD>
                          <Link to={`/transactions/${r.ledgerTransactionId}`} className="font-mono text-xs font-medium hover:text-primary">
                            {r.reference}
                          </Link>
                          <p className="text-xs text-muted">{r.reason ?? 'No reason given'}</p>
                        </TD>
                        <TD className="text-right font-medium">
                          <Money minor={r.amountMinor} currency={r.currency} />
                        </TD>
                        <TD className="hidden text-right text-ink-soft sm:table-cell">
                          <Money minor={r.feeReversalMinor} currency={r.currency} />
                        </TD>
                        <TD className="hidden whitespace-nowrap text-ink-soft md:table-cell">{formatDateTime(r.createdAt)}</TD>
                      </TR>
                    ))}
                  </TBody>
                </Table>
              ) : (
                <p className="px-5 py-8 text-center text-[13px] text-muted">No refunds have been issued for this payment.</p>
              )}
            </div>
          </Card>
        </div>
        <Card className="h-fit">
          <CardHeader>
            <CardTitle>Lifecycle</CardTitle>
          </CardHeader>
          <CardContent>{p ? <Timeline p={p} /> : <Skeleton className="h-32 w-full" />}</CardContent>
        </Card>
      </div>
      {p && canRefund && <RefundDialog payment={p} open={refundOpen} onOpenChange={setRefundOpen} />}
      <ConfirmDialog
        open={cancelOpen}
        onOpenChange={setCancelOpen}
        title="Cancel this payment?"
        description="The payment intent will be closed. No money has moved yet."
        confirmLabel="Cancel payment"
        tone="danger"
        loading={cancel.isPending}
        onConfirm={() => cancel.mutate()}
      />
    </>
  );
}
