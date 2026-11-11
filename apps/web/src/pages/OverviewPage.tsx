import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ArrowDownLeft, ArrowLeftRight, ArrowUpRight, CheckCircle2, Clock, Code2, Landmark, PlusCircle, Receipt, Search, Store, Undo2 } from 'lucide-react';
import * as React from 'react';
import { Link, Navigate } from 'react-router';
import { ErrorState, Pagination, StatCard, TableSkeleton } from '@/components/shared';
import { ActivityChart, ChartLegend, PaymentsTable, TransactionsTable, ViewAllLink, VolumeChart } from '@/components/shared/finance';
import { BalanceHero, BreakdownDonut, DeltaBadge, RateRing, balanceTrajectory, percentChange, windowTotals } from '@/components/shared/insights';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/form';
import { Card, CardContent, CardDescription, CardHeader, CardTitle, Skeleton, Tabs, TabsList, TabsTrigger } from '@/components/ui/primitives';
import { useAuth } from '@/features/auth/AuthProvider';
import { useDebouncedValue, useDocumentTitle } from '@/hooks';
import { endOfDayExclusiveIso, formatLongDate, greeting, startOfDayIso } from '@/lib/format';
import { formatMoney } from '@/lib/money';
import { dashboardService, merchantService, transactionService } from '@/services';

function Greeting({ name, subtitle, actions }: { name: string; subtitle: string; actions?: React.ReactNode }) {
  return (
    <div className="mb-6 flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <p className="text-[13px] font-medium text-muted">{formatLongDate(new Date())}</p>
        <h1 className="mt-1 text-[26px] font-semibold tracking-tight text-ink">
          {greeting()}, {name.split(' ')[0]}
        </h1>
        <p className="mt-1 text-sm text-muted">{subtitle}</p>
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

function RangeTabs({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <Tabs value={value} onValueChange={onChange}>
      <TabsList aria-label="Chart range">
        <TabsTrigger value="7">7D</TabsTrigger>
        <TabsTrigger value="14">14D</TabsTrigger>
        <TabsTrigger value="30">30D</TabsTrigger>
      </TabsList>
    </Tabs>
  );
}

function RecentActivity() {
  const [q, setQ] = React.useState('');
  const [from, setFrom] = React.useState('');
  const [to, setTo] = React.useState('');
  const [page, setPage] = React.useState(0);
  const debouncedQ = useDebouncedValue(q);
  const filter = {
    q: debouncedQ || undefined,
    from: from ? startOfDayIso(from) : undefined,
    to: to ? endOfDayExclusiveIso(to) : undefined,
    page,
    size: 8,
  };
  const tx = useQuery({ queryKey: ['transactions', filter], queryFn: () => transactionService.list(filter), placeholderData: keepPreviousData });
  React.useEffect(() => setPage(0), [debouncedQ, from, to]);
  return (
    <Card>
      <CardHeader className="flex-col gap-3 sm:flex-row sm:items-center">
        <div>
          <CardTitle>Recent activity</CardTitle>
          <CardDescription>Every row is a posted, balanced ledger transaction.</CardDescription>
        </div>
        <ViewAllLink to="/transactions" />
      </CardHeader>
      <div className="flex flex-col gap-2 px-5 pb-4 pt-4 sm:flex-row">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" aria-hidden />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search reference or description" className="pl-9" aria-label="Search transactions" />
        </div>
        <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From date" className="sm:w-40" />
        <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To date" className="sm:w-40" />
      </div>
      <div className="border-t border-line">
        {tx.isPending ? (
          <TableSkeleton rows={5} />
        ) : tx.isError ? (
          <ErrorState error={tx.error} onRetry={() => tx.refetch()} />
        ) : (
          <>
            <TransactionsTable
              items={tx.data.items}
              groupByDay
              emptyAction={
                <Button size="sm" variant="outline" asChild>
                  <Link to="/wallet?topup=1">
                    <PlusCircle /> Add sandbox funds
                  </Link>
                </Button>
              }
            />
            <Pagination page={tx.data.page} size={tx.data.size} totalItems={tx.data.totalItems} totalPages={tx.data.totalPages} onPage={setPage} />
          </>
        )}
      </div>
    </Card>
  );
}

function CustomerOverview({ name }: { name: string }) {
  const summary = useQuery({ queryKey: ['dashboard'], queryFn: dashboardService.summary });
  const [range, setRange] = React.useState('30');
  const s = summary.data;
  const currency = s?.currency ?? 'INR';
  const sum = (types: string[], key: 'inMinor' | 'outMinor') =>
    s?.byType.filter((t) => types.includes(t.type)).reduce((acc, t) => acc + t[key], 0) ?? 0;
  const trajectory = s ? balanceTrajectory(s.last30Days, s.balanceMinor) : [];
  const inWeek = s ? windowTotals(s.last30Days.map((d) => d.inMinor), 7) : null;
  const outWeek = s ? windowTotals(s.last30Days.map((d) => d.outMinor), 7) : null;
  const series = s?.last30Days.slice(-Number(range)) ?? [];
  const rangeIn = series.reduce((a, d) => a + d.inMinor, 0);
  const rangeOut = series.reduce((a, d) => a + d.outMinor, 0);

  return (
    <>
      <Greeting name={name} subtitle="Here's what's happening with your wallet." />
      {summary.isError && (
        <Card className="mb-6">
          <ErrorState error={summary.error} onRetry={() => summary.refetch()} />
        </Card>
      )}
      <div className="stagger mb-6 grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,420px)_minmax(0,1fr)]">
        {s ? (
          <BalanceHero
            label="Available balance"
            balanceMinor={s.balanceMinor}
            currency={currency}
            accountNumber={s.accountNumber}
            trajectory={trajectory}
            footer="Balance over the last 30 days, reconstructed from ledger entries"
            actions={
              <>
                <Button asChild className="bg-white text-ink hover:bg-slate-100">
                  <Link to="/transfers">
                    <ArrowLeftRight /> Send
                  </Link>
                </Button>
                <Button asChild variant="ghost" className="text-white ring-1 ring-white/20 hover:bg-white/10 hover:text-white">
                  <Link to="/wallet?topup=1">
                    <PlusCircle /> Add funds
                  </Link>
                </Button>
                <Button asChild variant="ghost" className="text-white ring-1 ring-white/20 hover:bg-white/10 hover:text-white">
                  <Link to="/merchants">
                    <Store /> Pay
                  </Link>
                </Button>
              </>
            }
          />
        ) : (
          <Skeleton className="h-[300px] rounded-2xl" />
        )}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <StatCard
            label="Money in · 7 days"
            icon={ArrowDownLeft}
            loading={!s}
            value={inWeek && formatMoney(inWeek.current, currency)}
            hint={inWeek && <DeltaBadge value={percentChange(inWeek.current, inWeek.previous)} />}
          />
          <StatCard
            label="Money out · 7 days"
            icon={ArrowUpRight}
            loading={!s}
            value={outWeek && formatMoney(outWeek.current, currency)}
            hint={outWeek && <DeltaBadge value={percentChange(outWeek.current, outWeek.previous)} invert />}
          />
          <StatCard
            label="Successful payments"
            icon={CheckCircle2}
            loading={!s}
            value={s?.successfulPayments ?? 0}
            hint={`${formatMoney(sum(['PAYMENT'], 'outMinor'), currency)} paid to merchants`}
          />
          <StatCard
            label="Pending"
            icon={Clock}
            loading={!s}
            value={s?.pendingPayments ?? 0}
            hint={
              s?.pendingPayments ? (
                <Link to="/payments?status=CREATED" className="font-medium text-primary hover:underline">
                  Awaiting your confirmation →
                </Link>
              ) : (
                'Nothing awaiting confirmation'
              )
            }
          />
        </div>
      </div>

      <div className="mb-6 grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
        <Card>
          <CardHeader className="flex-col gap-3 sm:flex-row sm:items-start">
            <div>
              <CardTitle>Cash flow</CardTitle>
              <div className="mt-2 flex flex-wrap gap-x-6 gap-y-1">
                <p className="text-[13px] text-muted">
                  In <span className="tabular ml-1 font-semibold text-success">{formatMoney(rangeIn, currency)}</span>
                </p>
                <p className="text-[13px] text-muted">
                  Out <span className="tabular ml-1 font-semibold text-ink">{formatMoney(rangeOut, currency)}</span>
                </p>
              </div>
            </div>
            <RangeTabs value={range} onChange={setRange} />
          </CardHeader>
          <CardContent>
            {!s ? (
              <Skeleton className="h-64 w-full" />
            ) : s.transactionCount > 0 ? (
              <>
                <ActivityChart data={series} currency={currency} />
                <div className="mt-2 flex justify-end">
                  <ChartLegend items={[{ label: 'Money in', color: '#059669' }, { label: 'Money out', color: '#2563eb' }]} />
                </div>
              </>
            ) : (
              <div className="grid h-64 place-items-center rounded-xl border border-dashed border-line text-center">
                <div>
                  <p className="text-sm font-medium text-ink">No activity yet</p>
                  <p className="mt-1 text-[13px] text-muted">Add sandbox funds to see your first ledger posting.</p>
                  <Button size="sm" variant="outline" className="mt-4" asChild>
                    <Link to="/wallet?topup=1">
                      <PlusCircle /> Add funds
                    </Link>
                  </Button>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <div>
              <CardTitle>Where money went</CardTitle>
              <CardDescription>All-time outflows by type</CardDescription>
            </div>
          </CardHeader>
          <CardContent>
            {s ? (
              <BreakdownDonut
                currency={currency}
                centerLabel="Spent"
                segments={[
                  { label: 'Merchant payments', value: sum(['PAYMENT'], 'outMinor'), color: '#2563eb' },
                  { label: 'Transfers sent', value: sum(['TRANSFER'], 'outMinor'), color: '#0ea5e9' },
                  { label: 'Reversals', value: sum(['REVERSAL'], 'outMinor'), color: '#94a3b8' },
                ]}
              />
            ) : (
              <Skeleton className="h-36 w-full" />
            )}
            {s && (
              <div className="mt-5 grid grid-cols-2 gap-3 border-t border-line pt-4 text-[13px]">
                <div>
                  <p className="text-muted">Received</p>
                  <p className="tabular font-semibold">{formatMoney(sum(['TRANSFER', 'REFUND'], 'inMinor'), currency)}</p>
                </div>
                <div>
                  <p className="text-muted">Sandbox funding</p>
                  <p className="tabular font-semibold">{formatMoney(sum(['TOP_UP'], 'inMinor'), currency)}</p>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
      <RecentActivity />
    </>
  );
}

function MerchantOverview({ name }: { name: string }) {
  const dashboard = useQuery({ queryKey: ['merchant-dashboard'], queryFn: merchantService.dashboard });
  const summary = useQuery({ queryKey: ['dashboard'], queryFn: dashboardService.summary });
  const recent = useQuery({ queryKey: ['merchant-payments', 'recent'], queryFn: () => merchantService.payments({ size: 6 }) });
  const [range, setRange] = React.useState('30');
  const d = dashboard.data;
  const currency = d?.currency ?? 'INR';
  const series = d?.last30Days.slice(-Number(range)) ?? [];
  const hasVolume = series.some((p) => p.grossMinor > 0 || p.refundsMinor > 0);
  const gross7 = d ? windowTotals(d.last30Days.map((p) => p.grossMinor), 7) : null;
  const decided = d ? d.successfulPayments + d.failedPayments : 0;
  const successRate = d && decided > 0 ? Math.round((d.successfulPayments / decided) * 1000) / 10 : null;

  return (
    <>
      <Greeting
        name={name}
        subtitle={d ? `${d.businessName} · merchant account` : 'Merchant account'}
        actions={
          <Button variant="outline" asChild>
            <Link to="/developers">
              <Code2 /> Webhooks
            </Link>
          </Button>
        }
      />
      {dashboard.isError && (
        <Card className="mb-6">
          <ErrorState error={dashboard.error} onRetry={() => dashboard.refetch()} />
        </Card>
      )}
      <div className="stagger mb-6 grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,420px)_minmax(0,1fr)]">
        {d && summary.data ? (
          <BalanceHero
            label="Available to pay out"
            balanceMinor={d.balanceMinor}
            currency={currency}
            accountNumber={summary.data.accountNumber}
            trajectory={balanceTrajectory(summary.data.last30Days, summary.data.balanceMinor)}
            footer={`${formatMoney(d.paidOutMinor, currency)} paid out to date`}
            actions={
              <>
                <Button asChild className="bg-white text-ink hover:bg-slate-100">
                  <Link to="/merchants">
                    <Landmark /> Pay out
                  </Link>
                </Button>
                <Button asChild variant="ghost" className="text-white ring-1 ring-white/20 hover:bg-white/10 hover:text-white">
                  <Link to="/payments">
                    <Receipt /> Payments
                  </Link>
                </Button>
              </>
            }
          />
        ) : (
          <Skeleton className="h-[300px] rounded-2xl" />
        )}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <StatCard
            label="Gross volume"
            icon={Receipt}
            loading={!d}
            value={d && formatMoney(d.grossVolumeMinor, currency)}
            hint={gross7 && <DeltaBadge value={percentChange(gross7.current, gross7.previous)} label="7d vs prior 7d" />}
          />
          <StatCard label="Net proceeds" icon={Landmark} loading={!d} value={d && formatMoney(d.netVolumeMinor, currency)} hint={d && `${formatMoney(d.platformFeesMinor, currency)} platform fees`} />
          <StatCard label="Refunded" icon={Undo2} loading={!d} value={d && formatMoney(d.refundedMinor, currency)} hint="Reversed through the ledger" />
          <Card className="p-5">
            <RateRing value={successRate} label="Payment success" sublabel={d ? `${d.successfulPayments} captured · ${d.failedPayments} declined · ${d.pendingPayments} pending` : '—'} />
          </Card>
        </div>
      </div>
      <Card className="mb-6">
        <CardHeader className="flex-col gap-3 sm:flex-row sm:items-start">
          <div>
            <CardTitle>Payment activity</CardTitle>
            <CardDescription>Captured volume and refunds per day (UTC)</CardDescription>
          </div>
          <RangeTabs value={range} onChange={setRange} />
        </CardHeader>
        <CardContent>
          {!d ? (
            <Skeleton className="h-64 w-full" />
          ) : hasVolume ? (
            <>
              <VolumeChart data={series} currency={currency} />
              <div className="mt-2 flex justify-end">
                <ChartLegend items={[{ label: 'Captured', color: '#2563eb' }, { label: 'Refunded', color: '#f59e0b' }]} />
              </div>
            </>
          ) : (
            <div className="grid h-64 place-items-center rounded-xl border border-dashed border-line text-sm text-muted">
              No captured payments in this period.
            </div>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-4">
          <div>
            <CardTitle>Latest payments</CardTitle>
            <CardDescription>Fee and net are computed server-side and snapshotted at creation.</CardDescription>
          </div>
          <ViewAllLink to="/payments" />
        </CardHeader>
        <div className="border-t border-line">
          {recent.isPending ? (
            <TableSkeleton rows={4} />
          ) : recent.isError ? (
            <ErrorState error={recent.error} onRetry={() => recent.refetch()} />
          ) : recent.data.items.length ? (
            <PaymentsTable items={recent.data.items} perspective="MERCHANT" />
          ) : (
            <p className="px-5 py-10 text-center text-sm text-muted">No payments yet. Customers can find you in the merchant directory.</p>
          )}
        </div>
      </Card>
    </>
  );
}

export default function OverviewPage() {
  useDocumentTitle('Overview');
  const { user } = useAuth();
  if (!user) return null;
  if (user.role === 'ADMIN') return <Navigate to="/operations" replace />;
  return user.role === 'MERCHANT' ? <MerchantOverview name={user.fullName} /> : <CustomerOverview name={user.fullName} />;
}
