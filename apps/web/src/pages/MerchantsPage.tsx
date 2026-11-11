import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Landmark, Search, Store } from 'lucide-react';
import * as React from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router';
import { toast } from 'sonner';
import { z } from 'zod';
import { EmptyState, ErrorState, Money, PageHeader, StatCard, StatusBadge } from '@/components/shared';
import { ChartLegend, VolumeChart } from '@/components/shared/finance';
import { Button } from '@/components/ui/button';
import { Field, Input, MoneyInput, NativeSelect } from '@/components/ui/form';
import { ConfirmDialog } from '@/components/ui/overlay';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Skeleton, Table, TBody, TD, TH, THead, TR, Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/primitives';
import { useAuth } from '@/features/auth/AuthProvider';
import { invalidateMoney, PayMerchantDialog } from '@/features/payments';
import { useDebouncedValue, useDocumentTitle, useIdempotencyKey } from '@/hooks';
import { errorMessage } from '@/lib/api';
import { formatDateTime } from '@/lib/format';
import { currencySymbol, formatMoney, parseMoneyInput } from '@/lib/money';
import { initials } from '@/lib/utils';
import { authService, merchantService, paymentService } from '@/services';
import type { DirectoryEntry } from '@/types/api';

// ---------------------------------------------------------------- Customer: directory

function MerchantDirectory() {
  const [q, setQ] = React.useState('');
  const debounced = useDebouncedValue(q);
  const merchants = useQuery({ queryKey: ['merchants', debounced], queryFn: () => paymentService.merchants(debounced || undefined) });
  const [selected, setSelected] = React.useState<DirectoryEntry | undefined>();
  const [open, setOpen] = React.useState(false);
  return (
    <>
      <PageHeader title="Merchants" description="Pay any LedgerForge merchant directly from your wallet." />
      <div className="relative mb-5 max-w-md">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-faint" aria-hidden />
        <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search merchants or categories" className="pl-9" aria-label="Search merchants" />
      </div>
      {merchants.isPending ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-32 rounded-xl" />
          ))}
        </div>
      ) : merchants.isError ? (
        <Card>
          <ErrorState error={merchants.error} onRetry={() => merchants.refetch()} />
        </Card>
      ) : merchants.data.length === 0 ? (
        <Card>
          <EmptyState icon={Store} title="No merchants found" description="Try a different search." />
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {merchants.data.map((m) => (
            <Card key={m.merchantId} className="flex flex-col p-5">
              <div className="flex items-start gap-3">
                <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-subtle text-sm font-semibold text-ink-soft ring-1 ring-inset ring-line">
                  {initials(m.businessName)}
                </span>
                <div className="min-w-0">
                  <p className="truncate font-medium text-ink">{m.businessName}</p>
                  <p className="text-[13px] text-muted">{m.category}</p>
                </div>
              </div>
              <div className="mt-4 flex items-center justify-between">
                <div className="flex gap-1">
                  {m.currencies.map((c) => (
                    <Badge key={c}>{c}</Badge>
                  ))}
                </div>
                <Button size="sm" onClick={() => { setSelected(m); setOpen(true); }}>
                  Pay
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}
      <PayMerchantDialog open={open} onOpenChange={setOpen} merchant={selected} />
    </>
  );
}

// ---------------------------------------------------------------- Merchant: business center

function PayoutCard() {
  const queryClient = useQueryClient();
  const balance = useQuery({ queryKey: ['merchant-balance'], queryFn: merchantService.balance });
  const payouts = useQuery({ queryKey: ['payouts'], queryFn: () => merchantService.payouts() });
  const { key, reset } = useIdempotencyKey();
  const [amount, setAmount] = React.useState('');
  const [error, setError] = React.useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = React.useState(false);
  const parsed = parseMoneyInput(amount);
  const currency = balance.data?.currency ?? 'INR';
  const payout = useMutation({
    mutationFn: (minor: number) => merchantService.payout(minor, key),
    onSuccess: (p) => {
      reset();
      setAmount('');
      setConfirmOpen(false);
      invalidateMoney(queryClient);
      queryClient.invalidateQueries({ queryKey: ['payouts'] });
      toast.success('Payout sent', { description: `${formatMoney(p.amountMinor, p.currency)} · ${p.reference}` });
    },
    onError: (e) => setError(errorMessage(e)),
  });
  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Payouts</CardTitle>
          <CardDescription>Settle your available balance to your linked bank account (simulated).</CardDescription>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="rounded-xl border border-line bg-subtle/50 p-4">
          <p className="text-[13px] text-muted">Available to pay out</p>
          {balance.data ? (
            <p className="tabular mt-1 text-2xl font-semibold">{formatMoney(balance.data.availableMinor, currency)}</p>
          ) : (
            <Skeleton className="mt-2 h-7 w-36" />
          )}
          {balance.data && <p className="mt-1 font-mono text-xs text-muted">{balance.data.accountNumber}</p>}
        </div>
        <form
          noValidate
          className="flex flex-col gap-2 sm:flex-row sm:items-start"
          onSubmit={(e) => {
            e.preventDefault();
            if (!parsed.ok) return setError(parsed.error);
            if (balance.data && parsed.minor > balance.data.availableMinor) return setError('Amount exceeds your available balance');
            setError(null);
            setConfirmOpen(true);
          }}
        >
          <div className="flex-1">
            <Field id="payout-amount" label="Amount" error={error ?? undefined}>
              <MoneyInput symbol={currencySymbol(currency)} placeholder="0.00" value={amount} onChange={(e) => { setAmount(e.target.value); reset(); setError(null); }} />
            </Field>
          </div>
          <Button type="submit" className="sm:mt-[26px]" disabled={!balance.data || balance.data.availableMinor === 0}>
            <Landmark /> Pay out
          </Button>
        </form>
        <div>
          <p className="mb-2 text-[13px] font-medium text-ink-soft">Recent payouts</p>
          {payouts.data?.items.length ? (
            <ul className="divide-y divide-line rounded-lg border border-line">
              {payouts.data.items.map((p) => (
                <li key={p.id} className="flex items-center justify-between px-4 py-2.5 text-sm">
                  <div>
                    <Link to={`/transactions/${p.ledgerTransactionId}`} className="font-mono text-xs font-medium hover:text-primary">
                      {p.reference}
                    </Link>
                    <p className="text-xs text-muted">{formatDateTime(p.createdAt)}</p>
                  </div>
                  <div className="flex items-center gap-3">
                    <Money minor={p.amountMinor} currency={p.currency} className="font-medium" />
                    <StatusBadge status={p.status} />
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <p className="rounded-lg border border-dashed border-line px-4 py-6 text-center text-[13px] text-muted">No payouts yet.</p>
          )}
        </div>
      </CardContent>
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="Confirm payout"
        description="Posts Debit Merchant Payable / Credit Platform Funding Clearing."
        confirmLabel={parsed.ok ? `Pay out ${formatMoney(parsed.minor, currency)}` : 'Pay out'}
        loading={payout.isPending}
        onConfirm={() => parsed.ok && payout.mutate(parsed.minor)}
      >
        {error && <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-[13px] text-danger-strong">{error}</p>}
      </ConfirmDialog>
    </Card>
  );
}

function ReportsCard() {
  const today = new Date().toISOString().slice(0, 10);
  const [from, setFrom] = React.useState(new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10));
  const [to, setTo] = React.useState(today);
  const report = useQuery({ queryKey: ['merchant-report', from, to], queryFn: () => merchantService.report(from, to) });
  const r = report.data;
  return (
    <Card>
      <CardHeader className="flex-col gap-3 sm:flex-row sm:items-end">
        <div>
          <CardTitle>Activity report</CardTitle>
          <CardDescription>Daily totals by capture date (UTC), computed in PostgreSQL.</CardDescription>
        </div>
        <div className="flex items-center gap-2">
          <Input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} aria-label="Report from" className="w-40" />
          <span className="text-muted">–</span>
          <Input type="date" value={to} min={from} max={today} onChange={(e) => setTo(e.target.value)} aria-label="Report to" className="w-40" />
        </div>
      </CardHeader>
      <CardContent>
        {report.isError ? (
          <ErrorState error={report.error} onRetry={() => report.refetch()} />
        ) : (
          <>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              <StatCard label="Gross" loading={!r} value={r && formatMoney(r.grossVolumeMinor, r.currency)} />
              <StatCard label="Fees" loading={!r} value={r && formatMoney(r.platformFeesMinor, r.currency)} />
              <StatCard label="Net" loading={!r} value={r && formatMoney(r.netVolumeMinor, r.currency)} />
              <StatCard label="Refunded" loading={!r} value={r && formatMoney(r.refundedMinor, r.currency)} hint={r && `${r.successfulPayments} payments`} />
            </div>
            <div className="mt-5 flex justify-end">
              <ChartLegend items={[{ label: 'Captured', color: '#2563eb' }, { label: 'Refunded', color: '#f59e0b' }]} />
            </div>
            {r ? <VolumeChart data={r.days} currency={r.currency} /> : <Skeleton className="h-64 w-full" />}
            {r && r.days.some((d) => d.count > 0) && (
              <Table className="mt-4">
                <THead>
                  <TR>
                    <TH>Date</TH>
                    <TH className="text-right">Payments</TH>
                    <TH className="text-right">Gross</TH>
                    <TH className="hidden text-right sm:table-cell">Fees</TH>
                    <TH className="text-right">Net</TH>
                    <TH className="hidden text-right sm:table-cell">Refunds</TH>
                  </TR>
                </THead>
                <TBody>
                  {r.days.filter((d) => d.count > 0 || d.refundsMinor > 0).reverse().map((d) => (
                    <TR key={d.date}>
                      <TD className="whitespace-nowrap">{d.date}</TD>
                      <TD className="tabular text-right">{d.count}</TD>
                      <TD className="text-right"><Money minor={d.grossMinor} currency={r.currency} /></TD>
                      <TD className="hidden text-right text-ink-soft sm:table-cell"><Money minor={d.feesMinor} currency={r.currency} /></TD>
                      <TD className="text-right"><Money minor={d.netMinor} currency={r.currency} /></TD>
                      <TD className="hidden text-right text-ink-soft sm:table-cell"><Money minor={d.refundsMinor} currency={r.currency} /></TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

const profileSchema = z.object({
  businessName: z.string().trim().min(2, 'Enter a business name').max(120),
  category: z.string().min(1).max(64),
  supportEmail: z.union([z.literal(''), z.email('Enter a valid email')]).optional(),
  website: z.union([z.literal(''), z.url('Enter a valid URL')]).optional(),
});
type ProfileValues = z.infer<typeof profileSchema>;

function ProfileCard() {
  const queryClient = useQueryClient();
  const { setUser } = useAuth();
  const profile = useQuery({ queryKey: ['merchant-profile'], queryFn: merchantService.profile });
  const { register, handleSubmit, reset, formState: { errors, isDirty } } = useForm<ProfileValues>({ resolver: zodResolver(profileSchema) });
  React.useEffect(() => {
    if (profile.data)
      reset({
        businessName: profile.data.businessName,
        category: profile.data.category,
        supportEmail: profile.data.supportEmail ?? '',
        website: profile.data.website ?? '',
      });
  }, [profile.data, reset]);
  const save = useMutation({
    mutationFn: (v: ProfileValues) =>
      merchantService.updateProfile({ businessName: v.businessName, category: v.category, supportEmail: v.supportEmail || undefined, website: v.website || undefined }),
    onSuccess: async () => {
      toast.success('Business profile saved');
      queryClient.invalidateQueries({ queryKey: ['merchant-profile'] });
      setUser(await authService.me());
    },
    onError: (e) => toast.error('Could not save', { description: errorMessage(e) }),
  });
  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Business profile</CardTitle>
          <CardDescription>Shown to customers in the merchant directory.</CardDescription>
        </div>
        {profile.data && <StatusBadge status={profile.data.status} />}
      </CardHeader>
      <CardContent>
        <form noValidate onSubmit={handleSubmit((v) => save.mutate(v))} className="space-y-4">
          <Field id="m-business" label="Business name" error={errors.businessName?.message}>
            <Input {...register('businessName')} />
          </Field>
          <Field id="m-category" label="Category">
            <NativeSelect {...register('category')}>
              {['Retail', 'Food & Beverage', 'Digital goods', 'Services', 'Education', 'Travel', 'General'].map((c) => (
                <option key={c}>{c}</option>
              ))}
            </NativeSelect>
          </Field>
          <Field id="m-email" label="Support email" error={errors.supportEmail?.message}>
            <Input type="email" {...register('supportEmail')} />
          </Field>
          <Field id="m-website" label="Website" error={errors.website?.message}>
            <Input placeholder="https://" {...register('website')} />
          </Field>
          <Button type="submit" loading={save.isPending} disabled={!isDirty}>
            Save changes
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

function MerchantCenter() {
  return (
    <>
      <PageHeader title="Merchant" description="Reports, payouts and your public business profile." />
      <Tabs defaultValue="reports">
        <TabsList className="mb-5" aria-label="Merchant sections">
          <TabsTrigger value="reports">Reports</TabsTrigger>
          <TabsTrigger value="payouts">Payouts</TabsTrigger>
          <TabsTrigger value="profile">Profile</TabsTrigger>
        </TabsList>
        <TabsContent value="reports">
          <ReportsCard />
        </TabsContent>
        <TabsContent value="payouts" className="max-w-2xl">
          <PayoutCard />
        </TabsContent>
        <TabsContent value="profile" className="max-w-2xl">
          <ProfileCard />
        </TabsContent>
      </Tabs>
    </>
  );
}

export default function MerchantsPage() {
  useDocumentTitle('Merchants');
  const { user } = useAuth();
  return user?.role === 'MERCHANT' ? <MerchantCenter /> : <MerchantDirectory />;
}
