import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';
import { TooltipProvider } from '@/components/ui/primitives';
import { StaticAuthProvider, type AuthContextValue } from '@/features/auth/AuthProvider';
import type { Role, UserProfile } from '@/types/api';

export function makeUser(role: Role = 'CUSTOMER', overrides: Partial<UserProfile> = {}): UserProfile {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    email: 'asha@example.com',
    fullName: 'Asha Verma',
    role,
    defaultCurrency: 'INR',
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

export function makeAuth(user: UserProfile | null, overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    status: user ? 'authenticated' : 'anonymous',
    user,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    setUser: vi.fn(),
    hasRole: (...roles) => !!user && roles.includes(user.role),
    ...overrides,
  };
}

export function renderWithProviders(ui: ReactElement, opts: { auth?: AuthContextValue; route?: string } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[opts.route ?? '/']}>
        <StaticAuthProvider value={opts.auth ?? makeAuth(makeUser())}>
          <TooltipProvider>{ui}</TooltipProvider>
        </StaticAuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
