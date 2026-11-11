import { expect, test, type Page } from '@playwright/test';

const PASSWORD = 'E2e-Strong-Pass-42';
const unique = () => Math.random().toString(36).slice(2, 10);

async function register(page: Page, opts: { name: string; email: string; business?: string }) {
  await page.goto('/register');
  if (opts.business) await page.getByRole('radio', { name: /Business/ }).click();
  await page.getByLabel('Full name').fill(opts.name);
  if (opts.business) await page.getByLabel('Business name').fill(opts.business);
  await page.getByLabel('Email').fill(opts.email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirm password').fill(PASSWORD);
  await page.getByRole('checkbox').click();
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Good');
}

test('customer funds wallet, pays a merchant, inspects the ledger; merchant refunds', async ({ browser }) => {
  const id = unique();
  const business = `E2E Store ${id}`;

  const merchantCtx = await browser.newContext();
  const merchant = await merchantCtx.newPage();
  await register(merchant, { name: 'Merchant Owner', email: `m-${id}@ledgerforge.test`, business });

  const customerCtx = await browser.newContext();
  const customer = await customerCtx.newPage();
  await register(customer, { name: 'Erin Tester', email: `c-${id}@ledgerforge.test` });

  // Sandbox top-up (two-step, idempotent)
  await customer.getByRole('link', { name: 'Add funds' }).first().click();
  const dialog = customer.getByRole('dialog');
  await dialog.getByLabel('Amount').fill('1500');
  await dialog.getByRole('button', { name: 'Review' }).click();
  await dialog.getByRole('button', { name: 'Confirm top-up' }).click();
  await expect(dialog.getByText('₹1,500.00').first()).toBeVisible();
  await dialog.getByRole('button', { name: 'Done' }).click();
  await expect(customer.getByText('₹1,500.00').first()).toBeVisible();

  // Pay the merchant from the directory
  await customer.goto('/merchants');
  await customer.getByLabel('Search merchants').fill(id);
  const payButtons = customer.getByRole('button', { name: 'Pay', exact: true });
  await expect(payButtons).toHaveCount(1); // debounced search has filtered to our merchant
  await payButtons.click();
  const pay = customer.getByRole('dialog');
  await pay.getByLabel('Amount').fill('1000');
  await pay.getByLabel('Description (optional)').fill('E2E order');
  await pay.getByRole('button', { name: 'Continue' }).click();
  await pay.getByRole('button', { name: 'Confirm payment' }).click();
  await expect(pay.getByText('Payment successful')).toBeVisible();
  await pay.getByRole('link', { name: 'View payment' }).click();
  await expect(customer.getByText('Succeeded')).toBeVisible();

  // Ledger inspector: balanced three-way posting with the 2% fee
  await customer.getByRole('link', { name: /Ledger entries/ }).click();
  await expect(customer.getByText('Balanced')).toBeVisible();
  await expect(customer.getByRole('cell', { name: '₹980.00' })).toBeVisible();
  await expect(customer.getByRole('cell', { name: '₹20.00' })).toBeVisible();

  // Merchant sees the payment and issues a partial refund
  await merchant.goto('/payments');
  await merchant.getByText('E2E order').click();
  await merchant.getByRole('button', { name: 'Refund' }).click();
  const refund = merchant.getByRole('dialog');
  await refund.getByRole('radio', { name: 'Partial amount' }).click();
  await refund.getByLabel('Refund amount').fill('250');
  await refund.getByRole('button', { name: 'Issue refund' }).click();
  await expect(merchant.getByText('Partially refunded').first()).toBeVisible();

  // Customer balance reflects the refund: 1500 - 1000 + 250
  await customer.goto('/wallet');
  await expect(customer.getByText('₹750.00').first()).toBeVisible();

  // Role isolation in the UI and API
  await customer.goto('/developers');
  await expect(customer).toHaveURL(/\/$/);
  const status = await customer.evaluate(async () => (await fetch('/api/v1/merchant/dashboard')).status);
  expect(status).toBe(401); // no bearer token outside the app's memory

  await merchantCtx.close();
  await customerCtx.close();
});

test('login rejects bad credentials with a generic message', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email').fill(`nobody-${unique()}@ledgerforge.test`);
  await page.getByLabel('Password', { exact: true }).fill('Wrong-pass-123');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toContainText('Email or password is incorrect');
});
