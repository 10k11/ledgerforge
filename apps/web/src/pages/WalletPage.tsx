import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, Download, FileText, FlaskConical, PlusCircle, Wallet } from 'lucide-react';
import * as React from 'react';
import { Link, useSearchParams } from 'react-router';
import { toast } from 'sonner';
import { CopyButton, DefinitionList, EmptyState, ErrorState, Money, PageHeader, Pagination, TableSkeleton, typeLabel } from '@/components/shared';
import { Button } from '@/components/ui/button';
import { Field, Input, MoneyInput } from '@/components/ui/form';
import { Dialog, DialogContent, DialogFooter } from '@/components/ui/overlay';
import { Badge, Card, CardDescription, CardHeader, CardTitle, Skeleton, Table, TBody, TD, TH, THead, TR } from '@/components/ui/primitives';
import { useDocumentTitle, useIdempotencyKey } from '@/hooks';
import { errorMessage } from '@/lib/api';
import { endOfDayExclusiveIso, formatDate, formatDateTime, startOfDayIso } from '@/lib/format';
import { currencySymbol, formatMoney, parseMoneyInput } from '@/lib/money';
import { walletService } from '@/services';
import type { TopUpResponse, WalletView } from '@/types/api';

type Step = 'amount' | 'confirm' | 'done';

export function TopUpDialog({ wallet, open, onOpenChange }: { wallet: WalletView; open: boolean; onOpenChange: (o: boolean) => void }) {
  const queryClient = useQueryClient();
  const { key, reset } = useIdempotencyKey();
  const [step, setStep] = React.useState<Step>('amount');
  const [raw, setRaw] = React.useState('');
  const [error, setError] = React.useState<string | null>(null);
  const [result, setResult] = React.useState<TopUpResponse | null>(null);
  const parsed = parseMoneyInput(raw);
  const remainingToday = Math.max(0, wallet.topUpLimits.dailyLimitMinor - wallet.topUpLimits.usedTodayMinor);

  const mutation = useMutation({
    mutationFn: (minor: number) => walletService.topUp(minor, wallet.currency, key),
    onSuccess: ({ data }) => {
      setResult(data);
      setStep('done');
      reset();
      // Balances are refetched from the server; nothing is updated optimistically.
      queryClient.invalidateQueries({ queryKey: ['wallet'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      queryClient.invalidateQueries({ queryKey: ['transactions'] });
      queryClient.invalidateQueries({ queryKey: ['statement'] });
      toast.success('Funds added', { description: `${formatMoney(data.amountMinor, data.currency)} · ${data.reference}` });
    },
    onError: (e) => setError(errorMessage(e)),
  });

  const close = (o: boolean) => {
    if (mutation.isPending) return;
    onOpenChange(o);
    if (!o) {
      setTimeout(() => {
        setStep('amount');
        setRaw('');
        setError(null);
        setResult(null);
      }, 150);
    }
  };

  const validate = (): string | null => {
    if (!parsed.ok) return parsed.error;
    if (parsed.minor > wallet.topUpLimits.maxPerTopUpMinor)
      return `Maximum per top-up is ${formatMoney(wallet.topUpLimits.maxPerTopUpMinor, wallet.currency)}`;
    if (parsed.minor > remainingToday) return `Only ${formatMoney(remainingToday, wallet.currency)} remaining today`;
    return null;
  };

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent
        title={step === 'done' ? 'Funds added' : 'Add sandbox funds'}
        description={step === 'done' ? undefined : 'Simulated money for testing. No card or bank is charged.'}
      >
        {step === 'amount' && (
          <form
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              const v = validate();
              setError(v);
              if (!v) setStep('confirm');
            }}
          >
            <div className="mb-4 flex gap-3 rounded-lg border border-amber-200 bg-warning-soft p-3 text-[13px] text-amber-900">
              <FlaskConical className="mt-0.5 size-4 shrink-0" aria-hidden />
              <p>
                Sandbox top-ups post <strong>Debit Platform Funding Clearing</strong> /{' '}
                <strong>Credit Customer Wallet</strong>. They are disabled in production.
              </p>
            </div>
            <Field
              id="topup-amount"
              label="Amount"
              error={error ?? undefined}
              hint={`Up to ${formatMoney(wallet.topUpLimits.maxPerTopUpMinor, wallet.currency)} per top-up · ${formatMoney(remainingToday, wallet.currency)} left today`}
            >
              <MoneyInput
                symbol={currencySymbol(wallet.currency)}
                placeholder="0.00"
                value={raw}
                autoFocus
                onChange={(e) => {
                  setRaw(e.target.value);
                  setError(null);
                  reset(); // a different amount is a different logical operation
                }}
              />
            </Field>
            <div className="mt-3 flex flex-wrap gap-2">
              {[100000, 500000, 2500000].map((m) => (
                <Button key={m} type="button" variant="secondary" size="sm" onClick={() => { setRaw(String(m / 100)); setError(null); reset(); }}>
                  {formatMoney(m, wallet.currency)}
                </Button>
              ))}
            </div>
            <Field id="topup-currency" label="Currency" className="mt-4">
              <Input value={wallet.currency} disabled readOnly />
            </Field>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => close(false)}>
                Cancel
              </Button>
              <Button type="submit">Review</Button>
            </DialogFooter>
          </form>
        )}
        {step === 'confirm' && parsed.ok && (
          <div>
            <div className="rounded-xl border border-line bg-subtle/50 p-4 text-center">
              <p className="text-[13px] text-muted">You are adding</p>
              <p className="tabular mt-1 text-3xl font-semibold tracking-tight">{formatMoney(parsed.minor, wallet.currency)}</p>
              <p className="mt-1 text-[13px] text-muted">to wallet {wallet.accountNumber}</p>
            </div>
            <div className="mt-4 text-[13px]">
              <DefinitionList
                items={[
                  { label: 'Balance after', value: <Money minor={wallet.balanceMinor + parsed.minor} currency={wallet.currency} /> },
                  { label: 'Idempotency key', value: <span className="font-mono text-xs text-muted">{key}</span> },
                ]}
              />
            </div>
            {error && (
              <p role="alert" className="mt-3 rounded-lg bg-danger-soft px-3 py-2 text-[13px] text-danger-strong">
                {error} You can retry safely: the same idempotency key prevents a duplicate top-up.
              </p>
            )}
            <DialogFooter>
              <Button variant="outline" onClick={() => setStep('amount')} disabled={mutation.isPending}>
                Back
              </Button>
              <Button onClick={() => mutation.mutate(parsed.minor)} loading={mutation.isPending}>
                Confirm top-up
              </Button>
            </DialogFooter>
          </div>
        )}
        {step === 'done' && result && (
          <div className="text-center">
            <CheckCircle2 className="mx-auto size-10 text-success" aria-hidden />
            <p className="tabular mt-3 text-2xl font-semibold">{formatMoney(result.amountMinor, result.currency)}</p>
            <p className="mt-1 text-sm text-muted">
              New balance <span className="tabular font-medium text-ink">{formatMoney(result.balanceAfterMinor, result.currency)}</span>
            </p>
            <p className="mt-3 font-mono text-xs text-muted">{result.reference}</p>
            <DialogFooter className="sm:justify-center">
              <Button variant="outline" asChild>
                <Link to={`/transactions/${result.transactionId}`}>View ledger entries</Link>
              </Button>
              <Button onClick={() => close(false)}>Done</Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function Statement({ currency }: { currency: string }) {
  const today = new Date().toISOString().slice(0, 10);
  const monthAgo = new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10);
  const [from, setFrom] = React.useState(monthAgo);
  const [to, setTo] = React.useState(today);
  const [page, setPage] = React.useState(0);
  const params = { from: from ? startOfDayIso(from) : undefined, to: to ? endOfDayExclusiveIso(to) : undefined, page, size: 15 };
  const statement = useQuery({
    queryKey: ['statement', params],
    queryFn: () => walletService.statement(params),
    placeholderData: keepPreviousData,
  });
  const exportCsv = async () => {
    try {
      const full = await walletService.statement({ ...params, page: 0, size: 100 });
      const rows = [
        ['Posted at', 'Reference', 'Type', 'Description', 'Direction', 'Amount', 'Running balance', 'Currency'],
        ...full.lines.map((l) => [
          l.postedAt,
          l.reference,
          l.type,
          l.description,
          l.direction,
          formatMoney(l.amountMinor, '').trim(),
          formatMoney(l.runningBalanceMinor, '').trim(),
          full.currency,
        ]),
      ];
      const csv = rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n');
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `ledgerforge-statement-${from}-to-${to}.csv`;
      a.click();
      URL.revokeObjectURL(url);
      if (full.totalItems > full.lines.length) toast.info('Exported the latest 100 lines of this period');
    } catch (e) {
      toast.error('Export failed', { description: errorMessage(e) });
    }
  };
  const s = statement.data;
  return (
    <Card>
      <CardHeader className="flex-col gap-3 pb-4 sm:flex-row sm:items-end">
        <div>
          <CardTitle>Statement</CardTitle>
          <CardDescription>Opening and closing balances with a running balance per posting.</CardDescription>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Input type="date" value={from} max={to} onChange={(e) => { setFrom(e.target.value); setPage(0); }} aria-label="Statement from" className="w-40" />
          <span className="text-muted">–</span>
          <Input type="date" value={to} min={from} onChange={(e) => { setTo(e.target.value); setPage(0); }} aria-label="Statement to" className="w-40" />
          <Button variant="outline" onClick={exportCsv} disabled={!s || s.totalItems === 0}>
            <Download /> CSV
          </Button>
        </div>
      </CardHeader>
      {s && (
        <div className="grid grid-cols-2 gap-px border-y border-line bg-line sm:grid-cols-4">
          {[
            ['Opening balance', s.openingBalanceMinor],
            ['Money in', s.totalCreditsMinor],
            ['Money out', s.totalDebitsMinor],
            ['Closing balance', s.closingBalanceMinor],
          ].map(([label, v]) => (
            <div key={label as string} className="bg-surface px-5 py-3">
              <p className="text-xs text-muted">{label}</p>
              <p className="tabular mt-0.5 text-[15px] font-semibold">{formatMoney(v as number, currency)}</p>
            </div>
          ))}
        </div>
      )}
      {statement.isPending ? (
        <TableSkeleton rows={4} />
      ) : statement.isError ? (
        <ErrorState error={statement.error} onRetry={() => statement.refetch()} />
      ) : s!.lines.length === 0 ? (
        <EmptyState icon={FileText} title="No postings in this period" description="Choose a wider date range or add funds." />
      ) : (
        <>
          <Table>
            <THead>
              <TR>
                <TH>Posted</TH>
                <TH>Description</TH>
                <TH className="hidden md:table-cell">Reference</TH>
                <TH className="text-right">Amount</TH>
                <TH className="text-right">Balance</TH>
              </TR>
            </THead>
            <TBody>
              {s!.lines.map((l) => (
                <TR key={l.transactionId + l.direction}>
                  <TD className="whitespace-nowrap text-ink-soft">{formatDateTime(l.postedAt)}</TD>
                  <TD>
                    <Link to={`/transactions/${l.transactionId}`} className="font-medium hover:text-primary">
                      {typeLabel(l.type)}
                    </Link>
                    <p className="max-w-xs truncate text-xs text-muted">{l.description}</p>
                  </TD>
                  <TD className="hidden font-mono text-xs text-ink-soft md:table-cell">{l.reference}</TD>
                  <TD className="text-right font-medium">
                    <Money minor={l.amountMinor} currency={currency} direction={l.direction} />
                  </TD>
                  <TD className="tabular text-right text-ink-soft">{formatMoney(l.runningBalanceMinor, currency)}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
          <Pagination page={s!.page} size={s!.size} totalItems={s!.totalItems} totalPages={s!.totalPages} onPage={setPage} />
        </>
      )}
    </Card>
  );
}

export default function WalletPage() {
  useDocumentTitle('Wallet');
  const [params, setParams] = useSearchParams();
  const wallet = useQuery({ queryKey: ['wallet'], queryFn: walletService.get });
  const topUpOpen = params.get('topup') === '1';
  const setTopUpOpen = (o: boolean) => setParams(o ? { topup: '1' } : {}, { replace: true });
  const w = wallet.data;

  return (
    <>
      <PageHeader
        title="Wallet"
        description="Your balance is a projection of the ledger, recomputed and verified by reconciliation."
        actions={
          w?.sandboxEnabled && (
            <Button onClick={() => setTopUpOpen(true)}>
              <PlusCircle /> Add funds
            </Button>
          )
        }
      />
      {wallet.isError ? (
        <Card>
          <ErrorState error={wallet.error} onRetry={() => wallet.refetch()} />
        </Card>
      ) : (
        <div className="mb-6 grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Card className="relative overflow-hidden p-6 lg:col-span-2">
            <div className="flex items-start justify-between">
              <div>
                <p className="flex items-center gap-2 text-[13px] font-medium text-muted">
                  <Wallet className="size-4" aria-hidden /> Available balance
                </p>
                {w ? (
                  <p className="tabular mt-3 text-[40px] font-semibold leading-none tracking-tight">
                    {formatMoney(w.balanceMinor, w.currency)}
                  </p>
                ) : (
                  <Skeleton className="mt-3 h-10 w-56" />
                )}
                <p className="mt-3 text-[13px] text-muted">{w?.accountName}</p>
              </div>
              {w && (
                <Badge tone={w.status === 'ACTIVE' ? 'success' : 'warning'} dot>
                  {w.status === 'ACTIVE' ? 'Active' : w.status}
                </Badge>
              )}
            </div>
            {w?.sandboxEnabled && (
              <div className="mt-6">
                <div className="flex justify-between text-xs text-muted">
                  <span>Sandbox funding used today</span>
                  <span className="tabular">
                    {formatMoney(w.topUpLimits.usedTodayMinor, w.currency)} / {formatMoney(w.topUpLimits.dailyLimitMinor, w.currency)}
                  </span>
                </div>
                <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-subtle" role="progressbar" aria-valuemin={0} aria-valuemax={100}
                  aria-valuenow={Math.round((w.topUpLimits.usedTodayMinor / w.topUpLimits.dailyLimitMinor) * 100)} aria-label="Daily sandbox funding used">
                  <div className="h-full rounded-full bg-primary" style={{ width: `${Math.min(100, (w.topUpLimits.usedTodayMinor / w.topUpLimits.dailyLimitMinor) * 100)}%` }} />
                </div>
              </div>
            )}
          </Card>
          <Card className="p-6">
            <p className="text-[13px] font-medium text-muted">Account details</p>
            {w ? (
              <DefinitionList
                items={[
                  {
                    label: 'Account number',
                    value: (
                      <span className="flex items-center gap-1 font-mono text-[13px]">
                        {w.accountNumber} <CopyButton value={w.accountNumber} label="Copy account number" />
                      </span>
                    ),
                  },
                  { label: 'Currency', value: w.currency },
                  { label: 'Ledger type', value: 'Liability (credit-normal)' },
                  { label: 'Mode', value: w.sandboxEnabled ? <Badge tone="warning">Sandbox</Badge> : <Badge tone="success">Live</Badge> },
                  { label: 'Opened', value: formatDate(w.createdAt) },
                ]}
              />
            ) : (
              <div className="mt-4 space-y-3">
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-2/3" />
                <Skeleton className="h-4 w-3/4" />
              </div>
            )}
          </Card>
        </div>
      )}
      {w && <Statement currency={w.currency} />}
      {w && <TopUpDialog wallet={w} open={topUpOpen} onOpenChange={setTopUpOpen} />}
    </>
  );
}
