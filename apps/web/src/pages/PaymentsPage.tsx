import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Plus, Receipt, Search } from 'lucide-react';
import * as React from 'react';
import { useSearchParams } from 'react-router';
import { EmptyState, ErrorState, PageHeader, Pagination, TableSkeleton } from '@/components/shared';
import { PaymentsTable } from '@/components/shared/finance';
import { Button } from '@/components/ui/button';
import { Input, NativeSelect } from '@/components/ui/form';
import { Card } from '@/components/ui/primitives';
import { useAuth } from '@/features/auth/AuthProvider';
import { PayMerchantDialog } from '@/features/payments';
import { useDebouncedValue, useDocumentTitle } from '@/hooks';
import { endOfDayExclusiveIso, humanize, startOfDayIso } from '@/lib/format';
import { merchantService, paymentService } from '@/services';

const STATUSES = ['CREATED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED'];

export default function PaymentsPage() {
  useDocumentTitle('Payments');
  const { user } = useAuth();
  const role = user!.role;
  const [params, setParams] = useSearchParams();
  const [q, setQ] = React.useState('');
  const [from, setFrom] = React.useState('');
  const [to, setTo] = React.useState('');
  const [page, setPage] = React.useState(0);
  const [payOpen, setPayOpen] = React.useState(false);
  const status = params.get('status') ?? '';
  const sort = params.get('sort') ?? 'createdAt,desc';
  const debounced = useDebouncedValue(q);
  const filter = {
    status: status || undefined,
    q: debounced || undefined,
    from: from ? startOfDayIso(from) : undefined,
    to: to ? endOfDayExclusiveIso(to) : undefined,
    sort,
    page,
    size: 15,
  };
  const payments = useQuery({
    queryKey: [role === 'MERCHANT' ? 'merchant-payments' : 'payments', filter],
    queryFn: () => (role === 'MERCHANT' ? merchantService.payments(filter) : paymentService.list(filter)),
    placeholderData: keepPreviousData,
  });
  React.useEffect(() => setPage(0), [debounced, status, from, to, sort]);

  const setParam = (k: string, v: string) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v);
    else next.delete(k);
    setParams(next, { replace: true });
  };

  return (
    <>
      <PageHeader
        title="Payments"
        description={
          role === 'MERCHANT'
            ? 'Payments received, with platform fee and net proceeds for each.'
            : role === 'ADMIN'
              ? 'All payment intents across the platform.'
              : 'Payments you have made to merchants.'
        }
        actions={
          role === 'CUSTOMER' && (
            <Button onClick={() => setPayOpen(true)}>
              <Plus /> New payment
            </Button>
          )
        }
      />
      <Card>
        <div className="grid grid-cols-1 gap-2 p-4 sm:grid-cols-2 lg:grid-cols-[1fr_200px_150px_150px_170px]">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" aria-hidden />
            <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search reference or description" className="pl-9" aria-label="Search payments" />
          </div>
          <NativeSelect value={status} onChange={(e) => setParam('status', e.target.value)} aria-label="Filter by status">
            <option value="">All statuses</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s === 'CREATED' ? 'Awaiting confirmation' : humanize(s)}
              </option>
            ))}
          </NativeSelect>
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="Created from" />
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="Created to" />
          <NativeSelect value={sort} onChange={(e) => setParam('sort', e.target.value)} aria-label="Sort">
            <option value="createdAt,desc">Newest first</option>
            <option value="createdAt,asc">Oldest first</option>
            <option value="amount,desc">Largest amount</option>
            <option value="amount,asc">Smallest amount</option>
          </NativeSelect>
        </div>
        <div className="border-t border-line">
          {payments.isPending ? (
            <TableSkeleton rows={6} cols={6} />
          ) : payments.isError ? (
            <ErrorState error={payments.error} onRetry={() => payments.refetch()} />
          ) : payments.data.items.length === 0 ? (
            <EmptyState
              icon={Receipt}
              title={status || debounced || from || to ? 'No payments match these filters' : 'No payments yet'}
              description={role === 'CUSTOMER' ? 'Pay a merchant from your wallet balance.' : undefined}
              action={
                role === 'CUSTOMER' && (
                  <Button variant="outline" size="sm" onClick={() => setPayOpen(true)}>
                    <Plus /> New payment
                  </Button>
                )
              }
            />
          ) : (
            <>
              <PaymentsTable items={payments.data.items} perspective={role} />
              <Pagination
                page={payments.data.page}
                size={payments.data.size}
                totalItems={payments.data.totalItems}
                totalPages={payments.data.totalPages}
                onPage={setPage}
              />
            </>
          )}
        </div>
      </Card>
      {role === 'CUSTOMER' && <PayMerchantDialog open={payOpen} onOpenChange={setPayOpen} />}
    </>
  );
}
