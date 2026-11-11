import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2, KeyRound, Plus, RefreshCw, RotateCcw, Send, ShieldCheck, Trash2, Webhook, XCircle } from 'lucide-react';
import * as React from 'react';
import { useSearchParams } from 'react-router';
import { toast } from 'sonner';
import { CopyButton, DefinitionList, EmptyState, ErrorState, JsonViewer, PageHeader, Pagination, StatusBadge, TableSkeleton } from '@/components/shared';
import { Button } from '@/components/ui/button';
import { Field, Input, NativeSelect } from '@/components/ui/form';
import { ConfirmDialog, Dialog, DialogContent, DialogFooter } from '@/components/ui/overlay';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Checkbox, Skeleton, Switch, Table, TBody, TD, TH, THead, TR, Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/primitives';
import { useAuth } from '@/features/auth/AuthProvider';
import { useDocumentTitle } from '@/hooks';
import { errorMessage } from '@/lib/api';
import { formatDateTime, relativeTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import { metaService, webhookService } from '@/services';
import type { DeliveryView, WebhookView, WebhookWithSecret } from '@/types/api';

function SecretRevealDialog({ data, onClose }: { data: WebhookWithSecret | null; onClose: () => void }) {
  return (
    <Dialog open={!!data} onOpenChange={(o) => !o && onClose()}>
      <DialogContent title="Save your signing secret" description="This is the only time the full secret is shown.">
        <div className="flex gap-3 rounded-lg border border-amber-200 bg-warning-soft p-3 text-[13px] text-amber-900">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden />
          Store it in your server's secret manager. LedgerForge keeps it encrypted and will only ever display the hint{' '}
          <span className="font-mono">{data?.webhook.secretHint}</span> from now on.
        </div>
        <div className="mt-4 flex items-center gap-2 rounded-lg border border-line bg-subtle px-3 py-2">
          <code className="min-w-0 flex-1 break-all font-mono text-[13px]">{data?.secret}</code>
          {data && <CopyButton value={data.secret} label="Copy signing secret" />}
        </div>
        <DialogFooter>
          <Button onClick={onClose}>I've saved it</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function EndpointDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  onCreated: (w: WebhookWithSecret) => void;
}) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const meta = useQuery({ queryKey: ['meta'], queryFn: metaService.get, staleTime: Infinity });
  const types = useQuery({ queryKey: ['event-types'], queryFn: webhookService.eventTypes, staleTime: Infinity });
  const [url, setUrl] = React.useState('');
  const [description, setDescription] = React.useState('');
  const [events, setEvents] = React.useState<string[]>(['payment.succeeded', 'payment.refunded']);
  const [error, setError] = React.useState<string | null>(null);
  const create = useMutation({
    mutationFn: () => webhookService.create({ url: url.trim(), description: description.trim() || undefined, events }),
    onSuccess: (w) => {
      queryClient.invalidateQueries({ queryKey: ['webhooks'] });
      onOpenChange(false);
      setUrl('');
      setDescription('');
      onCreated(w);
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const sandboxUrl = meta.data?.sandboxReceiverBaseUrl && user ? `${meta.data.sandboxReceiverBaseUrl}${user.id}` : null;
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Add webhook endpoint" description="Events are POSTed as JSON with an HMAC-SHA256 signature." className="max-w-lg">
        <form
          noValidate
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (!url.trim()) return setError('Enter the endpoint URL');
            if (!events.length) return setError('Select at least one event');
            setError(null);
            create.mutate();
          }}
        >
          <Field
            id="wh-url"
            label="Endpoint URL"
            hint="Must be HTTPS and publicly routable. Private and internal addresses are rejected (SSRF protection)."
          >
            <Input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://example.com/webhooks/ledgerforge" />
          </Field>
          {sandboxUrl && (
            <button type="button" className="-mt-2 text-[13px] font-medium text-primary hover:underline" onClick={() => setUrl(sandboxUrl)}>
              Use the built-in sandbox receiver
            </button>
          )}
          <Field id="wh-desc" label="Description (optional)">
            <Input value={description} maxLength={255} onChange={(e) => setDescription(e.target.value)} placeholder="Order fulfilment service" />
          </Field>
          <fieldset>
            <legend className="mb-2 text-[13px] font-medium text-ink-soft">Events</legend>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {(types.data ?? []).map((t) => (
                <label key={t} className="flex items-center gap-2.5 rounded-lg border border-line px-3 py-2 text-[13px] hover:bg-subtle">
                  <Checkbox
                    checked={events.includes(t)}
                    onCheckedChange={(c) => setEvents((prev) => (c ? [...prev, t] : prev.filter((x) => x !== t)))}
                  />
                  <span className="font-mono">{t}</span>
                </label>
              ))}
            </div>
          </fieldset>
          {error && (
            <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-[13px] text-danger-strong">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={create.isPending}>
              Add endpoint
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function DeliveryDialog({ webhookId, deliveryId, onClose }: { webhookId: string; deliveryId: string | null; onClose: () => void }) {
  const queryClient = useQueryClient();
  const delivery = useQuery({
    queryKey: ['delivery', webhookId, deliveryId],
    queryFn: () => webhookService.delivery(webhookId, deliveryId!),
    enabled: !!deliveryId,
    refetchInterval: (q) => (q.state.data && ['PENDING', 'RETRYING'].includes(q.state.data.status) ? 3000 : false),
  });
  const replay = useMutation({
    mutationFn: () => webhookService.replay(webhookId, deliveryId!),
    onSuccess: () => {
      toast.success('Delivery re-queued');
      queryClient.invalidateQueries({ queryKey: ['delivery', webhookId, deliveryId] });
      queryClient.invalidateQueries({ queryKey: ['deliveries', webhookId] });
      queryClient.invalidateQueries({ queryKey: ['webhooks'] });
    },
    onError: (e) => toast.error('Replay rejected', { description: errorMessage(e) }),
  });
  const d = delivery.data;
  return (
    <Dialog open={!!deliveryId} onOpenChange={(o) => !o && onClose()}>
      <DialogContent title={d ? d.eventType : 'Delivery'} description={d ? `Event ${d.eventId}` : undefined} className="max-w-2xl">
        {!d ? (
          <Skeleton className="h-48 w-full" />
        ) : (
          <div className="space-y-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <StatusBadge status={d.status} />
              <Button
                variant="outline"
                size="sm"
                onClick={() => replay.mutate()}
                loading={replay.isPending}
                disabled={d.status === 'PENDING' || d.status === 'RETRYING'}
              >
                <RotateCcw /> Replay
              </Button>
            </div>
            <DefinitionList
              items={[
                { label: 'Attempts', value: `${d.attemptCount} (manual replays: ${d.replayCount})` },
                { label: 'Last HTTP status', value: d.lastHttpStatus ?? '—' },
                { label: 'Last error', value: d.lastError ?? '—' },
                { label: 'Next attempt', value: d.nextAttemptAt ? formatDateTime(d.nextAttemptAt) : '—' },
                { label: 'Delivered', value: d.deliveredAt ? formatDateTime(d.deliveredAt) : '—' },
              ]}
            />
            <div>
              <p className="mb-2 text-[13px] font-medium text-ink-soft">Payload</p>
              <JsonViewer value={d.payload} />
            </div>
            <div>
              <p className="mb-2 text-[13px] font-medium text-ink-soft">Attempt history</p>
              {d.attempts.length === 0 ? (
                <p className="text-[13px] text-muted">Not attempted yet.</p>
              ) : (
                <ul className="divide-y divide-line rounded-lg border border-line">
                  {d.attempts.map((a) => (
                    <li key={`${a.attemptNumber}-${a.attemptedAt}`} className="flex items-start gap-3 px-4 py-2.5 text-[13px]">
                      {a.error ? <XCircle className="mt-0.5 size-4 shrink-0 text-danger-strong" /> : <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-success" />}
                      <div className="min-w-0 flex-1">
                        <p className="font-medium">
                          Attempt {a.attemptNumber} · {a.httpStatus ? `HTTP ${a.httpStatus}` : 'No response'} · {a.durationMs} ms
                        </p>
                        {a.error && <p className="text-muted">{a.error}</p>}
                        {a.responseSnippet && <p className="truncate font-mono text-xs text-faint">{a.responseSnippet}</p>}
                      </div>
                      <span className="shrink-0 text-xs text-muted">{formatDateTime(a.attemptedAt)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function Deliveries({ webhook }: { webhook: WebhookView }) {
  const [status, setStatus] = React.useState('');
  const [page, setPage] = React.useState(0);
  const [selected, setSelected] = React.useState<string | null>(null);
  const params = { status: status || undefined, page, size: 10 };
  const deliveries = useQuery({
    queryKey: ['deliveries', webhook.id, params],
    queryFn: () => webhookService.deliveries(webhook.id, params),
    placeholderData: keepPreviousData,
    refetchInterval: 5000,
  });
  return (
    <Card>
      <CardHeader className="flex-col gap-3 pb-4 sm:flex-row sm:items-center">
        <div>
          <CardTitle>Delivery history</CardTitle>
          <CardDescription>At-least-once delivery with exponential backoff; dead-lettered after repeated failures.</CardDescription>
        </div>
        <NativeSelect value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className="w-40" aria-label="Delivery status">
          <option value="">All statuses</option>
          <option value="SUCCEEDED">Succeeded</option>
          <option value="RETRYING">Retrying</option>
          <option value="PENDING">Pending</option>
          <option value="DEAD">Dead-lettered</option>
        </NativeSelect>
      </CardHeader>
      <div className="border-t border-line">
        {deliveries.isPending ? (
          <TableSkeleton rows={4} />
        ) : deliveries.isError ? (
          <ErrorState error={deliveries.error} onRetry={() => deliveries.refetch()} />
        ) : deliveries.data.items.length === 0 ? (
          <EmptyState icon={Send} title="No deliveries yet" description="Send a test event or make a payment to see deliveries here." />
        ) : (
          <>
            <Table>
              <THead>
                <TR>
                  <TH>Event</TH>
                  <TH>Status</TH>
                  <TH className="hidden text-right sm:table-cell">Attempts</TH>
                  <TH className="hidden md:table-cell">HTTP</TH>
                  <TH className="hidden lg:table-cell">Created</TH>
                </TR>
              </THead>
              <TBody>
                {deliveries.data.items.map((d: DeliveryView) => (
                  <TR
                    key={d.id}
                    tabIndex={0}
                    role="button"
                    aria-label={`Inspect delivery of ${d.eventType}`}
                    onClick={() => setSelected(d.id)}
                    onKeyDown={(e) => e.key === 'Enter' && setSelected(d.id)}
                    className="cursor-pointer hover:bg-subtle/60 focus-visible:bg-subtle/60 focus-visible:outline-none"
                  >
                    <TD>
                      <p className="font-mono text-[13px] font-medium">{d.eventType}</p>
                      <p className="font-mono text-xs text-muted">{d.eventId.slice(0, 18)}…</p>
                    </TD>
                    <TD>
                      <StatusBadge status={d.status} />
                    </TD>
                    <TD className="tabular hidden text-right sm:table-cell">{d.attemptCount}</TD>
                    <TD className="hidden md:table-cell">
                      {d.lastHttpStatus ? (
                        <Badge tone={d.lastHttpStatus < 300 ? 'success' : 'danger'}>{d.lastHttpStatus}</Badge>
                      ) : (
                        <span className="text-faint">—</span>
                      )}
                    </TD>
                    <TD className="hidden whitespace-nowrap text-ink-soft lg:table-cell">{relativeTime(d.createdAt)}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
            <Pagination page={deliveries.data.page} size={deliveries.data.size} totalItems={deliveries.data.totalItems} totalPages={deliveries.data.totalPages} onPage={setPage} />
          </>
        )}
      </div>
      <DeliveryDialog webhookId={webhook.id} deliveryId={selected} onClose={() => setSelected(null)} />
    </Card>
  );
}

function EndpointPanel({ webhook, onSecret }: { webhook: WebhookView; onSecret: (w: WebhookWithSecret) => void }) {
  const queryClient = useQueryClient();
  const [deleteOpen, setDeleteOpen] = React.useState(false);
  const [rotateOpen, setRotateOpen] = React.useState(false);
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['webhooks'] });
    queryClient.invalidateQueries({ queryKey: ['deliveries', webhook.id] });
  };
  const toggle = useMutation({
    mutationFn: (enabled: boolean) => webhookService.update(webhook.id, { enabled }),
    onSuccess: (w) => {
      refresh();
      toast.success(w.enabled ? 'Endpoint enabled' : 'Endpoint disabled');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const test = useMutation({
    mutationFn: () => webhookService.test(webhook.id),
    onSuccess: () => {
      refresh();
      toast.success('Test event queued', { description: 'It will be delivered within a few seconds.' });
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const rotate = useMutation({
    mutationFn: () => webhookService.rotate(webhook.id),
    onSuccess: (w) => {
      setRotateOpen(false);
      refresh();
      onSecret(w);
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const remove = useMutation({
    mutationFn: () => webhookService.remove(webhook.id),
    onSuccess: () => {
      setDeleteOpen(false);
      refresh();
      toast.success('Endpoint deleted');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="min-w-0">
            <CardTitle className="break-all font-mono text-[14px]">{webhook.url}</CardTitle>
            <CardDescription>{webhook.description ?? 'No description'}</CardDescription>
          </div>
          <label className="flex shrink-0 items-center gap-2 text-[13px] text-ink-soft">
            <Switch checked={webhook.enabled} onCheckedChange={(v) => toggle.mutate(v)} disabled={toggle.isPending} aria-label="Endpoint enabled" />
            {webhook.enabled ? 'Enabled' : 'Disabled'}
          </label>
        </CardHeader>
        <CardContent>
          <DefinitionList
            items={[
              {
                label: 'Events',
                value: (
                  <span className="flex flex-wrap gap-1.5">
                    {webhook.events.map((e) => (
                      <Badge key={e} className="font-mono">
                        {e}
                      </Badge>
                    ))}
                  </span>
                ),
              },
              {
                label: 'Signing secret',
                value: (
                  <span className="flex items-center gap-2">
                    <KeyRound className="size-4 text-muted" aria-hidden />
                    <span className="font-mono text-[13px]">{webhook.secretHint}</span>
                    <span className="text-xs text-muted">(encrypted at rest; shown once)</span>
                  </span>
                ),
              },
              {
                label: 'Deliveries',
                value: `${webhook.stats.succeeded} succeeded · ${webhook.stats.pending} in flight · ${webhook.stats.dead} dead-lettered`,
              },
              { label: 'Created', value: formatDateTime(webhook.createdAt) },
            ]}
          />
          <div className="mt-5 flex flex-wrap gap-2">
            <Button variant="outline" size="sm" onClick={() => test.mutate()} loading={test.isPending} disabled={!webhook.enabled}>
              <Send /> Send test event
            </Button>
            <Button variant="outline" size="sm" onClick={() => setRotateOpen(true)}>
              <RefreshCw /> Rotate secret
            </Button>
            <Button variant="ghost" size="sm" className="text-danger-strong hover:bg-danger-soft" onClick={() => setDeleteOpen(true)}>
              <Trash2 /> Delete
            </Button>
          </div>
        </CardContent>
      </Card>
      <Deliveries webhook={webhook} />
      <ConfirmDialog
        open={rotateOpen}
        onOpenChange={setRotateOpen}
        title="Rotate signing secret?"
        description="The current secret stops working immediately. Update your server with the new secret."
        confirmLabel="Rotate secret"
        loading={rotate.isPending}
        onConfirm={() => rotate.mutate()}
      />
      <ConfirmDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title="Delete this endpoint?"
        description="No further events will be delivered to it. Delivery history is retained."
        confirmLabel="Delete endpoint"
        tone="danger"
        loading={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </div>
  );
}

const NODE_SNIPPET = `import crypto from 'node:crypto';

// header: LedgerForge-Signature: t=<unix>,v1=<hex>
export function verify(rawBody, header, secret, toleranceSec = 300) {
  const parts = Object.fromEntries(header.split(',').map((p) => p.split('=')));
  const t = Number(parts.t);
  if (!t || Math.abs(Date.now() / 1000 - t) > toleranceSec) return false;
  const expected = crypto.createHmac('sha256', secret)
    .update(\`\${t}.\${rawBody}\`).digest('hex');
  const a = Buffer.from(expected), b = Buffer.from(parts.v1 ?? '');
  return a.length === b.length && crypto.timingSafeEqual(a, b); // constant time
}`;

const JAVA_SNIPPET = `// dev.ledgerforge.webhook.WebhookSignature (shipped with the API)
WebhookSignature.Result result = WebhookSignature.verify(
    secret,                                   // whsec_...
    request.getHeader("LedgerForge-Signature"),
    rawBody,                                  // exact bytes received
    Instant.now(),
    Duration.ofMinutes(5));                   // replay window
if (result != WebhookSignature.Result.VALID) return 400;
// De-duplicate on the LedgerForge-Event-Id header before processing.`;

function VerificationGuide() {
  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle className="flex items-center gap-2">
            <ShieldCheck className="size-4 text-muted" aria-hidden /> Verifying signatures
          </CardTitle>
          <CardDescription>
            Compute HMAC-SHA256 over <code className="font-mono">{'`${t}.${body}`'}</code> and compare in constant time.
          </CardDescription>
        </div>
      </CardHeader>
      <CardContent>
        <Tabs defaultValue="node">
          <TabsList aria-label="Language">
            <TabsTrigger value="node">Node.js</TabsTrigger>
            <TabsTrigger value="java">Java</TabsTrigger>
            <TabsTrigger value="headers">Headers</TabsTrigger>
          </TabsList>
          <TabsContent value="node" className="mt-3">
            <pre className="overflow-x-auto rounded-lg bg-[#0f172a] p-4 font-mono text-[12.5px] leading-relaxed text-slate-300">{NODE_SNIPPET}</pre>
          </TabsContent>
          <TabsContent value="java" className="mt-3">
            <pre className="overflow-x-auto rounded-lg bg-[#0f172a] p-4 font-mono text-[12.5px] leading-relaxed text-slate-300">{JAVA_SNIPPET}</pre>
          </TabsContent>
          <TabsContent value="headers" className="mt-3">
            <DefinitionList
              items={[
                { label: 'LedgerForge-Signature', value: <span className="font-mono text-[13px]">t=1767225600,v1=5f2c…</span> },
                { label: 'LedgerForge-Event-Id', value: 'Stable event id. Use it to de-duplicate retries.' },
                { label: 'LedgerForge-Event-Type', value: 'e.g. payment.succeeded' },
                { label: 'LedgerForge-Timestamp', value: 'Unix seconds; also bound into the signature' },
              ]}
            />
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  );
}

function SandboxReceipts() {
  const receipts = useQuery({ queryKey: ['receipts'], queryFn: webhookService.receipts, refetchInterval: 5000 });
  if (!receipts.data?.length) return null;
  return (
    <Card>
      <CardHeader className="pb-4">
        <div>
          <CardTitle>Sandbox receiver</CardTitle>
          <CardDescription>Deliveries received by the built-in receiver, verified with your secret.</CardDescription>
        </div>
      </CardHeader>
      <ul className="divide-y divide-line border-t border-line">
        {receipts.data.slice(0, 8).map((r) => (
          <li key={r.id} className="flex items-center justify-between gap-3 px-5 py-2.5 text-[13px]">
            <span className="font-mono">{r.eventType ?? 'unknown'}</span>
            <span className="flex items-center gap-3">
              {r.signatureValid ? <Badge tone="success">Signature valid</Badge> : <Badge tone="danger">Invalid signature</Badge>}
              <span className="text-xs text-muted">{relativeTime(r.receivedAt)}</span>
            </span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

export default function DevelopersPage() {
  useDocumentTitle('Developers');
  const [params, setParams] = useSearchParams();
  const webhooks = useQuery({ queryKey: ['webhooks'], queryFn: webhookService.list });
  const [createOpen, setCreateOpen] = React.useState(false);
  const [secret, setSecret] = React.useState<WebhookWithSecret | null>(null);
  const selectedId = params.get('webhook') ?? webhooks.data?.[0]?.id;
  const selected = webhooks.data?.find((w) => w.id === selectedId) ?? webhooks.data?.[0];

  return (
    <>
      <PageHeader
        title="Developers"
        description="Receive signed, retried webhook events for your payments."
        actions={
          <Button onClick={() => setCreateOpen(true)}>
            <Plus /> Add endpoint
          </Button>
        }
      />
      {webhooks.isPending ? (
        <Skeleton className="h-64 w-full rounded-xl" />
      ) : webhooks.isError ? (
        <Card>
          <ErrorState error={webhooks.error} onRetry={() => webhooks.refetch()} />
        </Card>
      ) : webhooks.data.length === 0 ? (
        <div className="space-y-6">
          <Card>
            <EmptyState
              icon={Webhook}
              title="No webhook endpoints"
              description="Add an endpoint to receive payment.succeeded, payment.refunded and other events in real time."
              action={
                <Button onClick={() => setCreateOpen(true)}>
                  <Plus /> Add endpoint
                </Button>
              }
            />
          </Card>
          <VerificationGuide />
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-6 xl:grid-cols-[280px_minmax(0,1fr)]">
          <div className="space-y-2">
            <p className="px-1 text-xs font-medium uppercase tracking-wide text-muted">Endpoints</p>
            {webhooks.data.map((w) => (
              <button
                key={w.id}
                onClick={() => setParams({ webhook: w.id }, { replace: true })}
                className={cn(
                  'w-full rounded-xl border p-3 text-left transition-colors',
                  selected?.id === w.id ? 'border-primary bg-primary-soft/60 ring-1 ring-primary' : 'border-line bg-surface hover:bg-subtle',
                )}
              >
                <span className="flex items-center justify-between gap-2">
                  <span className="truncate font-mono text-[12.5px] text-ink">{w.url.replace(/^https?:\/\//, '')}</span>
                  <span className={cn('size-2 shrink-0 rounded-full', w.enabled ? 'bg-emerald-500' : 'bg-faint')} aria-label={w.enabled ? 'Enabled' : 'Disabled'} />
                </span>
                <span className="mt-1 block text-xs text-muted">
                  {w.events.length} events · {w.stats.dead > 0 ? <span className="text-danger-strong">{w.stats.dead} failed</span> : `${w.stats.succeeded} delivered`}
                </span>
              </button>
            ))}
          </div>
          <div className="min-w-0 space-y-6">
            {selected && <EndpointPanel key={selected.id} webhook={selected} onSecret={setSecret} />}
            <SandboxReceipts />
            <VerificationGuide />
          </div>
        </div>
      )}
      <EndpointDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(w) => {
          setParams({ webhook: w.webhook.id }, { replace: true });
          setSecret(w);
        }}
      />
      <SecretRevealDialog data={secret} onClose={() => setSecret(null)} />
    </>
  );
}
