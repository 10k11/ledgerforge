import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Search } from 'lucide-react';
import * as React from 'react';
import { useSearchParams } from 'react-router';
import { ErrorState, PageHeader, Pagination, TableSkeleton } from '@/components/shared';
import { TransactionsTable } from '@/components/shared/finance';
import { Input, NativeSelect } from '@/components/ui/form';
import { Card } from '@/components/ui/primitives';
import { useDebouncedValue, useDocumentTitle } from '@/hooks';
import { endOfDayExclusiveIso, startOfDayIso } from '@/lib/format';
import { transactionService } from '@/services';

const TYPES = [
  ['', 'All types'],
  ['TOP_UP', 'Top-ups'],
  ['TRANSFER', 'Transfers'],
  ['PAYMENT', 'Payments'],
  ['REFUND', 'Refunds'],
  ['PAYOUT', 'Payouts'],
  ['REVERSAL', 'Reversals'],
] as const;

export default function TransactionsPage() {
  useDocumentTitle('Transactions');
  const [params, setParams] = useSearchParams();
  const [q, setQ] = React.useState(params.get('q') ?? '');
  const debounced = useDebouncedValue(q);
  const get = (k: string) => params.get(k) ?? '';
  const page = Number(params.get('page') ?? 0);

  const update = (patch: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    if (!('page' in patch)) next.delete('page');
    setParams(next, { replace: true });
  };
  React.useEffect(() => {
    if ((params.get('q') ?? '') !== debounced) update({ q: debounced });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced]);

  const filter = {
    type: get('type') || undefined,
    direction: get('direction') || undefined,
    currency: get('currency') || undefined,
    q: get('q') || undefined,
    from: get('from') ? startOfDayIso(get('from')) : undefined,
    to: get('to') ? endOfDayExclusiveIso(get('to')) : undefined,
    sort: get('sort') || undefined,
    page,
    size: 20,
  };
  const tx = useQuery({
    queryKey: ['transactions', filter],
    queryFn: () => transactionService.list(filter),
    placeholderData: keepPreviousData,
  });

  return (
    <>
      <PageHeader
        title="Transactions"
        description="Ledger transactions that touched your accounts. Filtering and pagination run in the database."
      />
      <Card>
        <div className="grid grid-cols-1 gap-2 p-4 sm:grid-cols-2 lg:grid-cols-[1fr_150px_140px_150px_150px_160px]">
          <div className="relative sm:col-span-2 lg:col-span-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" aria-hidden />
            <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search reference or description" className="pl-9" aria-label="Search transactions" />
          </div>
          <NativeSelect value={get('type')} onChange={(e) => update({ type: e.target.value })} aria-label="Transaction type">
            {TYPES.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </NativeSelect>
          <NativeSelect value={get('direction')} onChange={(e) => update({ direction: e.target.value })} aria-label="Direction">
            <option value="">In &amp; out</option>
            <option value="IN">Money in</option>
            <option value="OUT">Money out</option>
          </NativeSelect>
          <Input type="date" value={get('from')} onChange={(e) => update({ from: e.target.value })} aria-label="From date" />
          <Input type="date" value={get('to')} onChange={(e) => update({ to: e.target.value })} aria-label="To date" />
          <NativeSelect value={get('sort') || 'createdAt,desc'} onChange={(e) => update({ sort: e.target.value })} aria-label="Sort">
            <option value="createdAt,desc">Newest first</option>
            <option value="createdAt,asc">Oldest first</option>
            <option value="amount,desc">Largest amount</option>
            <option value="amount,asc">Smallest amount</option>
          </NativeSelect>
        </div>
        <div className="border-t border-line">
          {tx.isPending ? (
            <TableSkeleton rows={8} cols={6} />
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
                onPage={(p) => update({ page: String(p) })}
              />
            </>
          )}
        </div>
      </Card>
    </>
  );
}
