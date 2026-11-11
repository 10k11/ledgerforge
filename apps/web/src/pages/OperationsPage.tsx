import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, RefreshCw, RotateCcw, ScrollText, XCircle } from 'lucide-react';
import * as React from 'react';
import { toast } from 'sonner';
import { EmptyState, ErrorState, PageHeader, Pagination, StatCard, StatusBadge, TableSkeleton } from '@/components/shared';
import { Button } from '@/components/ui/button';
import { Field, Input } from '@/components/ui/form';
import { ConfirmDialog } from '@/components/ui/overlay';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Skeleton, Table, TBody, TD, TH, THead, TR, Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/primitives';
import { useDebouncedValue, useDocumentTitle, useIdempotencyKey } from '@/hooks';
import { errorMessage } from '@/lib/api';
import { formatDateTime, humanize } from '@/lib/format';
import { formatMoney } from '@/lib/money';
import { adminService } from '@/services';

function Reconciliation() {
  const report = useQuery({ queryKey: ['reconciliation'], queryFn: adminService.reconciliation });
  const r = report.data;
  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Ledger reconciliation</CardTitle>
          <CardDescription>
            Recomputes every balance from entries, checks each transaction balances and verifies Assets = Liabilities +
            Equity + Revenue − Expenses, in one consistent snapshot.
          </CardDescription>
        </div>
        <Button variant="outline" size="sm" onClick={() => report.refetch()} loading={report.isFetching}>
          <RefreshCw /> Run again
        </Button>
      </CardHeader>
      <CardContent>
        {report.isError ? (
          <ErrorState error={report.error} onRetry={() => report.refetch()} />
        ) : !r ? (
          <Skeleton className="h-32 w-full" />
        ) : (
          <>
            <div className={`flex items-center gap-3 rounded-xl border p-4 ${r.healthy ? 'border-emerald-200 bg-success-soft' : 'border-red-200 bg-danger-soft'}`}>
              {r.healthy ? <CheckCircle2 className="size-6 text-success" /> : <XCircle className="size-6 text-danger-strong" />}
              <div>
                <p className="font-semibold">{r.healthy ? 'Ledger is consistent' : 'Inconsistencies detected'}</p>
                <p className="text-[13px] text-muted">
                  {r.accountsChecked} accounts and {r.transactionsChecked} transactions checked at {formatDateTime(r.checkedAt)}
                </p>
              </div>
            </div>
            <Table className="mt-4">
              <THead>
                <TR>
                  <TH>Currency</TH>
                  <TH className="text-right">Debits</TH>
                  <TH className="text-right">Credits</TH>
                  <TH className="hidden text-right md:table-cell">Assets</TH>
                  <TH className="hidden text-right md:table-cell">Liabilities</TH>
                  <TH className="hidden text-right md:table-cell">Revenue</TH>
                  <TH>Equation</TH>
                </TR>
              </THead>
              <TBody>
                {r.trialBalances.map((t) => (
                  <TR key={t.currency}>
                    <TD className="font-medium">{t.currency}</TD>
                    <TD className="tabular text-right">{formatMoney(t.totalDebitsMinor, t.currency)}</TD>
                    <TD className="tabular text-right">{formatMoney(t.totalCreditsMinor, t.currency)}</TD>
                    <TD className="tabular hidden text-right md:table-cell">{formatMoney(t.assetsMinor, t.currency)}</TD>
                    <TD className="tabular hidden text-right md:table-cell">{formatMoney(t.liabilitiesMinor, t.currency)}</TD>
                    <TD className="tabular hidden text-right md:table-cell">{formatMoney(t.revenueMinor, t.currency)}</TD>
                    <TD>{t.balanced && t.accountingEquationHolds ? <Badge tone="success">Holds</Badge> : <Badge tone="danger">Broken</Badge>}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
            {r.balanceMismatches.length > 0 && (
              <div className="mt-4 rounded-lg border border-red-200 p-3 text-[13px]">
                <p className="font-medium text-danger-strong">Balance projection mismatches</p>
                {r.balanceMismatches.map((m) => (
                  <p key={m.accountId} className="font-mono text-xs">
                    {m.accountNumber}: projected {m.projectedBalanceMinor}, ledger {m.ledgerBalanceMinor}
                  </p>
                ))}
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function Overview() {
  const overview = useQuery({ queryKey: ['admin-overview'], queryFn: adminService.overview });
  const o = overview.data;
  if (overview.isError) return <ErrorState error={overview.error} onRetry={() => overview.refetch()} />;
  const inr = o?.volumes.find((v) => v.currency === 'INR');
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Users" loading={!o} value={o && Object.values(o.usersByRole).reduce((a, b) => a + b, 0)} hint={o && Object.entries(o.usersByRole).map(([k, v]) => `${v} ${k.toLowerCase()}`).join(' · ')} />
        <StatCard label="Ledger transactions" loading={!o} value={o?.ledgerTransactions} hint={o && `${o.ledgerEntries} entries`} />
        <StatCard label="Captured (INR)" loading={!o} value={inr && formatMoney(inr.capturedMinor, 'INR')} hint={inr && `${formatMoney(inr.refundedMinor, 'INR')} refunded`} />
        <StatCard label="Fee revenue (INR)" loading={!o} value={inr && formatMoney(inr.feesMinor, 'INR')} hint="Net of fee reversals" />
      </div>
      <Card>
        <CardHeader className="pb-4">
          <div>
            <CardTitle>System accounts</CardTitle>
            <CardDescription>Platform-owned ledger accounts and their projected balances.</CardDescription>
          </div>
        </CardHeader>
        {o ? (
          <Table>
            <THead>
              <TR>
                <TH>Account</TH>
                <TH className="hidden sm:table-cell">Type</TH>
                <TH className="text-right">Balance</TH>
              </TR>
            </THead>
            <TBody>
              {o.systemAccounts.map((a) => (
                <TR key={a.id}>
                  <TD>
                    <p className="font-medium">{a.name}</p>
                    <p className="font-mono text-xs text-muted">{a.accountNumber}</p>
                  </TD>
                  <TD className="hidden sm:table-cell">{humanize(a.type)}</TD>
                  <TD className="tabular text-right font-medium">{formatMoney(a.balanceMinor, a.currency)}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        ) : (
          <TableSkeleton rows={4} cols={3} />
        )}
      </Card>
      {o && (
        <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
          <Card className="p-5">
            <p className="text-sm font-semibold">Payments by status</p>
            <ul className="mt-3 space-y-2">
              {Object.entries(o.paymentsByStatus).map(([k, v]) => (
                <li key={k} className="flex items-center justify-between text-sm">
                  <StatusBadge status={k} />
                  <span className="tabular font-medium">{v}</span>
                </li>
              ))}
            </ul>
          </Card>
          <Card className="p-5">
            <p className="text-sm font-semibold">Webhook deliveries</p>
            <ul className="mt-3 space-y-2">
              {Object.entries(o.webhookDeliveriesByStatus).map(([k, v]) => (
                <li key={k} className="flex items-center justify-between text-sm">
                  <StatusBadge status={k} />
                  <span className="tabular font-medium">{v}</span>
                </li>
              ))}
              {Object.keys(o.webhookDeliveriesByStatus).length === 0 && <li className="text-[13px] text-muted">No deliveries yet.</li>}
            </ul>
          </Card>
        </div>
      )}
    </div>
  );
}

function AuditLog() {
  const [action, setAction] = React.useState('');
  const [page, setPage] = React.useState(0);
  const debounced = useDebouncedValue(action);
  const audit = useQuery({
    queryKey: ['audit', debounced, page],
    queryFn: () => adminService.audit({ action: debounced || undefined, page, size: 20 }),
    placeholderData: keepPreviousData,
  });
  return (
    <Card>
      <CardHeader className="flex-col gap-3 pb-4 sm:flex-row sm:items-center">
        <div>
          <CardTitle>Audit log</CardTitle>
          <CardDescription>Append-only record of security and financial actions with correlation IDs.</CardDescription>
        </div>
        <Input value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }} placeholder="Filter by action, e.g. LOGIN" className="sm:w-64" aria-label="Filter by action" />
      </CardHeader>
      <div className="border-t border-line">
        {audit.isPending ? (
          <TableSkeleton rows={6} />
        ) : audit.isError ? (
          <ErrorState error={audit.error} onRetry={() => audit.refetch()} />
        ) : audit.data.items.length === 0 ? (
          <EmptyState icon={ScrollText} title="No audit entries match" />
        ) : (
          <>
            <Table>
              <THead>
                <TR>
                  <TH>Action</TH>
                  <TH>Result</TH>
                  <TH className="hidden md:table-cell">Resource</TH>
                  <TH className="hidden lg:table-cell">Correlation ID</TH>
                  <TH>Time</TH>
                </TR>
              </THead>
              <TBody>
                {audit.data.items.map((a) => (
                  <TR key={a.id}>
                    <TD>
                      <p className="font-mono text-[13px] font-medium">{a.action}</p>
                      <p className="text-xs text-muted">{a.actorRole?.toLowerCase() ?? 'anonymous'}</p>
                    </TD>
                    <TD>
                      <StatusBadge status={a.result} />
                    </TD>
                    <TD className="hidden md:table-cell">
                      <p className="text-[13px]">{humanize(a.resourceType)}</p>
                      <p className="font-mono text-xs text-muted">{a.resourceId?.slice(0, 13) ?? '—'}</p>
                    </TD>
                    <TD className="hidden font-mono text-xs text-muted lg:table-cell">{a.correlationId?.slice(0, 13) ?? '—'}</TD>
                    <TD className="whitespace-nowrap text-[13px] text-ink-soft">{formatDateTime(a.createdAt)}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
            <Pagination page={audit.data.page} size={audit.data.size} totalItems={audit.data.totalItems} totalPages={audit.data.totalPages} onPage={setPage} />
          </>
        )}
      </div>
    </Card>
  );
}

function Corrections() {
  const queryClient = useQueryClient();
  const { key, reset } = useIdempotencyKey();
  const [txId, setTxId] = React.useState('');
  const [reason, setReason] = React.useState('');
  const [open, setOpen] = React.useState(false);
  const reverse = useMutation({
    mutationFn: () => adminService.reverse(txId.trim(), reason.trim(), key),
    onSuccess: (r) => {
      reset();
      setOpen(false);
      setTxId('');
      setReason('');
      queryClient.invalidateQueries();
      toast.success('Reversal posted', { description: r.reference });
    },
    onError: (e) => toast.error('Reversal rejected', { description: errorMessage(e) }),
  });
  return (
    <Card className="max-w-2xl">
      <CardHeader>
        <div>
          <CardTitle>Post a correction</CardTitle>
          <CardDescription>
            Ledger entries are immutable. A top-up or transfer is corrected by posting its exact mirror as a REVERSAL
            transaction. Payments must be refunded instead.
          </CardDescription>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <Field id="rev-tx" label="Ledger transaction ID">
          <Input value={txId} onChange={(e) => { setTxId(e.target.value); reset(); }} placeholder="00000000-0000-0000-0000-000000000000" className="font-mono" />
        </Field>
        <Field id="rev-reason" label="Reason">
          <Input value={reason} maxLength={255} onChange={(e) => setReason(e.target.value)} placeholder="Duplicate sandbox funding" />
        </Field>
        <Button variant="outline" onClick={() => setOpen(true)} disabled={!/^[0-9a-f-]{36}$/i.test(txId.trim()) || reason.trim().length < 3}>
          <RotateCcw /> Review reversal
        </Button>
      </CardContent>
      <ConfirmDialog
        open={open}
        onOpenChange={setOpen}
        title="Post reversal?"
        description="This creates a new balanced transaction that undoes the original. It cannot be deleted."
        confirmLabel="Post reversal"
        tone="danger"
        loading={reverse.isPending}
        onConfirm={() => reverse.mutate()}
      />
    </Card>
  );
}

export default function OperationsPage() {
  useDocumentTitle('Operations');
  return (
    <>
      <PageHeader title="Operations" description="Platform health, ledger integrity and the audit trail." />
      <Tabs defaultValue="overview">
        <TabsList className="mb-5" aria-label="Operations sections">
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="reconciliation">Reconciliation</TabsTrigger>
          <TabsTrigger value="audit">Audit log</TabsTrigger>
          <TabsTrigger value="corrections">Corrections</TabsTrigger>
        </TabsList>
        <TabsContent value="overview">
          <Overview />
        </TabsContent>
        <TabsContent value="reconciliation">
          <Reconciliation />
        </TabsContent>
        <TabsContent value="audit">
          <AuditLog />
        </TabsContent>
        <TabsContent value="corrections">
          <Corrections />
        </TabsContent>
      </Tabs>
    </>
  );
}
