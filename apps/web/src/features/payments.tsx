import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, ShieldCheck, Store, XCircle } from 'lucide-react';
import * as React from 'react';
import { Link } from 'react-router';
import { toast } from 'sonner';
import { DefinitionList } from '@/components/shared';
import { Button } from '@/components/ui/button';
import { Field, Input, MoneyInput, NativeSelect } from '@/components/ui/form';
import { Dialog, DialogContent, DialogFooter } from '@/components/ui/overlay';
import { useIdempotencyKey } from '@/hooks';
import { errorMessage } from '@/lib/api';
import { currencySymbol, formatMoney, parseMoneyInput } from '@/lib/money';
import { paymentService, walletService } from '@/services';
import type { DirectoryEntry, PaymentIntent } from '@/types/api';

export function invalidateMoney(queryClient: ReturnType<typeof useQueryClient>) {
  for (const k of ['wallet', 'dashboard', 'transactions', 'payments', 'payment', 'statement', 'merchant-dashboard', 'merchant-payments', 'merchant-balance', 'refunds', 'notifications']) {
    queryClient.invalidateQueries({ queryKey: [k] });
  }
}

/** Confirms (captures) an existing intent with its own idempotency key. */
export function useConfirmPayment(onDone?: (p: PaymentIntent) => void) {
  const queryClient = useQueryClient();
  const { key, reset } = useIdempotencyKey();
  const mutation = useMutation({
    mutationFn: (id: string) => paymentService.confirm(id, key),
    onSuccess: ({ data }) => {
      reset();
      invalidateMoney(queryClient);
      if (data.status === 'SUCCEEDED') toast.success('Payment successful', { description: data.reference });
      else toast.error('Payment declined', { description: data.failureMessage });
      onDone?.(data);
    },
  });
  return { ...mutation, key };
}

type Stage = 'details' | 'confirm' | 'result';

/** Checkout: create a payment intent, review, then confirm. Each step is idempotent. */
export function PayMerchantDialog({
  open,
  onOpenChange,
  merchant: presetMerchant,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  merchant?: DirectoryEntry;
}) {
  const queryClient = useQueryClient();
  const wallet = useQuery({ queryKey: ['wallet'], queryFn: walletService.get, enabled: open });
  const merchants = useQuery({ queryKey: ['merchants', ''], queryFn: () => paymentService.merchants(), enabled: open && !presetMerchant });
  const createKey = useIdempotencyKey();
  const [merchantId, setMerchantId] = React.useState(presetMerchant?.merchantId ?? '');
  const [amount, setAmount] = React.useState('');
  const [description, setDescription] = React.useState('');
  const [orderId, setOrderId] = React.useState('');
  const [error, setError] = React.useState<string | null>(null);
  const [stage, setStage] = React.useState<Stage>('details');
  const [intent, setIntent] = React.useState<PaymentIntent | null>(null);
  const currency = wallet.data?.currency ?? 'INR';

  React.useEffect(() => {
    if (presetMerchant) setMerchantId(presetMerchant.merchantId);
  }, [presetMerchant]);

  const create = useMutation({
    mutationFn: (minor: number) =>
      paymentService.create(
        {
          merchantId,
          amountMinor: minor,
          currency,
          description: description.trim() || undefined,
          metadata: orderId.trim() ? { orderId: orderId.trim() } : undefined,
        },
        createKey.key,
      ),
    onSuccess: (p) => {
      setIntent(p);
      setStage('confirm');
      createKey.reset();
      queryClient.invalidateQueries({ queryKey: ['payments'] });
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const confirm = useConfirmPayment((p) => {
    setIntent(p);
    setStage('result');
  });

  const close = (o: boolean) => {
    if (create.isPending || confirm.isPending) return;
    onOpenChange(o);
    if (!o) {
      setTimeout(() => {
        setStage('details');
        setAmount('');
        setDescription('');
        setOrderId('');
        setError(null);
        setIntent(null);
        confirm.reset();
      }, 150);
    }
  };

  const merchantName =
    presetMerchant?.businessName ?? merchants.data?.find((m) => m.merchantId === merchantId)?.businessName ?? intent?.merchant?.name;

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent
        title={stage === 'result' ? 'Payment result' : `Pay ${merchantName ?? 'a merchant'}`}
        description={stage === 'details' ? 'Paid from your wallet balance. No card details are ever collected.' : undefined}
      >
        {stage === 'details' && (
          <form
            noValidate
            className="space-y-4"
            onSubmit={(e) => {
              e.preventDefault();
              const parsed = parseMoneyInput(amount);
              if (!merchantId) return setError('Choose a merchant');
              if (!parsed.ok) return setError(parsed.error);
              setError(null);
              create.mutate(parsed.minor);
            }}
          >
            {!presetMerchant && (
              <Field id="pay-merchant" label="Merchant">
                <NativeSelect value={merchantId} onChange={(e) => { setMerchantId(e.target.value); createKey.reset(); }}>
                  <option value="">Select a merchant…</option>
                  {merchants.data?.map((m) => (
                    <option key={m.merchantId} value={m.merchantId}>
                      {m.businessName} · {m.category}
                    </option>
                  ))}
                </NativeSelect>
              </Field>
            )}
            <Field
              id="pay-amount"
              label="Amount"
              hint={wallet.data ? `Wallet balance ${formatMoney(wallet.data.balanceMinor, currency)}` : undefined}
            >
              <MoneyInput symbol={currencySymbol(currency)} placeholder="0.00" value={amount} onChange={(e) => { setAmount(e.target.value); createKey.reset(); setError(null); }} />
            </Field>
            <Field id="pay-desc" label="Description (optional)">
              <Input value={description} maxLength={255} placeholder="Order #1042" onChange={(e) => { setDescription(e.target.value); createKey.reset(); }} />
            </Field>
            <Field id="pay-order" label="Order reference (optional)" hint="Stored as payment metadata and sent in webhooks.">
              <Input value={orderId} maxLength={200} placeholder="CC-1088" onChange={(e) => { setOrderId(e.target.value); createKey.reset(); }} />
            </Field>
            {error && (
              <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-[13px] text-danger-strong">
                {error}
              </p>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => close(false)}>
                Cancel
              </Button>
              <Button type="submit" loading={create.isPending}>
                Continue
              </Button>
            </DialogFooter>
          </form>
        )}
        {stage === 'confirm' && intent && (
          <div>
            <div className="rounded-xl border border-line bg-subtle/50 p-5 text-center">
              <Store className="mx-auto size-6 text-muted" aria-hidden />
              <p className="mt-2 text-[13px] text-muted">Pay {intent.merchant?.name}</p>
              <p className="tabular mt-1 text-3xl font-semibold tracking-tight">{formatMoney(intent.amountMinor, intent.currency)}</p>
            </div>
            <div className="mt-4">
              <DefinitionList
                items={[
                  { label: 'Payment reference', value: <span className="font-mono text-[13px]">{intent.reference}</span> },
                  { label: 'Description', value: intent.description ?? '—' },
                  { label: 'You pay', value: formatMoney(intent.amountMinor, intent.currency) },
                  {
                    label: 'Balance after',
                    value: wallet.data ? formatMoney(wallet.data.balanceMinor - intent.amountMinor, intent.currency) : '—',
                  },
                ]}
              />
            </div>
            {confirm.error && (
              <p role="alert" className="mt-3 rounded-lg bg-danger-soft px-3 py-2 text-[13px] text-danger-strong">
                {errorMessage(confirm.error)}
              </p>
            )}
            <p className="mt-3 flex items-start gap-2 text-xs text-muted">
              <ShieldCheck className="mt-0.5 size-3.5 shrink-0" aria-hidden /> A payment can be captured at most once, even if you retry.
            </p>
            <DialogFooter>
              <Button variant="outline" onClick={() => close(false)} disabled={confirm.isPending}>
                Pay later
              </Button>
              <Button onClick={() => confirm.mutate(intent.id)} loading={confirm.isPending}>
                Confirm payment
              </Button>
            </DialogFooter>
          </div>
        )}
        {stage === 'result' && intent && (
          <div className="text-center">
            {intent.status === 'SUCCEEDED' ? (
              <CheckCircle2 className="mx-auto size-10 text-success" aria-hidden />
            ) : (
              <XCircle className="mx-auto size-10 text-danger-strong" aria-hidden />
            )}
            <p className="mt-3 text-base font-semibold">{intent.status === 'SUCCEEDED' ? 'Payment successful' : 'Payment declined'}</p>
            <p className="tabular mt-1 text-2xl font-semibold">{formatMoney(intent.amountMinor, intent.currency)}</p>
            {intent.failureMessage && <p className="mt-2 text-[13px] text-muted">{intent.failureMessage}</p>}
            <p className="mt-2 font-mono text-xs text-muted">{intent.reference}</p>
            <DialogFooter className="sm:justify-center">
              <Button variant="outline" asChild>
                <Link to={`/payments/${intent.id}`}>View payment</Link>
              </Button>
              <Button onClick={() => close(false)}>Done</Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
