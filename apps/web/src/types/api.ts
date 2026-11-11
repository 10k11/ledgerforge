/** Types mirroring the LedgerForge REST API (all amounts are integer minor units). */

export type Role = 'CUSTOMER' | 'MERCHANT' | 'ADMIN';

export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
  [key: string]: unknown;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface MerchantInfo {
  profileId: string;
  businessName: string;
  category: string;
  supportEmail?: string;
}

export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  defaultCurrency: string;
  createdAt: string;
  lastLoginAt?: string;
  merchant?: MerchantInfo;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  expiresAt: string;
  user: UserProfile;
}

export interface SessionView {
  id: string;
  createdAt: string;
  lastUsedAt: string;
  expiresAt: string;
  userAgent?: string;
  ipAddress?: string;
  current: boolean;
}

export interface Meta {
  environment: string;
  sandboxEnabled: boolean;
  platformFeeBps: number;
  currencies: string[];
  maxTopUpMinor: number;
  dailyTopUpLimitMinor: number;
  sandboxReceiverBaseUrl?: string;
}

export interface WalletView {
  accountId: string;
  accountNumber: string;
  accountName: string;
  currency: string;
  balanceMinor: number;
  status: string;
  sandboxEnabled: boolean;
  topUpLimits: { maxPerTopUpMinor: number; dailyLimitMinor: number; usedTodayMinor: number };
  createdAt: string;
}

export interface TopUpResponse {
  transactionId: string;
  reference: string;
  amountMinor: number;
  currency: string;
  balanceAfterMinor: number;
  createdAt: string;
}

export interface StatementLine {
  transactionId: string;
  reference: string;
  type: TransactionType;
  description: string;
  direction: 'IN' | 'OUT';
  amountMinor: number;
  runningBalanceMinor: number;
  postedAt: string;
}

export interface Statement {
  accountNumber: string;
  accountName: string;
  currency: string;
  from: string;
  to: string;
  openingBalanceMinor: number;
  closingBalanceMinor: number;
  totalCreditsMinor: number;
  totalDebitsMinor: number;
  lines: StatementLine[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export type TransactionType = 'TOP_UP' | 'TRANSFER' | 'PAYMENT' | 'REFUND' | 'PAYOUT' | 'REVERSAL';

export interface TransactionSummary {
  id: string;
  reference: string;
  type: TransactionType;
  status: string;
  description: string;
  counterparty?: string;
  currency: string;
  amountMinor: number;
  direction: 'IN' | 'OUT';
  createdAt: string;
}

export interface TransactionDetail extends TransactionSummary {
  postedAt: string;
  idempotencyKey?: string;
  related?: { kind: string; id: string; reference: string; status: string };
  reversesTransactionId?: string;
  reversesReference?: string;
  reversedBy: string[];
}

export interface LedgerLine {
  entryId: string;
  sequenceNumber: number;
  accountLabel: string;
  accountNumber: string;
  accountType: string;
  entryType: 'DEBIT' | 'CREDIT';
  debitMinor: number;
  creditMinor: number;
  ownedByViewer: boolean;
}

export interface LedgerView {
  transactionId: string;
  reference: string;
  currency: string;
  status: string;
  lines: LedgerLine[];
  totalDebitsMinor: number;
  totalCreditsMinor: number;
  balanced: boolean;
  postedAt: string;
}

export interface RecipientView {
  displayName: string;
  maskedEmail: string;
  accountNumberMasked: string;
  currency: string;
}

export interface TransferView {
  id: string;
  reference: string;
  direction: 'SENT' | 'RECEIVED';
  counterpartyName: string;
  amountMinor: number;
  currency: string;
  description?: string;
  status: string;
  ledgerTransactionId: string;
  senderBalanceAfterMinor?: number;
  createdAt: string;
}

export type PaymentStatus =
  | 'CREATED'
  | 'PROCESSING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED';

export interface PaymentIntent {
  id: string;
  reference: string;
  status: PaymentStatus;
  amountMinor: number;
  currency: string;
  feeBps: number;
  platformFeeMinor: number;
  merchantNetMinor: number;
  refundedAmountMinor: number;
  refundableAmountMinor: number;
  description?: string;
  metadata: Record<string, string>;
  merchant?: { id: string; name: string };
  customer?: { id: string; name: string; maskedEmail?: string };
  failureCode?: string;
  failureMessage?: string;
  ledgerTransactionId?: string;
  createdAt: string;
  updatedAt: string;
  capturedAt?: string;
  cancelledAt?: string;
}

export interface RefundView {
  id: string;
  reference: string;
  paymentId: string;
  paymentReference: string;
  amountMinor: number;
  feeReversalMinor: number;
  merchantDebitMinor: number;
  currency: string;
  status: string;
  reason?: string;
  ledgerTransactionId: string;
  paymentStatus: PaymentStatus;
  paymentRefundedAmountMinor: number;
  paymentRefundableAmountMinor: number;
  createdAt: string;
}

export interface DirectoryEntry {
  merchantId: string;
  businessName: string;
  category: string;
  currencies: string[];
}

export interface DashboardSummary {
  accountNumber: string;
  accountKind: 'CUSTOMER_WALLET' | 'MERCHANT_PAYABLE';
  currency: string;
  balanceMinor: number;
  totalInMinor: number;
  totalOutMinor: number;
  byType: { type: TransactionType; inMinor: number; outMinor: number; count: number }[];
  successfulPayments: number;
  pendingPayments: number;
  transactionCount: number;
  last30Days: { date: string; inMinor: number; outMinor: number }[];
}

export interface DailyPoint {
  date: string;
  grossMinor: number;
  feesMinor: number;
  netMinor: number;
  refundsMinor: number;
  count: number;
}

export interface MerchantDashboard {
  businessName: string;
  currency: string;
  balanceMinor: number;
  grossVolumeMinor: number;
  platformFeesMinor: number;
  netVolumeMinor: number;
  refundedMinor: number;
  paidOutMinor: number;
  successfulPayments: number;
  failedPayments: number;
  pendingPayments: number;
  paymentsByStatus: Record<string, number>;
  last30Days: DailyPoint[];
}

export interface MerchantReport {
  from: string;
  to: string;
  currency: string;
  grossVolumeMinor: number;
  platformFeesMinor: number;
  netVolumeMinor: number;
  refundedMinor: number;
  successfulPayments: number;
  days: DailyPoint[];
}

export interface MerchantBalance {
  accountId: string;
  accountNumber: string;
  currency: string;
  availableMinor: number;
  asOf: string;
}

export interface PayoutView {
  id: string;
  reference: string;
  amountMinor: number;
  currency: string;
  status: string;
  ledgerTransactionId: string;
  balanceAfterMinor: number;
  createdAt: string;
}

export interface MerchantProfileView {
  id: string;
  businessName: string;
  category: string;
  supportEmail?: string;
  website?: string;
  status: string;
  createdAt: string;
}

export interface WebhookView {
  id: string;
  url: string;
  description?: string;
  events: string[];
  enabled: boolean;
  secretHint: string;
  stats: { succeeded: number; pending: number; dead: number };
  createdAt: string;
  updatedAt: string;
}

export interface WebhookWithSecret {
  webhook: WebhookView;
  secret: string;
}

export interface DeliveryAttempt {
  attemptNumber: number;
  httpStatus?: number;
  error?: string;
  durationMs: number;
  responseSnippet?: string;
  attemptedAt: string;
}

export type DeliveryStatus = 'PENDING' | 'RETRYING' | 'SUCCEEDED' | 'DEAD';

export interface DeliveryView {
  id: string;
  eventId: string;
  eventType: string;
  status: DeliveryStatus;
  attemptCount: number;
  replayCount: number;
  nextAttemptAt?: string;
  lastAttemptAt?: string;
  lastHttpStatus?: number;
  lastError?: string;
  deliveredAt?: string;
  createdAt: string;
  payload: unknown;
  attempts: DeliveryAttempt[];
}

export interface ReceiptView {
  id: string;
  eventId?: string;
  eventType?: string;
  signatureValid?: boolean;
  receivedAt: string;
}

export interface NotificationItem {
  id: string;
  kind: string;
  title: string;
  body: string;
  link: string;
  createdAt: string;
  unread: boolean;
}

export interface Notifications {
  unreadCount: number;
  items: NotificationItem[];
}

export interface ReconciliationReport {
  checkedAt: string;
  healthy: boolean;
  accountsChecked: number;
  transactionsChecked: number;
  balanceMismatches: {
    accountId: string;
    accountNumber: string;
    projectedBalanceMinor: number;
    ledgerBalanceMinor: number;
  }[];
  unbalancedTransactions: string[];
  trialBalances: {
    currency: string;
    totalDebitsMinor: number;
    totalCreditsMinor: number;
    assetsMinor: number;
    liabilitiesMinor: number;
    equityMinor: number;
    revenueMinor: number;
    expensesMinor: number;
    balanced: boolean;
    accountingEquationHolds: boolean;
  }[];
}

export interface AdminOverview {
  usersByRole: Record<string, number>;
  ledgerTransactions: number;
  ledgerEntries: number;
  paymentsByStatus: Record<string, number>;
  volumes: {
    currency: string;
    capturedMinor: number;
    feesMinor: number;
    refundedMinor: number;
    walletLiabilitiesMinor: number;
    merchantLiabilitiesMinor: number;
  }[];
  systemAccounts: {
    id: string;
    accountNumber: string;
    name: string;
    type: string;
    purpose: string;
    currency: string;
    balanceMinor: number;
  }[];
  webhookDeliveriesByStatus: Record<string, number>;
}

export interface AuditEntry {
  id: string;
  actorId?: string;
  actorRole?: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  result: string;
  correlationId?: string;
  metadata: string;
  createdAt: string;
}
