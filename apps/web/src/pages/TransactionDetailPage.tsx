import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, ArrowRight, ArrowUpRight, BookOpenCheck, Info, Scale } from 'lucide-react';
import { Link, useParams } from 'react-router';
import { CopyButton, DefinitionList, ErrorState, PageHeader, StatusBadge, TxTypeIcon, typeLabel } from '@/components/shared';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Skeleton, Tooltip } from '@/components/ui/primitives';
import { useDocumentTitle } from '@/hooks';
import { formatDateTime, humanize } from '@/lib/format';
import { formatMoney } from '@/lib/money';
import { cn } from '@/lib/utils';
import { transactionService } from '@/services';
import type { LedgerView } from '@/types/api';

function relatedLink(kind: string, id: string) {
  if (kind === 'PAYMENT') return `/payments/${id}`;
  if (kind === 'TRANSFER') return '/transfers';
  if (kind === 'PAYOUT') return '/merchants';
  return null;
}

const NORMAL_SIDE: Record<string, string> = {
  ASSET: 'Debit-normal: debits increase it',
  EXPENSE: 'Debit-normal: debits increase it',
  LIABILITY: 'Credit-normal: credits increase it',
  EQUITY: 'Credit-normal: credits increase it',
  REVENUE: 'Credit-normal: credits increase it',
};

/** Visual summary of a posting: debited accounts (money leaves) → credited accounts (money arrives). */
export function MoneyFlow({ ledger }: { ledger: LedgerView }) {
  const debits = ledger.lines.filter((l) => l.debitMinor > 0);
  const credits = ledger.lines.filter((l) => l.creditMinor > 0);
  const total = ledger.totalDebitsMinor || 1;
  const Node = ({ label, number, amount, mine, side }: { label: string; number: string; amount: number; mine: boolean; side: 'D' | 'C' }) => (
    <div className={cn('rounded-xl border bg-surface px-4 py-3 shadow-[0_1px_0_rgb(23_32_51/0.03)]', mine ? 'border-primary/40 ring-1 ring-primary/20' : 'border-line')}>
      <div className="flex items-center justify-between gap-3">
        <p className="truncate text-[13px] font-medium text-ink">{label}</p>
        <span className={cn('rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold', side === 'D' ? 'bg-subtle text-ink-soft' : 'bg-primary-soft text-primary')}>
          {side === 'D' ? 'DR' : 'CR'}
        </span>
      </div>
      <p className="font-mono text-[11px] text-muted">{number}</p>
      <p className="tabular mt-1.5 text-[15px] font-semibold">{formatMoney(amount, ledger.currency)}</p>
      <div className="mt-2 h-1 overflow-hidden rounded-full bg-subtle">
        <div className={cn('h-full rounded-full', side === 'D' ? 'bg-ink-soft' : 'bg-primary')} style={{ width: `${(amount / total) * 100}%` }} />
      </div>
    </div>
  );
  return (
    <div className="grid grid-cols-1 items-center gap-3 border-b border-line bg-gradient-to-b from-subtle/60 to-surface px-5 py-5 md:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)]">
      <div className="space-y-2">
        <p className="text-[11px] font-semibold uppercase tracking-wider text-muted">Debited</p>
        {debits.map((l) => (
          <Node key={l.entryId} label={l.accountLabel} number={l.accountNumber} amount={l.debitMinor} mine={l.ownedByViewer} side="D" />
        ))}
      </div>
      <div className="flex flex-col items-center gap-1 py-2 text-center">
        <span className="grid size-10 place-items-center rounded-full bg-ink text-white shadow-md">
          <ArrowRight className="size-4 max-md:rotate-90" aria-hidden />
        </span>
        <p className="tabular text-xs font-semibold text-ink">{formatMoney(ledger.totalDebitsMinor, ledger.currency)}</p>
        <p className="text-[10px] uppercase tracking-wider text-muted">{ledger.lines.length} postings</p>
      </div>
      <div className="space-y-2">
        <p className="text-[11px] font-semibold uppercase tracking-wider text-muted">Credited</p>
        {credits.map((l) => (
          <Node key={l.entryId} label={l.accountLabel} number={l.accountNumber} amount={l.creditMinor} mine={l.ownedByViewer} side="C" />
        ))}
      </div>
    </div>
  );
}

export function LedgerTable({ ledger }: { ledger: LedgerView }) {
  const dash = <span className="text-faint">—</span>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-y border-line bg-subtle/60 text-xs uppercase tracking-wide text-muted">
            <th scope="col" className="px-5 py-2.5 text-left font-medium">
              Account
            </th>
            <th scope="col" className="hidden px-4 py-2.5 text-left font-medium sm:table-cell">
              Type
            </th>
            <th scope="col" className="w-36 px-4 py-2.5 text-right font-medium">
              Debit
            </th>
            <th scope="col" className="w-36 px-5 py-2.5 text-right font-medium">
              Credit
            </th>
          </tr>
        </thead>
        <tbody>
          {ledger.lines.map((l) => (
            <tr key={l.entryId} className={cn('border-b border-line', l.ownedByViewer && 'bg-primary-soft/40')}>
              <td className="px-5 py-3">
                <p className="font-medium text-ink">{l.accountLabel}</p>
                <p className="font-mono text-xs text-muted">{l.accountNumber}</p>
              </td>
              <td className="hidden px-4 py-3 sm:table-cell">
                <Tooltip content={NORMAL_SIDE[l.accountType] ?? l.accountType}>
                  <span className="cursor-help border-b border-dotted border-faint text-[13px] text-ink-soft">{humanize(l.accountType)}</span>
                </Tooltip>
              </td>
              <td className="tabular px-4 py-3 text-right font-medium">{l.debitMinor ? formatMoney(l.debitMinor, ledger.currency) : dash}</td>
              <td className="tabular px-5 py-3 text-right font-medium">{l.creditMinor ? formatMoney(l.creditMinor, ledger.currency) : dash}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="bg-subtle/60 text-sm font-semibold">
            <td className="px-5 py-3" colSpan={1}>
              Totals
            </td>
            <td className="hidden sm:table-cell" />
            <td className="tabular px-4 py-3 text-right">{formatMoney(ledger.totalDebitsMinor, ledger.currency)}</td>
            <td className="tabular px-5 py-3 text-right">{formatMoney(ledger.totalCreditsMinor, ledger.currency)}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

export default function TransactionDetailPage() {
  const { transactionId = '' } = useParams();
  const detail = useQuery({ queryKey: ['transaction', transactionId], queryFn: () => transactionService.get(transactionId) });
  const ledger = useQuery({ queryKey: ['transaction-ledger', transactionId], queryFn: () => transactionService.ledger(transactionId) });
  useDocumentTitle(detail.data ? detail.data.reference : 'Transaction');
  const t = detail.data;
  const l = ledger.data;

  if (detail.isError)
    return (
      <Card>
        <ErrorState error={detail.error} onRetry={() => detail.refetch()} />
      </Card>
    );

  return (
    <>
      <PageHeader title={t ? `${typeLabel(t.type)} ${t.reference}` : 'Transaction'} breadcrumbs={[{ label: 'Transactions', to: '/transactions' }, { label: t?.reference ?? '…' }]} />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[360px_minmax(0,1fr)]">
        <Card className="h-fit">
          <CardContent>
            {!t ? (
              <div className="space-y-3">
                <Skeleton className="h-8 w-40" />
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-2/3" />
              </div>
            ) : (
              <>
                <div className="flex items-center gap-3">
                  <TxTypeIcon type={t.type} direction={t.direction} />
                  <div>
                    <p className="text-[13px] text-muted">{t.counterparty ?? typeLabel(t.type)}</p>
                    <p className={cn('tabular text-2xl font-semibold tracking-tight', t.direction === 'IN' && 'text-success')}>
                      {formatMoney(t.direction === 'OUT' ? -t.amountMinor : t.amountMinor, t.currency, { sign: 'always' })}
                    </p>
                  </div>
                </div>
                <p className="mt-1 text-xs text-muted">Net effect on your account(s)</p>
                <div className="mt-4">
                  <DefinitionList
                    compact
                    items={[
                      { label: 'Status', value: <StatusBadge status={t.status} /> },
                      { label: 'Type', value: typeLabel(t.type) },
                      {
                        label: 'Reference',
                        value: (
                          <span className="flex items-center gap-1 font-mono text-[13px]">
                            {t.reference} <CopyButton value={t.reference} label="Copy reference" />
                          </span>
                        ),
                      },
                      { label: 'Description', value: t.description },
                      { label: 'Currency', value: t.currency },
                      { label: 'Created', value: formatDateTime(t.createdAt) },
                      { label: 'Posted', value: formatDateTime(t.postedAt) },
                      ...(t.related
                        ? [
                            {
                              label: `Related ${humanize(t.related.kind)}`,
                              value: (() => {
                                const to = relatedLink(t.related.kind, t.related.id);
                                return to ? (
                                  <Link to={to} className="inline-flex items-center gap-1 font-mono text-[13px] text-primary hover:underline">
                                    {t.related.reference} <ArrowUpRight className="size-3.5" />
                                  </Link>
                                ) : (
                                  <span className="font-mono text-[13px]">{t.related.reference}</span>
                                );
                              })(),
                            },
                          ]
                        : []),
                      ...(t.reversesTransactionId
                        ? [
                            {
                              label: 'Reverses',
                              value: (
                                <Link to={`/transactions/${t.reversesTransactionId}`} className="font-mono text-[13px] text-primary hover:underline">
                                  {t.reversesReference}
                                </Link>
                              ),
                            },
                          ]
                        : []),
                      ...(t.reversedBy.length
                        ? [
                            {
                              label: 'Reversed by',
                              value: (
                                <span className="flex flex-col gap-0.5">
                                  {t.reversedBy.map((id) => (
                                    <Link key={id} to={`/transactions/${id}`} className="font-mono text-[13px] text-primary hover:underline">
                                      {id.slice(0, 8)}…
                                    </Link>
                                  ))}
                                </span>
                              ),
                            },
                          ]
                        : []),
                      ...(t.idempotencyKey
                        ? [{ label: 'Idempotency key', value: <span className="break-all font-mono text-xs text-muted">{t.idempotencyKey}</span> }]
                        : []),
                    ]}
                  />
                </div>
              </>
            )}
          </CardContent>
        </Card>

        <Card className="h-fit overflow-hidden">
          <CardHeader className="pb-4">
            <div>
              <CardTitle className="flex items-center gap-2">
                <BookOpenCheck className="size-4 text-muted" aria-hidden /> Ledger postings
              </CardTitle>
              <CardDescription>Immutable double-entry entries written atomically for this transaction.</CardDescription>
            </div>
            {l &&
              (l.balanced ? (
                <Badge tone="success">
                  <Scale className="size-3.5" aria-hidden /> Balanced
                </Badge>
              ) : (
                <Badge tone="danger">
                  <AlertTriangle className="size-3.5" aria-hidden /> Unbalanced
                </Badge>
              ))}
          </CardHeader>
          {ledger.isPending ? (
            <div className="space-y-2 px-5 pb-5">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          ) : ledger.isError ? (
            <ErrorState error={ledger.error} onRetry={() => ledger.refetch()} />
          ) : (
            <>
              <MoneyFlow ledger={l!} />
              <LedgerTable ledger={l!} />
              <div className="grid grid-cols-1 gap-3 p-5 sm:grid-cols-3">
                <div className="rounded-lg border border-line px-4 py-3">
                  <p className="text-xs text-muted">Total debits</p>
                  <p className="tabular mt-0.5 font-semibold">{formatMoney(l!.totalDebitsMinor, l!.currency)}</p>
                </div>
                <div className="rounded-lg border border-line px-4 py-3">
                  <p className="text-xs text-muted">Total credits</p>
                  <p className="tabular mt-0.5 font-semibold">{formatMoney(l!.totalCreditsMinor, l!.currency)}</p>
                </div>
                <div className="rounded-lg border border-line px-4 py-3">
                  <p className="text-xs text-muted">Posting status</p>
                  <p className="mt-0.5 font-semibold">{humanize(l!.status)}</p>
                </div>
              </div>
              <p className="flex items-start gap-2 border-t border-line px-5 py-3 text-xs text-muted">
                <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                Rows highlighted in blue are your accounts. Other parties' accounts are masked. Posted entries can never
                be edited; corrections are made with reversal transactions.
              </p>
            </>
          )}
        </Card>
      </div>
    </>
  );
}
