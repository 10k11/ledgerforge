import { zodResolver } from '@hookform/resolvers/zod';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeftRight, ArrowRight, CheckCircle2, Search, ShieldCheck, UserCheck } from 'lucide-react';
import * as React from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router';
import { toast } from 'sonner';
import { z } from 'zod';
import { DefinitionList, EmptyState, ErrorState, Money, PageHeader, Pagination, TableSkeleton } from '@/components/shared';
import { Button } from '@/components/ui/button';
import { Field, Input, MoneyInput } from '@/components/ui/form';
import { Card, CardContent, CardDescription, CardHeader, CardTitle, Tabs, TabsList, TabsTrigger, TD, TBody, Table, TR } from '@/components/ui/primitives';
import { useDebouncedValue, useDocumentTitle, useIdempotencyKey } from '@/hooks';
import { ApiError, errorMessage } from '@/lib/api';
import { formatDateTime } from '@/lib/format';
import { currencySymbol, formatMoney, parseMoneyInput } from '@/lib/money';
import { transferService, walletService } from '@/services';
import type { RecipientView, TransferView } from '@/types/api';

const schema = z.object({
  recipient: z.string().trim().min(3, 'Enter an email address or account number').max(254),
  amount: z.string().superRefine((v, ctx) => {
    const r = parseMoneyInput(v);
    if (!r.ok) ctx.addIssue({ code: 'custom', message: r.error });
  }),
  description: z.string().max(140, 'Keep the note under 140 characters').optional(),
});
type Values = z.infer<typeof schema>;

type Stage = { kind: 'form' } | { kind: 'review'; values: Values; minor: number; recipient: RecipientView } | { kind: 'done'; transfer: TransferView };

export function SendMoneyCard() {
  const queryClient = useQueryClient();
  const wallet = useQuery({ queryKey: ['wallet'], queryFn: walletService.get });
  const { key, reset } = useIdempotencyKey();
  const [stage, setStage] = React.useState<Stage>({ kind: 'form' });
  const [submitError, setSubmitError] = React.useState<string | null>(null);
  const currency = wallet.data?.currency ?? 'INR';
  const {
    register,
    handleSubmit,
    setError,
    reset: resetForm,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { recipient: '', amount: '', description: '' } });

  const lookup = handleSubmit(async (values) => {
    const parsed = parseMoneyInput(values.amount);
    if (!parsed.ok) return;
    // The displayed balance is a convenience; the server re-checks funds under a row lock.
    if (wallet.data && parsed.minor > wallet.data.balanceMinor) {
      setError('amount', { message: `Exceeds your available balance of ${formatMoney(wallet.data.balanceMinor, currency)}` });
      return;
    }
    try {
      const recipient = await transferService.lookup(values.recipient.trim());
      setSubmitError(null);
      setStage({ kind: 'review', values, minor: parsed.minor, recipient });
    } catch (e) {
      setError('recipient', { message: e instanceof ApiError ? e.detail : errorMessage(e) });
    }
  });

  const send = useMutation({
    mutationFn: (s: Extract<Stage, { kind: 'review' }>) =>
      transferService.create(
        { recipient: s.values.recipient.trim(), amountMinor: s.minor, currency, description: s.values.description?.trim() || undefined },
        key,
      ),
    onSuccess: ({ data, replayed }) => {
      setStage({ kind: 'done', transfer: data });
      reset();
      resetForm();
      for (const k of ['wallet', 'dashboard', 'transactions', 'transfers', 'statement']) {
        queryClient.invalidateQueries({ queryKey: [k] });
      }
      toast.success(replayed ? 'Transfer already completed' : 'Money sent', { description: data.reference });
    },
    onError: (e) => setSubmitError(errorMessage(e)),
  });

  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Send money</CardTitle>
          <CardDescription>Instant wallet-to-wallet transfer, posted atomically to the ledger.</CardDescription>
        </div>
      </CardHeader>
      <CardContent>
        {stage.kind === 'form' && (
          <form onSubmit={lookup} noValidate className="space-y-4">
            <Field id="recipient" label="Recipient" error={errors.recipient?.message} hint="Email address or LedgerForge account number (e.g. LF123456789012)">
              <Input placeholder="ravi@example.com" autoComplete="off" {...register('recipient')} />
            </Field>
            <Field
              id="amount"
              label="Amount"
              error={errors.amount?.message}
              hint={wallet.data ? `Available ${formatMoney(wallet.data.balanceMinor, currency)}` : undefined}
            >
              <MoneyInput symbol={currencySymbol(currency)} placeholder="0.00" {...register('amount', { onChange: () => reset() })} />
            </Field>
            <Field id="currency" label="Currency">
              <Input value={currency} disabled readOnly />
            </Field>
            <Field id="description" label="Note (optional)" error={errors.description?.message}>
              <Input placeholder="Dinner split" maxLength={140} {...register('description')} />
            </Field>
            <Button type="submit" className="w-full" loading={isSubmitting} disabled={!wallet.data}>
              Review transfer <ArrowRight />
            </Button>
          </form>
        )}
        {stage.kind === 'review' && (
          <div>
            <div className="rounded-xl border border-line bg-subtle/50 p-5 text-center">
              <p className="text-[13px] text-muted">You are sending</p>
              <p className="tabular mt-1 text-3xl font-semibold tracking-tight">{formatMoney(stage.minor, currency)}</p>
              <p className="mt-2 flex items-center justify-center gap-1.5 text-sm text-ink-soft">
                <UserCheck className="size-4 text-success" aria-hidden /> to <strong>{stage.recipient.displayName}</strong>
              </p>
            </div>
            <div className="mt-4">
              <DefinitionList
                items={[
                  { label: 'Recipient email', value: stage.recipient.maskedEmail },
                  { label: 'Recipient account', value: <span className="font-mono">{stage.recipient.accountNumberMasked}</span> },
                  { label: 'Note', value: stage.values.description || '—' },
                  { label: 'Fee', value: formatMoney(0, currency) },
                  {
                    label: 'Your balance after',
                    value: wallet.data ? <Money minor={wallet.data.balanceMinor - stage.minor} currency={currency} /> : '—',
                  },
                ]}
              />
            </div>
            {submitError && (
              <p role="alert" className="mt-3 rounded-lg bg-danger-soft px-3 py-2 text-[13px] text-danger-strong">
                {submitError}
              </p>
            )}
            <p className="mt-3 flex items-start gap-2 text-xs text-muted">
              <ShieldCheck className="mt-0.5 size-3.5 shrink-0" aria-hidden />
              Protected against duplicates: retrying reuses idempotency key <span className="font-mono">{key.slice(0, 8)}…</span>
            </p>
            <div className="mt-5 flex gap-2">
              <Button variant="outline" className="flex-1" onClick={() => setStage({ kind: 'form' })} disabled={send.isPending}>
                Edit
              </Button>
              <Button className="flex-1" onClick={() => send.mutate(stage)} loading={send.isPending}>
                Send {formatMoney(stage.minor, currency)}
              </Button>
            </div>
          </div>
        )}
        {stage.kind === 'done' && (
          <div className="text-center">
            <CheckCircle2 className="mx-auto size-10 text-success" aria-hidden />
            <p className="mt-3 text-sm text-muted">Sent to {stage.transfer.counterpartyName}</p>
            <p className="tabular mt-1 text-2xl font-semibold">{formatMoney(stage.transfer.amountMinor, stage.transfer.currency)}</p>
            <p className="mt-2 font-mono text-xs text-muted">{stage.transfer.reference}</p>
            {stage.transfer.senderBalanceAfterMinor !== undefined && (
              <p className="mt-1 text-[13px] text-muted">
                New balance {formatMoney(stage.transfer.senderBalanceAfterMinor, stage.transfer.currency)}
              </p>
            )}
            <div className="mt-5 flex gap-2">
              <Button variant="outline" className="flex-1" asChild>
                <Link to={`/transactions/${stage.transfer.ledgerTransactionId}`}>Ledger entries</Link>
              </Button>
              <Button className="flex-1" onClick={() => setStage({ kind: 'form' })}>
                New transfer
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function TransferHistory() {
  const [direction, setDirection] = React.useState('ALL');
  const [q, setQ] = React.useState('');
  const [page, setPage] = React.useState(0);
  const debounced = useDebouncedValue(q);
  const params = { direction, q: debounced || undefined, page, size: 10 };
  const transfers = useQuery({
    queryKey: ['transfers', params],
    queryFn: () => transferService.list(params),
    placeholderData: keepPreviousData,
  });
  return (
    <Card>
      <CardHeader className="flex-col gap-3 pb-4 sm:flex-row sm:items-center">
        <div>
          <CardTitle>Transfer history</CardTitle>
          <CardDescription>Sent and received wallet transfers</CardDescription>
        </div>
        <Tabs value={direction} onValueChange={(v) => { setDirection(v); setPage(0); }}>
          <TabsList aria-label="Direction">
            <TabsTrigger value="ALL">All</TabsTrigger>
            <TabsTrigger value="SENT">Sent</TabsTrigger>
            <TabsTrigger value="RECEIVED">Received</TabsTrigger>
          </TabsList>
        </Tabs>
      </CardHeader>
      <div className="px-5 pb-4">
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" aria-hidden />
          <Input value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} placeholder="Search reference or note" className="pl-9" aria-label="Search transfers" />
        </div>
      </div>
      <div className="border-t border-line">
        {transfers.isPending ? (
          <TableSkeleton rows={4} cols={3} />
        ) : transfers.isError ? (
          <ErrorState error={transfers.error} onRetry={() => transfers.refetch()} />
        ) : transfers.data.items.length === 0 ? (
          <EmptyState icon={ArrowLeftRight} title="No transfers found" description="Transfers you send or receive will appear here." />
        ) : (
          <>
            <Table>
              <TBody>
                {transfers.data.items.map((t) => (
                  <TR key={t.id}>
                    <TD>
                      <Link to={`/transactions/${t.ledgerTransactionId}`} className="font-medium hover:text-primary">
                        {t.direction === 'SENT' ? `To ${t.counterpartyName}` : `From ${t.counterpartyName}`}
                      </Link>
                      <p className="text-xs text-muted">
                        {t.description} · <span className="font-mono">{t.reference}</span>
                      </p>
                    </TD>
                    <TD className="hidden whitespace-nowrap text-[13px] text-ink-soft sm:table-cell">{formatDateTime(t.createdAt)}</TD>
                    <TD className="text-right font-medium">
                      <Money minor={t.amountMinor} currency={t.currency} direction={t.direction === 'SENT' ? 'OUT' : 'IN'} />
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
            <Pagination page={transfers.data.page} size={transfers.data.size} totalItems={transfers.data.totalItems} totalPages={transfers.data.totalPages} onPage={setPage} />
          </>
        )}
      </div>
    </Card>
  );
}

export default function TransfersPage() {
  useDocumentTitle('Transfers');
  return (
    <>
      <PageHeader title="Transfers" description="Move money between LedgerForge wallets instantly and safely." />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[400px_minmax(0,1fr)]">
        <div>
          <SendMoneyCard />
        </div>
        <TransferHistory />
      </div>
    </>
  );
}
