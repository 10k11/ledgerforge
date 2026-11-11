import * as DialogPrimitive from '@radix-ui/react-dialog';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeftRight, CornerDownLeft, PlusCircle, Receipt, Search, Store, Webhook, type LucideIcon } from 'lucide-react';
import * as React from 'react';
import { useNavigate } from 'react-router';
import { TxTypeIcon, typeLabel } from '@/components/shared';
import { useAuth } from '@/features/auth/AuthProvider';
import { useDebouncedValue } from '@/hooks';
import { navItemsFor } from '@/layouts/nav';
import { formatMoney } from '@/lib/money';
import { cn } from '@/lib/utils';
import { transactionService } from '@/services';
import type { Role } from '@/types/api';

interface Command {
  id: string;
  label: string;
  hint?: string;
  icon?: LucideIcon;
  node?: React.ReactNode;
  group: string;
  run: () => void;
}

const ACTIONS: { label: string; to: string; icon: LucideIcon; roles: Role[]; keywords: string }[] = [
  { label: 'Add sandbox funds', to: '/wallet?topup=1', icon: PlusCircle, roles: ['CUSTOMER'], keywords: 'top up deposit' },
  { label: 'Send money', to: '/transfers', icon: ArrowLeftRight, roles: ['CUSTOMER'], keywords: 'transfer pay friend' },
  { label: 'Pay a merchant', to: '/merchants', icon: Store, roles: ['CUSTOMER'], keywords: 'checkout buy' },
  { label: 'Review payments', to: '/payments', icon: Receipt, roles: ['MERCHANT', 'ADMIN'], keywords: 'refund' },
  { label: 'Manage webhooks', to: '/developers', icon: Webhook, roles: ['MERCHANT'], keywords: 'events api' },
];

/** ⌘K / Ctrl+K command palette: navigation, quick actions, and live transaction search. */
export function CommandPalette({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [query, setQuery] = React.useState('');
  const [active, setActive] = React.useState(0);
  const debounced = useDebouncedValue(query.trim(), 200);
  const canSearchTx = user?.role === 'CUSTOMER' || user?.role === 'MERCHANT';
  const search = useQuery({
    queryKey: ['palette-search', debounced],
    queryFn: () => transactionService.list({ q: debounced, size: 5 }),
    enabled: open && canSearchTx && debounced.length >= 2,
  });

  React.useEffect(() => {
    if (!open) {
      setQuery('');
      setActive(0);
    }
  }, [open]);

  const go = React.useCallback(
    (to: string) => {
      onOpenChange(false);
      navigate(to);
    },
    [navigate, onOpenChange],
  );

  const commands = React.useMemo<Command[]>(() => {
    const q = query.trim().toLowerCase();
    const match = (s: string) => !q || s.toLowerCase().includes(q);
    const role = user?.role;
    const list: Command[] = [];
    for (const a of ACTIONS) {
      if (role && a.roles.includes(role) && match(`${a.label} ${a.keywords}`)) {
        list.push({ id: `a-${a.to}`, label: a.label, icon: a.icon, group: 'Quick actions', run: () => go(a.to) });
      }
    }
    for (const n of navItemsFor(role)) {
      if (match(n.label)) list.push({ id: `n-${n.to}`, label: n.label, hint: 'Go to', icon: n.icon, group: 'Navigate', run: () => go(n.to) });
    }
    for (const t of search.data?.items ?? []) {
      list.push({
        id: `t-${t.id}`,
        label: t.counterparty ?? typeLabel(t.type),
        group: 'Transactions',
        node: (
          <span className="flex min-w-0 flex-1 items-center gap-3">
            <TxTypeIcon type={t.type} direction={t.direction} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm text-ink">{t.counterparty ?? typeLabel(t.type)}</span>
              <span className="block truncate font-mono text-[11px] text-muted">{t.reference}</span>
            </span>
            <span className={cn('tabular text-sm font-medium', t.direction === 'IN' ? 'text-success' : 'text-ink')}>
              {formatMoney(t.direction === 'OUT' ? -t.amountMinor : t.amountMinor, t.currency, { sign: 'always' })}
            </span>
          </span>
        ),
        run: () => go(`/transactions/${t.id}`),
      });
    }
    return list;
  }, [query, user?.role, search.data, go]);

  React.useEffect(() => setActive(0), [query, search.data]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => Math.min(commands.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      commands[active]?.run();
    }
  };

  let lastGroup = '';
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="animate-fade fixed inset-0 z-50 bg-ink/30 backdrop-blur-[2px]" />
        <DialogPrimitive.Content
          className="fixed left-1/2 top-[12vh] z-50 w-[calc(100vw-2rem)] max-w-xl -translate-x-1/2 overflow-hidden rounded-2xl border border-line bg-surface shadow-pop focus:outline-none"
          onKeyDown={onKeyDown}
          aria-describedby={undefined}
        >
          <DialogPrimitive.Title className="sr-only">Command palette</DialogPrimitive.Title>
          <div className="flex items-center gap-3 border-b border-line px-4">
            <Search className="size-4 text-muted" aria-hidden />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={canSearchTx ? 'Search transactions, pages and actions…' : 'Jump to a page or action…'}
              className="h-14 flex-1 bg-transparent text-[15px] text-ink outline-none placeholder:text-faint"
              aria-label="Command"
              role="combobox"
              aria-expanded
              aria-controls="palette-list"
              aria-activedescendant={commands[active] ? `cmd-${commands[active].id}` : undefined}
            />
            <kbd className="rounded-md border border-line bg-subtle px-1.5 py-0.5 font-mono text-[11px] text-muted">Esc</kbd>
          </div>
          <div id="palette-list" role="listbox" className="scrollbar-thin max-h-[min(60vh,420px)] overflow-y-auto p-2">
            {commands.length === 0 && (
              <p className="px-3 py-10 text-center text-sm text-muted">
                {search.isFetching ? 'Searching…' : 'No results. Try a reference like LTX_ or PAY_.'}
              </p>
            )}
            {commands.map((c, i) => {
              const header = c.group !== lastGroup ? c.group : null;
              lastGroup = c.group;
              const Icon = c.icon;
              return (
                <React.Fragment key={c.id}>
                  {header && <p className="px-3 pb-1 pt-3 text-[11px] font-medium uppercase tracking-wider text-faint">{header}</p>}
                  <div
                    id={`cmd-${c.id}`}
                    role="option"
                    aria-selected={i === active}
                    onMouseMove={() => setActive(i)}
                    onClick={c.run}
                    className={cn(
                      'flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm',
                      i === active ? 'bg-primary-soft text-primary' : 'text-ink-soft',
                    )}
                  >
                    {c.node ?? (
                      <>
                        {Icon && <Icon className={cn('size-4', i === active ? 'text-primary' : 'text-muted')} aria-hidden />}
                        <span className="flex-1 text-ink">{c.label}</span>
                        {c.hint && <span className="text-xs text-faint">{c.hint}</span>}
                      </>
                    )}
                    {i === active && <CornerDownLeft className="size-3.5 shrink-0 text-primary" aria-hidden />}
                  </div>
                </React.Fragment>
              );
            })}
          </div>
          <div className="flex items-center gap-4 border-t border-line bg-subtle/60 px-4 py-2 text-[11px] text-muted">
            <span><kbd className="font-mono">↑↓</kbd> navigate</span>
            <span><kbd className="font-mono">↵</kbd> open</span>
            <span className="ml-auto">LedgerForge</span>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
