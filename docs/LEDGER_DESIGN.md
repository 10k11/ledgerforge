# Ledger Design

The ledger is the accounting source of truth. Every movement of (simulated) money is a
**ledger transaction** made of two or more **entries** (postings). Balances shown in the UI
are a *projection* of those entries.

Code: `apps/api/src/main/java/dev/ledgerforge/ledger/`

## 1. Debits, credits and normal balances

Every account has a type, and every type has a *normal balance*: the side on which increases
are recorded.

| Account type | Normal side | A **debit**… | A **credit**… | Examples in LedgerForge |
|---|---|---|---|---|
| ASSET     | Debit  | increases | decreases | `SYS-FUNDING-INR` (platform settlement bank position) |
| EXPENSE   | Debit  | increases | decreases | *(none yet)* |
| LIABILITY | Credit | decreases | increases | Customer wallets, merchant payables |
| EQUITY    | Credit | decreases | increases | *(none yet)* |
| REVENUE   | Credit | decreases | increases | `SYS-FEEREV-INR` (platform fee revenue) |

A customer wallet is a **liability**: the money in it is owed *by the platform to the customer*.
That is why a top-up *credits* the wallet.

The rule is encoded once, in `AccountType`:

```java
public long signedDelta(EntryType entryType, long amountMinor) {
    return entryType == normalBalance ? amountMinor : -amountMinor;
}
```

The database also pins it down. `ck_ledger_accounts_normal_matches_type` makes it impossible to
store, say, a debit-normal liability.

## 2. Chart of accounts

| Purpose | Type | Owner | Created by |
|---|---|---|---|
| `CUSTOMER_WALLET`  | LIABILITY | customer | registration (`AccountProvisioningService`) |
| `MERCHANT_PAYABLE` | LIABILITY | merchant | merchant registration |
| `FUNDING_CLEARING` | ASSET     | system   | migration `V2__system_accounts.sql` (INR, USD) |
| `FEE_REVENUE`      | REVENUE   | system   | migration `V2__system_accounts.sql` (INR, USD) |

Each account has a single currency. The system has one funding and one revenue account per currency.

## 3. Postings for every operation

All amounts below are for a ₹1,000.00 operation with the illustrative 2% fee.

**Sandbox top-up** (`WalletService.topUp`)

| Account | Debit | Credit |
|---|---:|---:|
| Platform Funding Clearing (asset) | 1,000.00 | |
| Customer Wallet (liability) | | 1,000.00 |

**Transfer A → B** (`TransferService.create`)

| Account | Debit | Credit |
|---|---:|---:|
| Wallet A | 1,000.00 | |
| Wallet B | | 1,000.00 |

**Merchant payment capture** (`PaymentService.confirm`)

| Account | Debit | Credit |
|---|---:|---:|
| Customer Wallet | 1,000.00 | |
| Merchant Payable | | 980.00 |
| Platform Fee Revenue | | 20.00 |

**Full refund** (`RefundService.create`, type `REFUND`, `reverses_transaction_id` = the payment)

| Account | Debit | Credit |
|---|---:|---:|
| Merchant Payable | 980.00 | |
| Platform Fee Revenue | 20.00 | |
| Customer Wallet | | 1,000.00 |

**Merchant payout** (`MerchantService.payout`): cash leaves the platform.

| Account | Debit | Credit |
|---|---:|---:|
| Merchant Payable | 980.00 | |
| Platform Funding Clearing | | 980.00 |

**Correction** (`AdminService.reverse`, type `REVERSAL`): the exact mirror of a top-up or transfer.

Across all of these, the accounting equation **Assets = Liabilities + Equity + Revenue − Expenses**
holds after every transaction. `ReconciliationService` verifies it for each currency.

## 4. Partial refunds and fee rounding

The fee is computed once at intent creation and snapshotted (`fee_bps`, `platform_fee_minor`),
rounded **half-up** to the minor unit with exact integer arithmetic (`MoneyMath.feeForAmount`).

Refunds use **cumulative rounding**:

```java
long refundedAfter = Math.addExact(alreadyRefundedMinor, refundAmountMinor);
long cumulativeFee = MoneyMath.proportionalFloor(refundedAfter, paymentFeeMinor, paymentAmountMinor);
long feeReversal = cumulativeFee - alreadyReversedFeeMinor;
return new RefundSplit(feeReversal, refundAmountMinor - feeReversal);
```

Why not round each refund independently? Consider a ₹1.00 payment with a ₹0.02 fee, refunded one
paisa at a time. Flooring each refund's fee gives 0 ninety-nine times. The final paisa would then
need a fee reversal of 2, which is larger than the refund itself, so the merchant debit goes negative.
With cumulative rounding:

* the total fee reversed after a full refund is **exactly** the original fee;
* the total merchant debit is **exactly** the original net;
* no single refund has a negative component, because `floor(x + y) − floor(x) ≤ ceil(y) ≤ refund`
  whenever `fee ≤ amount`.

`FeeCalculatorTest.randomRefundSequencesReconcileExactly` checks these properties over 200 random
amount, fee, and refund-sequence combinations on every build.

## 5. Balance projection

`account_balances.balance_minor` holds each account's balance in its normal direction. It exists
so that "is there enough money?" is an O(1) check under a row lock, instead of a `SUM` over history.

It is **never an independent source of truth**:

* Only `LedgerService.post` changes it, in the same database transaction that inserts the entries.
* `ReconciliationService` recomputes every balance from entries and reports any mismatch
  (`LedgerEngineIT.reconciliationDetectsTamperedProjection` tampers with one to prove detection works).
* `CHECK (allow_negative OR balance_minor >= 0)` is a last line of defence against overdrafts.

## 6. Invariants and where they are enforced

| Invariant | Application (`LedgerValidationService`) | Database |
|---|---|---|
| ≥ 2 postings | `MIN_TWO_POSTINGS` | deferred constraint trigger `trg_ledger_*_balanced` |
| Debits = credits | `BALANCED` | deferred constraint trigger (checked at COMMIT) |
| Amounts > 0 | `POSITIVE_AMOUNTS` | `ck_ledger_entries_amount` |
| No currency mismatch | `validateAccounts` → `CURRENCY_MISMATCH` | trigger `trg_ledger_entries_currency` |
| Posted entries immutable | `@Immutable` entities, no update code paths | triggers reject `UPDATE`/`DELETE`/`TRUNCATE` |
| Reversal references original | `REVERSAL_REFERENCE` | `ck_ledger_tx_reversal` |
| Reversal ≤ unreversed original, per account | `REVERSAL_NOT_EXCEEDED` (original row locked) | — |
| Reversal mirrors original's accounts and sides | `REVERSAL_MIRRORS_ORIGINAL` | — |
| No overdraft of non-negative accounts | `validateResultingBalance` | `ck_account_balances_non_negative` |
| No duplicate postings from retries | idempotency framework (see `IDEMPOTENCY.md`) | unique idempotency scope |
| No partial postings | `@Transactional(propagation = MANDATORY)` | single DB transaction |

The deferred trigger means that even a developer writing raw SQL cannot commit an unbalanced
transaction. `LedgerEngineIT.databaseRejectsUnbalancedTransactionAtCommitEvenIfApplicationIsBypassed`
proves this.

## 7. Corrections

History is append-only. To correct a top-up or transfer, an administrator posts a `REVERSAL`
transaction whose entries mirror the original (`POST /api/v1/admin/ledger/transactions/{id}/reversal`).
The reversal invariants guarantee it can be applied at most once in full. Payments are corrected
through refunds, which carry the fee logic.
