/**
 * Money helpers. Amounts travel as integer minor units (paise / cents) and are never
 * represented as floating point: parsing and formatting are done with string/integer math.
 */
export type CurrencyCode = 'INR' | 'USD';

const SYMBOLS: Record<CurrencyCode, string> = { INR: '₹', USD: '$' };
const LOCALES: Record<CurrencyCode, string> = { INR: 'en-IN', USD: 'en-US' };
export const MINOR_DIGITS = 2;
const MAX_SAFE_MINOR = 100_000_000_000; // 1,000,000,000.00

export function currencySymbol(currency: string): string {
  return SYMBOLS[currency as CurrencyCode] ?? `${currency} `;
}

/**
 * Formats minor units, e.g. formatMoney(123456, 'INR') => "₹1,234.56".
 * Integer division keeps the result exact for any safe integer.
 */
export function formatMoney(
  minor: number,
  currency: string,
  opts: { sign?: 'auto' | 'always' | 'never'; compact?: boolean } = {},
): string {
  const negative = minor < 0;
  const abs = Math.abs(minor);
  const major = Math.trunc(abs / 100);
  const fraction = String(abs % 100).padStart(MINOR_DIGITS, '0');
  const locale = LOCALES[currency as CurrencyCode] ?? 'en-US';
  let body: string;
  if (opts.compact && major >= 100_000) {
    body = new Intl.NumberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 }).format(major);
  } else {
    body = `${new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(major)}.${fraction}`;
  }
  const symbol = currencySymbol(currency);
  const sign = opts.sign ?? 'auto';
  const prefix = negative ? (sign === 'never' ? '' : '−') : sign === 'always' && minor > 0 ? '+' : '';
  return `${prefix}${symbol}${body}`;
}

export type ParseResult = { ok: true; minor: number } | { ok: false; error: string };

/**
 * Parses user input such as "1,000.5" into minor units (100050) without floating point.
 * Rejects more than two decimals, negatives, zero, and values above the platform maximum.
 */
export function parseMoneyInput(raw: string): ParseResult {
  const value = raw.trim().replace(/[,\s_]/g, '');
  if (value === '') return { ok: false, error: 'Enter an amount' };
  const match = /^(\d+)(?:\.(\d{0,2}))?$/.exec(value);
  if (!match) {
    return /^\d+\.\d{3,}$/.test(value)
      ? { ok: false, error: 'Use at most 2 decimal places' }
      : { ok: false, error: 'Enter a valid amount' };
  }
  const whole = match[1]!.replace(/^0+(?=\d)/, '');
  const fraction = (match[2] ?? '').padEnd(MINOR_DIGITS, '0');
  if (whole.length > 12) return { ok: false, error: 'Amount is too large' };
  const minor = Number(whole) * 100 + Number(fraction);
  if (!Number.isSafeInteger(minor) || minor > MAX_SAFE_MINOR) return { ok: false, error: 'Amount is too large' };
  if (minor <= 0) return { ok: false, error: 'Amount must be greater than zero' };
  return { ok: true, minor };
}

/** Minor units to an editable decimal string: 100050 -> "1000.50". */
export function minorToInput(minor: number): string {
  return `${Math.trunc(minor / 100)}.${String(Math.abs(minor % 100)).padStart(2, '0')}`;
}

export function formatBps(bps: number): string {
  const whole = Math.trunc(bps / 100);
  const frac = bps % 100;
  return frac === 0 ? `${whole}%` : `${whole}.${String(frac).padStart(2, '0').replace(/0$/, '')}%`;
}
