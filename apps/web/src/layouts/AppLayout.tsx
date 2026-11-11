import { useIsFetching, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, ChevronsUpDown, Command, LogOut, Menu, MoreHorizontal, Search, Settings, X } from 'lucide-react';
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
import { CommandPalette } from '@/features/CommandPalette';
import { relativeTime } from '@/lib/format';
import { formatMoney } from '@/lib/money';
import { cn, initials } from '@/lib/utils';
import { metaService, notificationService } from '@/services';
import { Wordmark } from './Wordmark';
import { navItemsFor, titleFor } from './nav';

function SidebarNav({ onNavigate }: { onNavigate?: () => void }) {
  const { user } = useAuth();
  const items = navItemsFor(user?.role);
  const sections = [...new Set(items.map((i) => i.section))];
  return (
    <nav aria-label="Main" className="space-y-5">
      {sections.map((section) => (
        <div key={section}>
          {section !== 'Main' && (
            <p className="mb-1.5 px-3 text-[11px] font-semibold uppercase tracking-[0.08em] text-faint">{section}</p>
          )}
          <div className="space-y-0.5">
            {items
              .filter((i) => i.section === section)
              .map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/'}
                  onClick={onNavigate}
                  className={({ isActive }) =>
                    cn(
                      'group relative flex items-center gap-3 rounded-lg px-3 py-2 text-[13.5px] font-medium transition-colors',
                      isActive ? 'bg-primary-soft text-primary' : 'text-ink-soft hover:bg-subtle hover:text-ink',
                    )
                  }
                >
                  {({ isActive }) => (
                    <>
                      {isActive && <span className="absolute inset-y-1.5 -left-3 w-[3px] rounded-r-full bg-primary" aria-hidden />}
                      <item.icon className={cn('size-[18px] shrink-0', isActive ? 'text-primary' : 'text-muted group-hover:text-ink-soft')} aria-hidden />
                      {item.label}
                    </>
                  )}
                </NavLink>
              ))}
          </div>
        </div>
      ))}
    </nav>
  );
}

function WorkspaceCard() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  if (!user) return null;
  const title = user.merchant?.businessName ?? user.fullName;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button className="flex w-full items-center gap-3 rounded-xl border border-line bg-surface p-2.5 text-left shadow-[0_1px_0_rgb(23_32_51/0.03)] transition-colors hover:bg-subtle">
          <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-gradient-to-br from-[#1e2a44] to-ink text-xs font-semibold text-white">
            {initials(title)}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-[13px] font-semibold text-ink">{title}</span>
            <span className="block truncate text-[11px] capitalize text-muted">{user.role.toLowerCase()} · {user.defaultCurrency}</span>
          </span>
          <ChevronsUpDown className="size-4 text-faint" aria-hidden />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-60">
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

function SandboxNotice() {
  const meta = useQuery({ queryKey: ['meta'], queryFn: metaService.get, staleTime: Infinity });
  if (!meta.data) return null;
  return (
    <div className="rounded-xl border border-amber-200/70 bg-gradient-to-b from-amber-50 to-surface p-3">
      <div className="flex items-center gap-2">
        <span className="relative flex size-2">
          <span className="absolute inline-flex size-full animate-ping rounded-full bg-amber-400 opacity-60" />
          <span className="relative inline-flex size-2 rounded-full bg-amber-500" />
        </span>
        <p className="text-xs font-semibold text-amber-900">{meta.data.sandboxEnabled ? 'Sandbox mode' : 'Live mode'}</p>
      </div>
      <p className="mt-1 text-[11px] leading-relaxed text-amber-900/70">Simulated balances. No real money, cards or bank accounts.</p>
    </div>
  );
}

function NotificationsMenu() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const notifications = useQuery({ queryKey: ['notifications'], queryFn: notificationService.list, refetchInterval: 30_000 });
  const markRead = useMutation({
    mutationFn: notificationService.markRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });
  const unread = notifications.data?.unreadCount ?? 0;
  return (
    <DropdownMenu onOpenChange={(open) => !open && unread > 0 && markRead.mutate()}>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={unread ? `Notifications, ${unread} unread` : 'Notifications'} className="relative">
          <Bell />
          {unread > 0 && (
            <span className="absolute right-1.5 top-1.5 grid min-w-4 place-items-center rounded-full bg-primary px-1 text-[10px] font-semibold leading-4 text-white ring-2 ring-surface">
              {unread > 9 ? '9+' : unread}
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-[340px] p-0">
        <div className="flex items-center justify-between border-b border-line px-4 py-3">
          <p className="text-sm font-semibold">Notifications</p>
          {unread > 0 && <Badge tone="info">{unread} new</Badge>}
        </div>
        <div className="scrollbar-thin max-h-96 overflow-y-auto p-1.5">
          {notifications.data?.items.length ? (
            notifications.data.items.map((n) => {
              const [currency, amount, reference] = n.body.split(':');
              const text =
                n.kind === 'WEBHOOK_DEAD'
                  ? `Event ${n.body} could not be delivered`
                  : `${formatMoney(Number(amount), currency ?? 'INR')} · ${reference}`;
              return (
                <DropdownMenuItem key={n.id + n.kind} onSelect={() => navigate(n.link)} className="items-start">
                  <span className={cn('mt-1.5 size-2 shrink-0 rounded-full', n.unread ? 'bg-primary' : 'bg-line-strong')} />
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-medium text-ink">{n.title}</span>
                    <span className="block truncate text-xs text-muted">{text}</span>
                  </span>
                  <span className="shrink-0 text-[11px] text-faint">{relativeTime(n.createdAt)}</span>
                </DropdownMenuItem>
              );
            })
          ) : (
            <div className="px-3 py-10 text-center">
              <Bell className="mx-auto size-5 text-faint" aria-hidden />
              <p className="mt-2 text-[13px] text-muted">You're all caught up.</p>
            </div>
          )}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Thin top progress bar while any query is fetching (except background polling). */
function FetchProgress() {
  const fetching = useIsFetching({ predicate: (q) => q.state.data === undefined });
  return (
    <div className={cn('pointer-events-none absolute inset-x-0 bottom-0 h-0.5 overflow-hidden transition-opacity', fetching ? 'opacity-100' : 'opacity-0')} aria-hidden>
      <div className="h-full w-1/3 bg-primary" style={{ animation: 'lf-progress 1s ease-in-out infinite' }} />
    </div>
  );
}

function MobileTabBar({ onMore }: { onMore: () => void }) {
  const { user } = useAuth();
  const items = navItemsFor(user?.role).filter((i) => i.to !== '/settings').slice(0, 4);
  return (
    <nav aria-label="Primary" className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-surface/95 pb-[env(safe-area-inset-bottom)] backdrop-blur lg:hidden">
      <div className="grid grid-cols-5">
        {items.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              cn('flex flex-col items-center gap-1 py-2.5 text-[10.5px] font-medium', isActive ? 'text-primary' : 'text-muted')
            }
          >
            <item.icon className="size-5" aria-hidden />
            {item.label}
          </NavLink>
        ))}
        <button onClick={onMore} className="flex flex-col items-center gap-1 py-2.5 text-[10.5px] font-medium text-muted">
          <MoreHorizontal className="size-5" aria-hidden />
          More
        </button>
      </div>
    </nav>
  );
}

export function AppLayout() {
  const [mobileOpen, setMobileOpen] = React.useState(false);
  const [paletteOpen, setPaletteOpen] = React.useState(false);
  const location = useLocation();
  React.useEffect(() => setMobileOpen(false), [location.pathname]);

  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((o) => !o);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);
  const title = titleFor(location.pathname);

  return (
    <div className="min-h-dvh">
      <a href="#main" className="sr-only z-50 rounded-md bg-surface px-3 py-2 text-sm focus:not-sr-only focus:fixed focus:left-3 focus:top-3">
        Skip to content
      </a>

      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r border-line bg-surface lg:flex">
        <div className="flex h-16 items-center px-5">
          <Link to="/" aria-label="LedgerForge home">
            <Wordmark />
          </Link>
        </div>
        <div className="px-3 pb-4">
          <WorkspaceCard />
        </div>
        <div className="scrollbar-thin flex-1 overflow-y-auto px-3 pb-4">
          <SidebarNav />
        </div>
        <div className="p-3">
          <SandboxNotice />
        </div>
      </aside>

      {mobileOpen && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Navigation">
          <div className="animate-fade absolute inset-0 bg-ink/40" onClick={() => setMobileOpen(false)} />
          <div className="animate-page absolute inset-y-0 left-0 flex w-72 flex-col bg-surface shadow-pop">
            <div className="flex h-16 items-center justify-between px-5">
              <Wordmark />
              <Button variant="ghost" size="icon" aria-label="Close navigation" onClick={() => setMobileOpen(false)}>
                <X />
              </Button>
            </div>
            <div className="px-3 pb-4">
              <WorkspaceCard />
            </div>
            <div className="flex-1 overflow-y-auto px-3">
              <SidebarNav onNavigate={() => setMobileOpen(false)} />
            </div>
            <div className="p-3">
              <SandboxNotice />
            </div>
          </div>
        </div>
      )}

      <div className="lg:pl-64">
        <header className="sticky top-0 z-20 border-b border-line bg-surface/85 backdrop-blur-md">
          <div className="relative flex h-16 items-center gap-3 px-4 sm:px-6 lg:px-8">
            <Button variant="ghost" size="icon" className="lg:hidden" aria-label="Open navigation" onClick={() => setMobileOpen(true)}>
              <Menu />
            </Button>
            <p className="text-[15px] font-semibold text-ink lg:hidden">{title}</p>
            <button
              onClick={() => setPaletteOpen(true)}
              className="group hidden h-9 w-full max-w-md items-center gap-2.5 rounded-lg border border-line bg-subtle/70 px-3 text-left text-[13px] text-muted transition-colors hover:border-line-strong hover:bg-surface md:flex"
              aria-label="Open command palette"
            >
              <Search className="size-4 text-faint" aria-hidden />
              <span className="flex-1">Search transactions, pages, actions…</span>
              <kbd className="flex items-center gap-0.5 rounded border border-line bg-surface px-1.5 py-0.5 font-mono text-[10.5px] text-muted">
                {isMac ? <Command className="size-3" /> : 'Ctrl'} K
              </kbd>
            </button>
            <div className="ml-auto flex items-center gap-1">
              <Button variant="ghost" size="icon" className="md:hidden" aria-label="Search" onClick={() => setPaletteOpen(true)}>
                <Search />
              </Button>
              <span className="mr-1 hidden items-center gap-1.5 rounded-full border border-amber-200 bg-warning-soft px-2.5 py-1 text-[11px] font-semibold text-warning sm:flex">
                <span className="size-1.5 rounded-full bg-amber-500" /> Sandbox
              </span>
              <NotificationsMenu />
            </div>
            <FetchProgress />
          </div>
        </header>
        <main id="main" key={location.pathname} className="animate-page mx-auto w-full max-w-[1280px] px-4 pb-28 pt-6 sm:px-6 lg:px-8 lg:pb-10 lg:pt-8">
          <Outlet />
        </main>
      </div>

      <MobileTabBar onMore={() => setMobileOpen(true)} />
      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
    </div>
  );
}

