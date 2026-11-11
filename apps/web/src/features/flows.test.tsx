import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { JsonViewer } from '@/components/shared';
import { TransactionsTable } from '@/components/shared/finance';
import { navItemsFor } from '@/layouts/AppLayout';
import { ApiError } from '@/lib/api';
import { SendMoneyCard } from '@/pages/TransfersPage';
import { TopUpDialog } from '@/pages/WalletPage';
import { transferService, walletService } from '@/services';
import { renderWithProviders } from '@/test/utils';
import type { WalletView } from '@/types/api';

vi.mock('@/services', async (orig) => {
  const actual = await orig<typeof import('@/services')>();
  return {
    ...actual,
    walletService: { get: vi.fn(), topUp: vi.fn(), statement: vi.fn() },
    transferService: { lookup: vi.fn(), create: vi.fn(), list: vi.fn(), get: vi.fn() },
  };
});

const wallet: WalletView = {
  accountId: 'a',
  accountNumber: 'LF123456789012',
  accountName: 'Wallet · Asha',
  currency: 'INR',
  balanceMinor: 50_000,
  status: 'ACTIVE',
  sandboxEnabled: true,
  topUpLimits: { maxPerTopUpMinor: 10_000_000, dailyLimitMinor: 50_000_000, usedTodayMinor: 0 },
  createdAt: '2026-01-01T00:00:00Z',
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(walletService.get).mockResolvedValue(wallet);
});

describe('role-based navigation', () => {
  it('shows each role only its sections', () => {
    const labels = (r: Parameters<typeof navItemsFor>[0]) => navItemsFor(r).map((i) => i.label);
    expect(labels('CUSTOMER')).toEqual(['Overview', 'Wallet', 'Transfers', 'Payments', 'Transactions', 'Merchants', 'Settings']);
    expect(labels('MERCHANT')).toContain('Developers');
    expect(labels('MERCHANT')).not.toContain('Wallet');
    expect(labels('ADMIN')).toEqual(['Overview', 'Payments', 'Operations', 'Settings']);
    expect(labels(undefined)).toEqual([]);
  });
});

describe('SendMoneyCard', () => {
  it('reviews, sends, and reuses the idempotency key when retrying after a failure', async () => {
    vi.mocked(transferService.lookup).mockResolvedValue({
      displayName: 'Ravi K.',
      maskedEmail: 'ra•••@example.com',
      accountNumberMasked: '••••9012',
      currency: 'INR',
    });
    vi.mocked(transferService.create)
      .mockRejectedValueOnce(new ApiError(0, null, 'Network error'))
      .mockResolvedValueOnce({
        replayed: false,
        data: {
          id: 't1',
          reference: 'TRF_ABC',
          direction: 'SENT',
          counterpartyName: 'Ravi K.',
          amountMinor: 20_000,
          currency: 'INR',
          status: 'COMPLETED',
          ledgerTransactionId: 'l1',
          senderBalanceAfterMinor: 30_000,
          createdAt: '2026-01-01T00:00:00Z',
        },
      });
    renderWithProviders(<SendMoneyCard />);
    await screen.findByText(/Available ₹500.00/);
    await userEvent.type(screen.getByLabelText('Recipient'), 'ravi@example.com');
    await userEvent.type(screen.getByLabelText('Amount'), '200');
    await userEvent.click(screen.getByRole('button', { name: /Review transfer/ }));
    expect(await screen.findByText('Ravi K.')).toBeInTheDocument();
    expect(screen.getByText('₹200.00')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Send ₹200.00' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Network error');
    await userEvent.click(screen.getByRole('button', { name: 'Send ₹200.00' }));
    expect(await screen.findByText('TRF_ABC')).toBeInTheDocument();

    const calls = vi.mocked(transferService.create).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[0][0]).toEqual({ recipient: 'ravi@example.com', amountMinor: 20_000, currency: 'INR', description: undefined });
    expect(calls[1][1]).toBe(calls[0][1]); // same Idempotency-Key for the retry
  });

  it('blocks amounts above the displayed balance before calling the API', async () => {
    renderWithProviders(<SendMoneyCard />);
    await screen.findByText(/Available ₹500.00/);
    await userEvent.type(screen.getByLabelText('Recipient'), 'ravi@example.com');
    await userEvent.type(screen.getByLabelText('Amount'), '600');
    await userEvent.click(screen.getByRole('button', { name: /Review transfer/ }));
    expect(await screen.findByText(/Exceeds your available balance/)).toBeInTheDocument();
    expect(transferService.lookup).not.toHaveBeenCalled();
  });
});

describe('TopUpDialog', () => {
  it('enforces the per-top-up limit and confirms with an idempotency key', async () => {
    vi.mocked(walletService.topUp).mockResolvedValue({
      replayed: false,
      data: { transactionId: 'x', reference: 'LTX_1', amountMinor: 150_000, currency: 'INR', balanceAfterMinor: 200_000, createdAt: '2026-01-01T00:00:00Z' },
    });
    renderWithProviders(<TopUpDialog wallet={wallet} open onOpenChange={() => {}} />);
    const dialog = screen.getByRole('dialog');
    const amount = within(dialog).getByLabelText('Amount');
    await userEvent.type(amount, '100001');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Review' }));
    expect(await within(dialog).findByText(/Maximum per top-up/)).toBeInTheDocument();
    await userEvent.clear(amount);
    await userEvent.type(amount, '1500');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Review' }));
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Confirm top-up' }));
    await waitFor(() => expect(walletService.topUp).toHaveBeenCalledTimes(1));
    const [minor, currency, key] = vi.mocked(walletService.topUp).mock.calls[0];
    expect(minor).toBe(150_000);
    expect(currency).toBe('INR');
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByRole('heading', { name: 'Funds added' })).toBeInTheDocument();
    expect(screen.getByText('LTX_1')).toBeInTheDocument();
  });
});

describe('TransactionsTable', () => {
  it('renders signed amounts and an empty state', () => {
    const first = renderWithProviders(
      <TransactionsTable
        items={[
          { id: '1', reference: 'LTX_A', type: 'TOP_UP', status: 'POSTED', description: 'Sandbox wallet top-up', counterparty: 'Sandbox funding', currency: 'INR', amountMinor: 100000, direction: 'IN', createdAt: '2026-01-01T00:00:00Z' },
          { id: '2', reference: 'LTX_B', type: 'PAYMENT', status: 'POSTED', description: 'Order', counterparty: 'Chai & Co', currency: 'INR', amountMinor: 2500, direction: 'OUT', createdAt: '2026-01-01T00:00:00Z' },
        ]}
      />,
    );
    expect(screen.getByText('+₹1,000.00')).toBeInTheDocument();
    expect(screen.getByText('−₹25.00')).toBeInTheDocument();
    expect(screen.getByText('Chai & Co')).toBeInTheDocument();
    first.unmount();
    renderWithProviders(<TransactionsTable items={[]} />);
    expect(screen.getByText('No transactions yet')).toBeInTheDocument();
  });
});

describe('JsonViewer', () => {
  it('renders payload text without interpreting markup', () => {
    const { container } = renderWithProviders(<JsonViewer value={{ note: '<img src=x onerror=alert(1)>' }} />);
    expect(container.querySelector('img')).toBeNull();
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>');
  });
});
