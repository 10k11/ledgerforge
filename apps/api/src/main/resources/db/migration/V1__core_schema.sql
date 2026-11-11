-- =====================================================================
-- LedgerForge core schema
-- All monetary amounts are BIGINT minor units (paise, cents).
-- All timestamps are TIMESTAMPTZ and written in UTC by the application.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                     UUID PRIMARY KEY,
    email                  VARCHAR(254) NOT NULL,
    full_name              VARCHAR(120) NOT NULL,
    password_hash          VARCHAR(100) NOT NULL,
    role                   VARCHAR(16)  NOT NULL,
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    default_currency       VARCHAR(3)      NOT NULL,
    failed_login_attempts  INTEGER      NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    last_login_at          TIMESTAMPTZ,
    notifications_seen_at  TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'MERCHANT', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_currency CHECK (default_currency IN ('INR', 'USD')),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_login_attempts >= 0)
);
CREATE UNIQUE INDEX ux_users_email ON users (email);

CREATE TABLE refresh_sessions (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id),
    family_id       UUID         NOT NULL,
    token_hash      VARCHAR(64)     NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(32),
    replaced_by     UUID REFERENCES refresh_sessions (id),
    user_agent      VARCHAR(255),
    ip_address      VARCHAR(64),
    CONSTRAINT ck_refresh_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_sessions_reason CHECK (
        revoked_reason IS NULL OR revoked_reason IN
            ('ROTATED', 'LOGOUT', 'LOGOUT_ALL', 'REUSE_DETECTED', 'USER_REVOKED', 'PASSWORD_CHANGED'))
);
CREATE UNIQUE INDEX ux_refresh_sessions_token_hash ON refresh_sessions (token_hash);
CREATE INDEX ix_refresh_sessions_user ON refresh_sessions (user_id, revoked_at);
CREATE INDEX ix_refresh_sessions_family ON refresh_sessions (family_id);

CREATE TABLE merchant_profiles (
    id               UUID PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users (id),
    business_name    VARCHAR(120) NOT NULL,
    category         VARCHAR(64)  NOT NULL,
    support_email    VARCHAR(254),
    website          VARCHAR(255),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_merchant_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
CREATE UNIQUE INDEX ux_merchant_profiles_user ON merchant_profiles (user_id);

-- ---------------------------------------------------------------------
-- Ledger (double-entry)
-- ---------------------------------------------------------------------
CREATE TABLE ledger_accounts (
    id               UUID PRIMARY KEY,
    account_number   VARCHAR(32)  NOT NULL,
    owner_id         UUID REFERENCES users (id),
    account_name     VARCHAR(120) NOT NULL,
    account_type     VARCHAR(16)  NOT NULL,
    account_purpose  VARCHAR(32)  NOT NULL,
    normal_balance   VARCHAR(6)   NOT NULL,
    currency         VARCHAR(3)      NOT NULL,
    allow_negative   BOOLEAN      NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_ledger_accounts_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT ck_ledger_accounts_normal CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    -- normal balance is fully determined by the account type
    CONSTRAINT ck_ledger_accounts_normal_matches_type CHECK (
        (account_type IN ('ASSET', 'EXPENSE') AND normal_balance = 'DEBIT') OR
        (account_type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND normal_balance = 'CREDIT')),
    CONSTRAINT ck_ledger_accounts_purpose CHECK (account_purpose IN
        ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'FUNDING_CLEARING', 'FEE_REVENUE')),
    CONSTRAINT ck_ledger_accounts_currency CHECK (currency IN ('INR', 'USD')),
    CONSTRAINT ck_ledger_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    -- user-owned accounts are liabilities (we owe the user); system accounts have no owner
    CONSTRAINT ck_ledger_accounts_owner CHECK (
        (account_purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE') AND owner_id IS NOT NULL AND account_type = 'LIABILITY')
        OR (account_purpose NOT IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE') AND owner_id IS NULL))
);
CREATE UNIQUE INDEX ux_ledger_accounts_number ON ledger_accounts (account_number);
CREATE UNIQUE INDEX ux_ledger_accounts_owner_purpose ON ledger_accounts (owner_id, account_purpose, currency) WHERE owner_id IS NOT NULL;
CREATE UNIQUE INDEX ux_ledger_accounts_system_purpose ON ledger_accounts (account_purpose, currency) WHERE owner_id IS NULL;
CREATE INDEX ix_ledger_accounts_owner ON ledger_accounts (owner_id);

CREATE TABLE ledger_transactions (
    id                      UUID PRIMARY KEY,
    reference_number        VARCHAR(32)  NOT NULL,
    transaction_type        VARCHAR(16)  NOT NULL,
    status                  VARCHAR(16)  NOT NULL,
    description             VARCHAR(255) NOT NULL,
    currency                VARCHAR(3)      NOT NULL,
    idempotency_key         VARCHAR(128),
    related_entity_id       UUID,
    reverses_transaction_id UUID REFERENCES ledger_transactions (id),
    initiated_by            UUID REFERENCES users (id),
    created_at              TIMESTAMPTZ  NOT NULL,
    posted_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_ledger_tx_type CHECK (transaction_type IN ('TOP_UP', 'TRANSFER', 'PAYMENT', 'REFUND', 'PAYOUT', 'REVERSAL')),
    CONSTRAINT ck_ledger_tx_status CHECK (status IN ('POSTED')),
    CONSTRAINT ck_ledger_tx_currency CHECK (currency IN ('INR', 'USD')),
    CONSTRAINT ck_ledger_tx_reversal CHECK (
        (transaction_type IN ('REFUND', 'REVERSAL')) = (reverses_transaction_id IS NOT NULL)),
    CONSTRAINT ck_ledger_tx_not_self_reversal CHECK (reverses_transaction_id IS NULL OR reverses_transaction_id <> id)
);
CREATE UNIQUE INDEX ux_ledger_tx_reference ON ledger_transactions (reference_number);
CREATE INDEX ix_ledger_tx_created ON ledger_transactions (created_at DESC);
CREATE INDEX ix_ledger_tx_related ON ledger_transactions (related_entity_id);
CREATE INDEX ix_ledger_tx_reverses ON ledger_transactions (reverses_transaction_id) WHERE reverses_transaction_id IS NOT NULL;

CREATE TABLE ledger_entries (
    id                     UUID PRIMARY KEY,
    sequence_number        BIGINT GENERATED ALWAYS AS IDENTITY,
    ledger_transaction_id  UUID        NOT NULL REFERENCES ledger_transactions (id),
    ledger_account_id      UUID        NOT NULL REFERENCES ledger_accounts (id),
    entry_type             VARCHAR(6)  NOT NULL,
    amount_minor           BIGINT      NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ledger_entries_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_ledger_entries_currency CHECK (currency IN ('INR', 'USD'))
);
CREATE UNIQUE INDEX ux_ledger_entries_sequence ON ledger_entries (sequence_number);
CREATE INDEX ix_ledger_entries_tx ON ledger_entries (ledger_transaction_id);
CREATE INDEX ix_ledger_entries_account_seq ON ledger_entries (ledger_account_id, sequence_number);

-- Balance projection. Derived from ledger_entries; never an independent source of truth.
-- balance_minor is expressed in the account's normal-balance direction.
CREATE TABLE account_balances (
    id                 UUID PRIMARY KEY,
    ledger_account_id  UUID        NOT NULL REFERENCES ledger_accounts (id),
    balance_minor      BIGINT      NOT NULL DEFAULT 0,
    allow_negative     BOOLEAN     NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_account_balances_non_negative CHECK (allow_negative OR balance_minor >= 0)
);
CREATE UNIQUE INDEX ux_account_balances_account ON account_balances (ledger_account_id);

-- ---------------------------------------------------------------------
-- Ledger invariants enforced by the database (defence in depth; the
-- application's LedgerValidationService enforces the same rules first).
-- ---------------------------------------------------------------------

-- 1. Posted ledger entries and transactions are immutable.
CREATE FUNCTION ledger_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'LEDGER_IMMUTABLE: % on % is not permitted; post a reversal instead', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER trg_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();

-- 2. Entry currency must match both its transaction and its account.
CREATE FUNCTION ledger_check_entry_currency() RETURNS trigger AS $$
DECLARE
    tx_currency  VARCHAR(3);
    acc_currency VARCHAR(3);
BEGIN
    SELECT currency INTO tx_currency FROM ledger_transactions WHERE id = NEW.ledger_transaction_id;
    SELECT currency INTO acc_currency FROM ledger_accounts WHERE id = NEW.ledger_account_id;
    IF NEW.currency <> tx_currency OR NEW.currency <> acc_currency THEN
        RAISE EXCEPTION 'LEDGER_CURRENCY_MISMATCH: entry %, transaction %, account %',
            NEW.currency, tx_currency, acc_currency
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_currency
    BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_currency();

-- 3. Every transaction must have >= 2 entries and balance (debits = credits)
--    by the time the database transaction commits. Deferred constraint
--    triggers run at COMMIT, after all entries of the transaction exist.
CREATE FUNCTION ledger_check_transaction_balanced() RETURNS trigger AS $$
DECLARE
    target_tx    UUID;
    entry_count  INTEGER;
    debit_total  NUMERIC;
    credit_total NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'ledger_entries' THEN
        target_tx := NEW.ledger_transaction_id;
    ELSE
        target_tx := NEW.id;
    END IF;

    SELECT count(*),
           coalesce(sum(amount_minor) FILTER (WHERE entry_type = 'DEBIT'), 0),
           coalesce(sum(amount_minor) FILTER (WHERE entry_type = 'CREDIT'), 0)
      INTO entry_count, debit_total, credit_total
      FROM ledger_entries
     WHERE ledger_transaction_id = target_tx;

    IF entry_count < 2 THEN
        RAISE EXCEPTION 'LEDGER_UNBALANCED: transaction % has % entries (minimum 2)', target_tx, entry_count
            USING ERRCODE = 'check_violation';
    END IF;
    IF debit_total <> credit_total THEN
        RAISE EXCEPTION 'LEDGER_UNBALANCED: transaction % debits % <> credits %', target_tx, debit_total, credit_total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_transaction_balanced();

CREATE CONSTRAINT TRIGGER trg_ledger_transactions_balanced
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_transaction_balanced();

-- ---------------------------------------------------------------------
-- Money movement records
-- ---------------------------------------------------------------------
CREATE TABLE transfers (
    id                     UUID PRIMARY KEY,
    public_reference       VARCHAR(32)  NOT NULL,
    sender_id              UUID         NOT NULL REFERENCES users (id),
    recipient_id           UUID         NOT NULL REFERENCES users (id),
    amount_minor           BIGINT       NOT NULL,
    currency               VARCHAR(3)      NOT NULL,
    description            VARCHAR(140),
    status                 VARCHAR(16)  NOT NULL,
    ledger_transaction_id  UUID         NOT NULL REFERENCES ledger_transactions (id),
    created_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_transfers_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_transfers_not_self CHECK (sender_id <> recipient_id),
    CONSTRAINT ck_transfers_status CHECK (status IN ('COMPLETED')),
    CONSTRAINT ck_transfers_currency CHECK (currency IN ('INR', 'USD'))
);
CREATE UNIQUE INDEX ux_transfers_reference ON transfers (public_reference);
CREATE UNIQUE INDEX ux_transfers_ledger_tx ON transfers (ledger_transaction_id);
CREATE INDEX ix_transfers_sender ON transfers (sender_id, created_at DESC);
CREATE INDEX ix_transfers_recipient ON transfers (recipient_id, created_at DESC);

CREATE TABLE payment_intents (
    id                     UUID PRIMARY KEY,
    public_reference       VARCHAR(32)  NOT NULL,
    customer_id            UUID         NOT NULL REFERENCES users (id),
    merchant_id            UUID         NOT NULL REFERENCES users (id),
    amount_minor           BIGINT       NOT NULL,
    currency               VARCHAR(3)      NOT NULL,
    fee_bps                INTEGER      NOT NULL,
    platform_fee_minor     BIGINT       NOT NULL,
    merchant_net_minor     BIGINT       NOT NULL,
    refunded_amount_minor  BIGINT       NOT NULL DEFAULT 0,
    refunded_fee_minor     BIGINT       NOT NULL DEFAULT 0,
    status                 VARCHAR(24)  NOT NULL,
    description            VARCHAR(255),
    metadata               JSONB        NOT NULL DEFAULT '{}'::jsonb,
    failure_code           VARCHAR(64),
    failure_message        VARCHAR(255),
    ledger_transaction_id  UUID REFERENCES ledger_transactions (id),
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    captured_at            TIMESTAMPTZ,
    cancelled_at           TIMESTAMPTZ,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_pi_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_pi_fee_split CHECK (platform_fee_minor >= 0 AND merchant_net_minor >= 0
                                      AND platform_fee_minor + merchant_net_minor = amount_minor),
    CONSTRAINT ck_pi_fee_bps CHECK (fee_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_pi_refunded CHECK (refunded_amount_minor BETWEEN 0 AND amount_minor
                                     AND refunded_fee_minor BETWEEN 0 AND platform_fee_minor),
    CONSTRAINT ck_pi_status CHECK (status IN
        ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    CONSTRAINT ck_pi_currency CHECK (currency IN ('INR', 'USD')),
    CONSTRAINT ck_pi_not_self CHECK (customer_id <> merchant_id),
    CONSTRAINT ck_pi_captured_has_ledger CHECK (
        status NOT IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
        OR (ledger_transaction_id IS NOT NULL AND captured_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_pi_reference ON payment_intents (public_reference);
CREATE UNIQUE INDEX ux_pi_ledger_tx ON payment_intents (ledger_transaction_id) WHERE ledger_transaction_id IS NOT NULL;
CREATE INDEX ix_pi_customer ON payment_intents (customer_id, created_at DESC);
CREATE INDEX ix_pi_merchant ON payment_intents (merchant_id, created_at DESC);
CREATE INDEX ix_pi_merchant_status ON payment_intents (merchant_id, status);

CREATE TABLE refunds (
    id                       UUID PRIMARY KEY,
    public_reference         VARCHAR(32)  NOT NULL,
    payment_intent_id        UUID         NOT NULL REFERENCES payment_intents (id),
    amount_minor             BIGINT       NOT NULL,
    fee_reversal_minor       BIGINT       NOT NULL,
    merchant_debit_minor     BIGINT       NOT NULL,
    currency                 VARCHAR(3)      NOT NULL,
    status                   VARCHAR(16)  NOT NULL,
    reason                   VARCHAR(255),
    requested_by             UUID         NOT NULL REFERENCES users (id),
    ledger_transaction_id    UUID         NOT NULL REFERENCES ledger_transactions (id),
    created_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_refunds_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_refunds_split CHECK (fee_reversal_minor >= 0 AND merchant_debit_minor >= 0
                                       AND fee_reversal_minor + merchant_debit_minor = amount_minor),
    CONSTRAINT ck_refunds_status CHECK (status IN ('SUCCEEDED')),
    CONSTRAINT ck_refunds_currency CHECK (currency IN ('INR', 'USD'))
);
CREATE UNIQUE INDEX ux_refunds_reference ON refunds (public_reference);
CREATE UNIQUE INDEX ux_refunds_ledger_tx ON refunds (ledger_transaction_id);
CREATE INDEX ix_refunds_payment ON refunds (payment_intent_id, created_at);

CREATE TABLE payouts (
    id                     UUID PRIMARY KEY,
    public_reference       VARCHAR(32)  NOT NULL,
    merchant_id            UUID         NOT NULL REFERENCES users (id),
    amount_minor           BIGINT       NOT NULL,
    currency               VARCHAR(3)      NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    ledger_transaction_id  UUID         NOT NULL REFERENCES ledger_transactions (id),
    created_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_payouts_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_payouts_status CHECK (status IN ('PAID')),
    CONSTRAINT ck_payouts_currency CHECK (currency IN ('INR', 'USD'))
);
CREATE UNIQUE INDEX ux_payouts_reference ON payouts (public_reference);
CREATE INDEX ix_payouts_merchant ON payouts (merchant_id, created_at DESC);

-- ---------------------------------------------------------------------
-- Idempotency
-- ---------------------------------------------------------------------
CREATE TABLE idempotency_records (
    id                      UUID PRIMARY KEY,
    owner_id                UUID         NOT NULL REFERENCES users (id),
    idempotency_key         VARCHAR(128) NOT NULL,
    http_method             VARCHAR(8)   NOT NULL,
    endpoint                VARCHAR(128) NOT NULL,
    request_fingerprint     VARCHAR(64)     NOT NULL,
    response_status         INTEGER      NOT NULL,
    response_body           JSONB        NOT NULL,
    related_transaction_id  UUID,
    created_at              TIMESTAMPTZ  NOT NULL,
    expires_at              TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_idem_expiry CHECK (expires_at > created_at)
);
-- Scope: one logical operation per (owner, operation, key).
CREATE UNIQUE INDEX ux_idem_scope ON idempotency_records (owner_id, http_method, endpoint, idempotency_key);
CREATE INDEX ix_idem_expires ON idempotency_records (expires_at);

-- ---------------------------------------------------------------------
-- Events & webhooks (transactional outbox)
-- ---------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(64)  NOT NULL,
    aggregate_type  VARCHAR(32)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    merchant_id     UUID REFERENCES users (id),
    payload         JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED'))
);
CREATE INDEX ix_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_merchant ON outbox_events (merchant_id, created_at DESC);

CREATE TABLE webhook_endpoints (
    id                 UUID PRIMARY KEY,
    merchant_id        UUID          NOT NULL REFERENCES users (id),
    url                VARCHAR(2048) NOT NULL,
    description        VARCHAR(255),
    secret_ciphertext  TEXT          NOT NULL,
    secret_hint        VARCHAR(16)   NOT NULL,
    subscribed_events  TEXT[]        NOT NULL,
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT ck_webhook_events_not_empty CHECK (cardinality(subscribed_events) > 0)
);
CREATE INDEX ix_webhook_endpoints_merchant ON webhook_endpoints (merchant_id) WHERE deleted_at IS NULL;

CREATE TABLE webhook_deliveries (
    id                UUID PRIMARY KEY,
    event_id          UUID        NOT NULL REFERENCES outbox_events (id),
    endpoint_id       UUID        NOT NULL REFERENCES webhook_endpoints (id),
    status            VARCHAR(16) NOT NULL,
    attempt_count     INTEGER     NOT NULL DEFAULT 0,
    replay_count      INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ,
    last_attempt_at   TIMESTAMPTZ,
    last_http_status  INTEGER,
    last_error        VARCHAR(500),
    delivered_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_wd_status CHECK (status IN ('PENDING', 'RETRYING', 'SUCCEEDED', 'DEAD')),
    CONSTRAINT ck_wd_attempts CHECK (attempt_count >= 0 AND replay_count >= 0)
);
-- One delivery per (event, endpoint): fan-out is idempotent.
CREATE UNIQUE INDEX ux_wd_event_endpoint ON webhook_deliveries (event_id, endpoint_id);
CREATE INDEX ix_wd_due ON webhook_deliveries (next_attempt_at) WHERE status IN ('PENDING', 'RETRYING');
CREATE INDEX ix_wd_endpoint ON webhook_deliveries (endpoint_id, created_at DESC);

CREATE TABLE webhook_delivery_attempts (
    id              UUID PRIMARY KEY,
    delivery_id     UUID         NOT NULL REFERENCES webhook_deliveries (id),
    attempt_number  INTEGER      NOT NULL,
    http_status     INTEGER,
    error           VARCHAR(500),
    duration_ms     BIGINT       NOT NULL,
    response_snippet VARCHAR(512),
    attempted_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_wda_delivery ON webhook_delivery_attempts (delivery_id, attempt_number);

-- Dev/sandbox-only receiver used for local webhook demos.
CREATE TABLE sandbox_webhook_receipts (
    id               UUID PRIMARY KEY,
    merchant_id      UUID        NOT NULL REFERENCES users (id),
    event_id         VARCHAR(64),
    event_type       VARCHAR(64),
    signature_valid  BOOLEAN,
    received_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_sandbox_receipts_merchant ON sandbox_webhook_receipts (merchant_id, received_at DESC);

-- ---------------------------------------------------------------------
-- Audit
-- ---------------------------------------------------------------------
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY,
    actor_id        UUID,
    actor_role      VARCHAR(16),
    action          VARCHAR(64)  NOT NULL,
    resource_type   VARCHAR(32)  NOT NULL,
    resource_id     VARCHAR(64),
    result          VARCHAR(16)  NOT NULL,
    correlation_id  VARCHAR(64),
    ip_address      VARCHAR(64),
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED'))
);
CREATE INDEX ix_audit_created ON audit_logs (created_at DESC);
CREATE INDEX ix_audit_actor ON audit_logs (actor_id, created_at DESC);
CREATE INDEX ix_audit_resource ON audit_logs (resource_type, resource_id);
