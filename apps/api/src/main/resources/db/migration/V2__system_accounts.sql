-- System chart of accounts. These exist in every environment (including
-- production); they carry no simulated money until a posting touches them.
--
-- FUNDING_CLEARING (ASSET, debit-normal): the platform's (simulated) settlement
--   bank position. Debited when money enters (top-ups), credited when it leaves
--   (merchant payouts).
-- FEE_REVENUE (REVENUE, credit-normal): platform fees earned on merchant payments.
INSERT INTO ledger_accounts (id, account_number, owner_id, account_name, account_type, account_purpose,
                             normal_balance, currency, allow_negative, status, created_at)
VALUES ('00000000-0000-7000-8000-000000000101', 'SYS-FUNDING-INR', NULL, 'Platform Funding Clearing (INR)',
        'ASSET', 'FUNDING_CLEARING', 'DEBIT', 'INR', TRUE, 'ACTIVE', now()),
       ('00000000-0000-7000-8000-000000000102', 'SYS-FEEREV-INR', NULL, 'Platform Fee Revenue (INR)',
        'REVENUE', 'FEE_REVENUE', 'CREDIT', 'INR', FALSE, 'ACTIVE', now()),
       ('00000000-0000-7000-8000-000000000201', 'SYS-FUNDING-USD', NULL, 'Platform Funding Clearing (USD)',
        'ASSET', 'FUNDING_CLEARING', 'DEBIT', 'USD', TRUE, 'ACTIVE', now()),
       ('00000000-0000-7000-8000-000000000202', 'SYS-FEEREV-USD', NULL, 'Platform Fee Revenue (USD)',
        'REVENUE', 'FEE_REVENUE', 'CREDIT', 'USD', FALSE, 'ACTIVE', now());

INSERT INTO account_balances (id, ledger_account_id, balance_minor, allow_negative, version, updated_at)
SELECT gen_random_uuid(), id, 0, allow_negative, 0, now() FROM ledger_accounts WHERE owner_id IS NULL;
