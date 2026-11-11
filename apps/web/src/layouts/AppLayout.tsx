import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeftRight,
  Bell,
  Code2,
  LayoutDashboard,
  ListOrdered,
  LogOut,
  Menu,
  Receipt,
  Settings,
  ShieldCheck,
  Store,
  Wallet,
  X,
  type LucideIcon,
} from 'lucide-react';
import * as React from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/overlay';
import { Badge } from '@/components/ui/primitives';
import { useAuth } from '@/features/auth/AuthProvider';
import { relativeTime } from '@/lib/format';
import { formatMoney } from '@/lib/money';
import { cn, initials } from '@/lib/utils';
import { metaService, notificationService } from '@/services';
import type { Role } from '@/types/api';

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  roles: Role[];
}

/** Navigation adapts to role; every route is still authorised server-side. */
export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, roles: ['CUSTOMER', 'MERCHANT', 'ADMIN'] },
  { to: '/wallet', label: 'Wallet', icon: Wallet, roles: ['CUSTOMER'] },
  { to: '/transfers', label: 'Transfers', icon: ArrowLeftRight, roles: ['CUSTOMER'] },
  { to: '/payments', label: 'Payments', icon: Receipt, roles: ['CUSTOMER', 'MERCHANT', 'ADMIN'] },
  { to: '/transactions', label: 'Transactions', icon: ListOrdered, roles: ['CUSTOMER', 'MERCHANT'] },
  { to: '/merchants', label: 'Merchants', icon: Store, roles: ['CUSTOMER', 'MERCHANT'] },
  { to: '/developers', label: 'Developers', icon: Code2, roles: ['MERCHANT'] },
  { to: '/operations', label: 'Operations', icon: ShieldCheck, roles: ['ADMIN'] },
  { to: '/settings', label: 'Settings', icon: Settings, roles: ['CUSTOMER', 'MERCHANT', 'ADMIN'] },
];

export function navItemsFor(role: Role | undefined) {
  return NAV_ITEMS.filter((i) => role && i.roles.includes(role));
}

export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={cn('flex items-center gap-2.5', className)}>
      <svg viewBox="0 0 32 32" className="size-7" aria-hidden>
        <rect width="32" height="32" rx="8" fill="#172033" />
        <path d="M9 9h4v10h10v4H9z" fill="#fff" />
        <path d="M17 9h6v6h-6z" fill="#2563EB" />
      </svg>
      <span className="text-[15px] font-semibold tracking-tight text-ink">LedgerForge</span>
    </span>
  );
}

function SidebarNav({ onNavigate }: { onNavigate?: () => void }) {
  const { user } = useAuth();
  return (
    <nav aria-label="Main" className="space-y-0.5">
      {navItemsFor(user?.role).map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.to === '/'}
          onClick={onNavigate}
          className={({ isActive }) =>
            cn(
              'group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
              isActive ? 'bg-primary-soft text-primary' : 'text-ink-soft hover:bg-subtle hover:text-ink',
            )
          }
        >
          <item.icon className="size-[18px] shrink-0" aria-hidden />
          {item.label}
        </NavLink>
      ))}
    </nav>
  );
}

function SandboxNotice() {
  const meta = useQuery({ queryKey: ['meta'], queryFn: metaService.get, staleTime: Infinity });
  if (!meta.data) return null;
  return (
    <div className="rounded-xl border border-line bg-subtle/70 p-3">
      <div className="flex items-center gap-2">
        <span className={cn('size-2 rounded-full', meta.data.sandboxEnabled ? 'bg-amber-500' : 'bg-emerald-500')} />
        <p className="text-xs font-medium text-ink-soft">{meta.data.sandboxEnabled ? 'Sandbox mode' : 'Live mode'}</p>
      </div>
      <p className="mt-1 text-[11px] leading-relaxed text-muted">
        Simulated balances only. No real money, cards or bank accounts are used.
      </p>
    </div>
  );
}

function NotificationsMenu() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const notifications = useQuery({
    queryKey: ['notifications'],
    queryFn: notificationService.list,
    refetchInterval: 30_000,
  });
  const markRead = useMutation({
    mutationFn: notificationService.markRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });
  const unread = notifications.data?.unreadCount ?? 0;
  return (
    <DropdownMenu
      onOpenChange={(open) => {
        if (!open && unread > 0) markRead.mutate();
      }}
    >
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={unread ? `Notifications, ${unread} unread` : 'Notifications'} className="relative">
          <Bell />
          {unread > 0 && (
            <span className="absolute right-1.5 top-1.5 grid min-w-4 place-items-center rounded-full bg-primary px-1 text-[10px] font-semibold leading-4 text-white">
              {unread > 9 ? '9+' : unread}
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80 p-0">
        <div className="flex items-center justify-between border-b border-line px-4 py-3">
          <p className="text-sm font-semibold">Notifications</p>
          {unread > 0 && <Badge tone="info">{unread} new</Badge>}
        </div>
        <div className="max-h-96 overflow-y-auto p-1.5">
          {notifications.data?.items.length ? (
            notifications.data.items.map((n) => {
              const [currency, amount, reference] = n.body.split(':');
              const text =
                n.kind === 'WEBHOOK_DEAD'
                  ? `Event ${n.body} could not be delivered`
                  : `${formatMoney(Number(amount), currency ?? 'INR')} · ${reference}`;
              return (
                <DropdownMenuItem key={n.id + n.kind} onSelect={() => navigate(n.link)} className="items-start">
                  <span className={cn('mt-1.5 size-2 shrink-0 rounded-full', n.unread ? 'bg-primary' : 'bg-transparent')} />
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-medium text-ink">{n.title}</span>
                    <span className="block truncate text-xs text-muted">{text}</span>
                  </span>
                  <span className="shrink-0 text-[11px] text-faint">{relativeTime(n.createdAt)}</span>
                </DropdownMenuItem>
              );
            })
          ) : (
            <p className="px-3 py-8 text-center text-[13px] text-muted">You're all caught up.</p>
          )}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function UserMenu() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  if (!user) return null;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          className="flex items-center gap-2.5 rounded-lg py-1 pl-1 pr-2 text-left hover:bg-subtle"
          aria-label="Account menu"
        >
          <span className="grid size-8 place-items-center rounded-full bg-ink text-xs font-semibold text-white">
            {initials(user.fullName)}
          </span>
          <span className="hidden min-w-0 md:block">
            <span className="block max-w-40 truncate text-[13px] font-medium text-ink">
              {user.merchant?.businessName ?? user.fullName}
            </span>
            <span className="block text-[11px] capitalize text-muted">{user.role.toLowerCase()}</span>
          </span>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>
          <p className="truncate text-sm font-medium text-ink">{user.fullName}</p>
          <p className="truncate text-xs text-muted">{user.email}</p>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => navigate('/settings')}>
          <Settings /> Settings
        </DropdownMenuItem>
        <DropdownMenuItem
          onSelect={async () => {
            await logout();
            navigate('/login');
          }}
        >
          <LogOut /> Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export function AppLayout() {
  const [mobileOpen, setMobileOpen] = React.useState(false);
  const location = useLocation();
  React.useEffect(() => setMobileOpen(false), [location.pathname]);

  return (
    <div className="min-h-dvh">
      <a
        href="#main"
        className="sr-only z-50 rounded-md bg-surface px-3 py-2 text-sm focus:not-sr-only focus:fixed focus:left-3 focus:top-3"
      >
        Skip to content
      </a>

      {/* Desktop sidebar */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-60 flex-col border-r border-line bg-surface lg:flex">
        <div className="flex h-16 items-center px-5">
          <Link to="/" aria-label="LedgerForge home">
            <Wordmark />
          </Link>
        </div>
        <div className="flex-1 overflow-y-auto px-3 py-2">
          <SidebarNav />
        </div>
        <div className="p-3">
          <SandboxNotice />
        </div>
      </aside>

      {/* Mobile drawer */}
      {mobileOpen && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Navigation">
          <div className="absolute inset-0 bg-ink/40" onClick={() => setMobileOpen(false)} />
          <div className="absolute inset-y-0 left-0 flex w-72 flex-col bg-surface shadow-pop">
            <div className="flex h-16 items-center justify-between px-5">
              <Wordmark />
              <Button variant="ghost" size="icon" aria-label="Close navigation" onClick={() => setMobileOpen(false)}>
                <X />
              </Button>
            </div>
            <div className="flex-1 overflow-y-auto px-3 py-2">
              <SidebarNav onNavigate={() => setMobileOpen(false)} />
            </div>
            <div className="p-3">
              <SandboxNotice />
            </div>
          </div>
        </div>
      )}

      <div className="lg:pl-60">
        <header className="sticky top-0 z-20 flex h-16 items-center gap-3 border-b border-line bg-surface/90 px-4 backdrop-blur-sm sm:px-6">
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            aria-label="Open navigation"
            onClick={() => setMobileOpen(true)}
          >
            <Menu />
          </Button>
          <Link to="/" className="lg:hidden" aria-label="LedgerForge home">
            <Wordmark />
          </Link>
          <div className="ml-auto flex items-center gap-1.5">
            <NotificationsMenu />
            <span className="mx-1 hidden h-6 w-px bg-line sm:block" aria-hidden />
            <UserMenu />
          </div>
        </header>
        <main id="main" className="mx-auto w-full max-w-[1240px] px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
