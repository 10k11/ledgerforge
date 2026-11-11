import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  ArrowDownLeft,
  ArrowLeftRight,
  ArrowUpRight,
  CheckCircle2,
  Clock,
  Code2,
  Landmark,
  PlusCircle,
  Receipt,
  Search,
  Store,
  Undo2,
  Wallet,
} from 'lucide-react';
import * as React from 'react';
import { Link, Navigate } from 'react-router';
import { ErrorState, Money, Pagination, StatCard, TableSkeleton } from '@/components/shared';
import { ActivityChart, ChartLegend, PaymentsTable, TransactionsTable, ViewAllLink, VolumeChart } from '@/components/shared/finance';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/form';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Skeleton } from '@/components/ui/primitives';
import { useAuth } from '@/features/auth/AuthProvider';
import { useDebouncedValue, useDocumentTitle } from '@/hooks';
import { endOfDayExclusiveIso, formatLongDate, greeting, startOfDayIso } from '@/lib/format';
import { formatMoney } from '@/lib/money';
import { dashboardService, merchantService, transactionService } from '@/services';

function Greeting({ name, actions, status }: { name: string; actions: React.ReactNode; status: string }) {
  return (
    <div className="mb-6 flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <p className="text-[13px] text-muted">{formatLongDate(new Date())}</p>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight text-ink">
          {greeting()}, {name.split(' ')[0]}
        </h1>
        <div className="mt-2 flex items-center gap-2">
          <Badge tone="success" dot>
            {status}
          </Badge>
        </div>
      </div>
      <div className="flex flex-wrap gap-2">{actions}</div>
    </div>
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
  const tx = useQuery({
    queryKey: ['transactions', filter],
    queryFn: () => transactionService.list(filter),
    placeholderData: keepPreviousData,
  });
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
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search reference or description"
            className="pl-9"
            aria-label="Search transactions"
          />
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
            <TransactionsTable items={tx.data.items} />
            <Pagination
              page={tx.data.page}
              size={tx.data.size}
              totalItems={tx.data.totalItems}
              totalPages={tx.data.totalPages}
              onPage={setPage}
            />
          </>
        )}
      </div>
    </Card>
  );
}

function CustomerOverview({ name }: { name: string }) {
  const summary = useQuery({ queryKey: ['dashboard'], queryFn: dashboardService.summary });
  const s = summary.data;
  const sum = (types: string[], key: 'inMinor' | 'outMinor') =>
    s?.byType.filter((t) => types.includes(t.type)).reduce((acc, t) => acc + t[key], 0) ?? 0;
  const currency = s?.currency ?? 'INR';

  return (
    <>
      <Greeting
        name={name}
        status="Wallet active"
        actions={
          <>
            <Button variant="outline" asChild>
              <Link to="/wallet?topup=1">
                <PlusCircle /> Add funds
              </Link>
            </Button>
            <Button variant="outline" asChild>
              <Link to="/merchants">
                <Store /> Pay a merchant
              </Link>
            </Button>
            <Button asChild>
              <Link to="/transfers">
                <ArrowLeftRight /> Send money
              </Link>
            </Button>
          </>
        }
      />
      {summary.isError ? (
        <Card className="mb-6">
          <ErrorState error={summary.error} onRetry={() => summary.refetch()} />
        </Card>
      ) : (
        <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-5">
          <div className="sm:col-span-2 xl:col-span-1">
            <StatCard
              emphasis
              label="Available balance"
              icon={Wallet}
              loading={summary.isPending}
              value={s && formatMoney(s.balanceMinor, currency)}
              hint={s && <span className="font-mono">{s.accountNumber}</span>}
            />
          </div>
          <StatCard
            label="Total received"
            icon={ArrowDownLeft}
            loading={summary.isPending}
            value={formatMoney(sum(['TRANSFER', 'REFUND'], 'inMinor'), currency)}
            hint={s && `Plus ${formatMoney(sum(['TOP_UP'], 'inMinor'), currency)} sandbox funding`}
          />
          <StatCard
            label="Total sent"
            icon={ArrowUpRight}
            loading={summary.isPending}
            value={formatMoney(sum(['TRANSFER', 'PAYMENT'], 'outMinor'), currency)}
            hint="Transfers and merchant payments"
          />
          <StatCard
            label="Successful payments"
            icon={CheckCircle2}
            loading={summary.isPending}
            value={s?.successfulPayments ?? 0}
            hint="Captured merchant payments"
          />
          <StatCard
            label="Pending"
            icon={Clock}
            loading={summary.isPending}
            value={s?.pendingPayments ?? 0}
            hint={
              s?.pendingPayments ? (
                <Link to="/payments?status=CREATED" className="text-primary hover:underline">
                  Awaiting your confirmation
                </Link>
              ) : (
                'Nothing awaiting confirmation'
              )
            }
          />
        </div>
      )}
      <Card className="mb-6">
        <CardHeader>
          <div>
            <CardTitle>Financial activity</CardTitle>
            <CardDescription>Money in and out of your wallet over the last 30 days</CardDescription>
          </div>
          <ChartLegend
            items={[
              { label: 'Money in', color: '#059669' },
              { label: 'Money out', color: '#2563eb' },
            ]}
          />
        </CardHeader>
        <CardContent>
          {summary.isPending ? (
            <Skeleton className="h-64 w-full" />
          ) : s && s.transactionCount > 0 ? (
            <ActivityChart data={s.last30Days} currency={currency} />
          ) : (
            <div className="grid h-64 place-items-center rounded-lg border border-dashed border-line text-center">
              <div>
                <p className="text-sm font-medium text-ink">No activity in the last 30 days</p>
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
      <RecentActivity />
    </>
  );
}

function MerchantOverview({ name }: { name: string }) {
  const dashboard = useQuery({ queryKey: ['merchant-dashboard'], queryFn: merchantService.dashboard });
  const recent = useQuery({ queryKey: ['merchant-payments', 'recent'], queryFn: () => merchantService.payments({ size: 6 }) });
  const d = dashboard.data;
  const currency = d?.currency ?? 'INR';
  const hasVolume = d?.last30Days.some((p) => p.grossMinor > 0 || p.refundsMinor > 0);
  return (
    <>
      <Greeting
        name={name}
        status={d ? `${d.businessName} · merchant account active` : 'Merchant account active'}
        actions={
          <>
            <Button variant="outline" asChild>
              <Link to="/developers">
                <Code2 /> Webhooks
              </Link>
            </Button>
            <Button asChild>
              <Link to="/merchants">
                <Landmark /> Payouts & reports
              </Link>
            </Button>
          </>
        }
      />
      {dashboard.isError ? (
        <Card className="mb-6">
          <ErrorState error={dashboard.error} onRetry={() => dashboard.refetch()} />
        </Card>
      ) : (
        <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-5">
          <div className="sm:col-span-2 xl:col-span-1">
            <StatCard
              emphasis
              label="Available balance"
              icon={Wallet}
              loading={dashboard.isPending}
              value={d && formatMoney(d.balanceMinor, currency)}
              hint="Net of fees, refunds and payouts"
            />
          </div>
          <StatCard label="Gross volume" icon={Receipt} loading={dashboard.isPending} value={d && formatMoney(d.grossVolumeMinor, currency)} hint="Captured payments" />
          <StatCard label="Platform fees" icon={Landmark} loading={dashboard.isPending} value={d && formatMoney(d.platformFeesMinor, currency)} hint={d && `Net ${formatMoney(d.netVolumeMinor, currency)}`} />
          <StatCard label="Refunded" icon={Undo2} loading={dashboard.isPending} value={d && formatMoney(d.refundedMinor, currency)} hint={d && `${formatMoney(d.paidOutMinor, currency)} paid out`} />
          <StatCard
            label="Successful payments"
            icon={CheckCircle2}
            loading={dashboard.isPending}
            value={d?.successfulPayments ?? 0}
            hint={d && `${d.failedPayments} failed · ${d.pendingPayments} pending`}
          />
        </div>
      )}
      <Card className="mb-6">
        <CardHeader>
          <div>
            <CardTitle>Payment activity</CardTitle>
            <CardDescription>Captured volume and refunds per day (UTC), last 30 days</CardDescription>
          </div>
          <ChartLegend
            items={[
              { label: 'Captured', color: '#2563eb' },
              { label: 'Refunded', color: '#f59e0b' },
            ]}
          />
        </CardHeader>
        <CardContent>
          {dashboard.isPending ? (
            <Skeleton className="h-64 w-full" />
          ) : hasVolume && d ? (
            <VolumeChart data={d.last30Days} currency={currency} />
          ) : (
            <div className="grid h-64 place-items-center rounded-lg border border-dashed border-line text-center text-sm text-muted">
              No captured payments in the last 30 days.
            </div>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-4">
          <div>
            <CardTitle>Latest payments</CardTitle>
            <CardDescription>Fee breakdown is computed server-side and snapshotted at creation.</CardDescription>
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
            <p className="px-5 py-10 text-center text-sm text-muted">
              No payments yet. Customers can find you in the merchant directory.
            </p>
          )}
        </div>
      </Card>
      {d && (
        <p className="mt-4 text-xs text-muted">
          Balance <Money minor={d.balanceMinor} currency={currency} /> = net volume − refunds charged to you − payouts.
        </p>
      )}
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
