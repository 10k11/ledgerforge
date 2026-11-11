import { describe, expect, it } from 'vitest';
import { formatBps, formatMoney, minorToInput, parseMoneyInput } from './money';

describe('parseMoneyInput', () => {
  it.each([
    ['1000', 100000],
    ['1,000.5', 100050],
    ['0.01', 1],
    ['12.30', 1230],
    ['  250.13 ', 25013],
    ['0010.00', 1000],
  ])('parses %s to %i minor units without floating point', (raw, minor) => {
    expect(parseMoneyInput(raw)).toEqual({ ok: true, minor });
  });

  it('never suffers float rounding (0.29 * 100 would be 28.999…)', () => {
    expect(parseMoneyInput('0.29')).toEqual({ ok: true, minor: 29 });
    expect(parseMoneyInput('1.005')).toMatchObject({ ok: false });
  });

  it.each([
    ['', 'Enter an amount'],
    ['abc', 'Enter a valid amount'],
    ['-5', 'Enter a valid amount'],
    ['1.234', 'Use at most 2 decimal places'],
    ['0', 'Amount must be greater than zero'],
    ['0.00', 'Amount must be greater than zero'],
    ['9999999999999', 'Amount is too large'],
  ])('rejects %j', (raw, error) => {
    expect(parseMoneyInput(raw)).toEqual({ ok: false, error });
  });
});

describe('formatMoney', () => {
  it('formats INR with Indian grouping and exact paise', () => {
    expect(formatMoney(12345678901, 'INR')).toBe('₹12,34,56,789.01');
    expect(formatMoney(100000, 'INR')).toBe('₹1,000.00');
    expect(formatMoney(5, 'INR')).toBe('₹0.05');
  });
  it('formats USD and signs', () => {
    expect(formatMoney(123456, 'USD')).toBe('$1,234.56');
    expect(formatMoney(-2000, 'USD')).toBe('−$20.00');
    expect(formatMoney(2000, 'USD', { sign: 'always' })).toBe('+$20.00');
  });
  it('round-trips through input helpers', () => {
    expect(minorToInput(100050)).toBe('1000.50');
    expect(formatBps(200)).toBe('2%');
    expect(formatBps(250)).toBe('2.5%');
  });
});
