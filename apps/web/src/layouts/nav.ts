import {
  ArrowLeftRight,
  Code2,
  LayoutDashboard,
  ListOrdered,
  Receipt,
  Settings,
  ShieldCheck,
  Store,
  Wallet,
  type LucideIcon,
} from 'lucide-react';
import type { Role } from '@/types/api';

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  roles: Role[];
  section: 'Main' | 'Money' | 'Business' | 'Platform';
}

/** Navigation adapts to role; every route is still authorised server-side. */
export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, roles: ['CUSTOMER', 'MERCHANT', 'ADMIN'], section: 'Main' },
  { to: '/wallet', label: 'Wallet', icon: Wallet, roles: ['CUSTOMER'], section: 'Money' },
  { to: '/transfers', label: 'Transfers', icon: ArrowLeftRight, roles: ['CUSTOMER'], section: 'Money' },
  { to: '/payments', label: 'Payments', icon: Receipt, roles: ['CUSTOMER', 'MERCHANT', 'ADMIN'], section: 'Money' },
  { to: '/transactions', label: 'Transactions', icon: ListOrdered, roles: ['CUSTOMER', 'MERCHANT'], section: 'Money' },
  { to: '/merchants', label: 'Merchants', icon: Store, roles: ['CUSTOMER', 'MERCHANT'], section: 'Business' },
  { to: '/developers', label: 'Developers', icon: Code2, roles: ['MERCHANT'], section: 'Business' },
  { to: '/operations', label: 'Operations', icon: ShieldCheck, roles: ['ADMIN'], section: 'Platform' },
  { to: '/settings', label: 'Settings', icon: Settings, roles: ['CUSTOMER', 'MERCHANT', 'ADMIN'], section: 'Platform' },
];

export function navItemsFor(role: Role | undefined): NavItem[] {
  return NAV_ITEMS.filter((i) => role && i.roles.includes(role));
}

/** Title shown in the top bar for a pathname. */
export function titleFor(pathname: string): string {
  if (pathname.startsWith('/transactions/')) return 'Transaction';
  if (pathname.startsWith('/payments/')) return 'Payment';
  const item = NAV_ITEMS.find((i) => i.to !== '/' && pathname.startsWith(i.to));
  return item?.label ?? 'Overview';
}
