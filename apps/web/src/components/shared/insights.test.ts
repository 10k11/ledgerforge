import { describe, expect, it } from 'vitest';
import { dayLabel } from './finance';
import { balanceTrajectory, percentChange, windowTotals } from './insights';

describe('dashboard derivations', () => {
  it('reconstructs end-of-day balances backwards from the current balance', () => {
    const days = [
      { date: 'd1', inMinor: 10_000, outMinor: 0 },
      { date: 'd2', inMinor: 0, outMinor: 2_500 },
      { date: 'd3', inMinor: 500, outMinor: 1_000 },
    ];
    // Opening 0: +10000 -> 10000, -2500 -> 7500, +500-1000 -> 7000
    expect(balanceTrajectory(days, 7_000)).toEqual([10_000, 7_500, 7_000]);
  });

  it('computes rolling windows and percentage changes', () => {
    const values = [1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2];
    expect(windowTotals(values, 7)).toEqual({ current: 14, previous: 7 });
    expect(percentChange(14, 7)).toBe(100);
    expect(percentChange(5, 10)).toBe(-50);
    expect(percentChange(5, 0)).toBeNull();
    expect(percentChange(0, 0)).toBe(0);
  });

  it('labels days relative to today', () => {
    const now = new Date('2026-11-11T12:00:00');
    expect(dayLabel('2026-11-11T08:00:00', now)).toBe('Today');
    expect(dayLabel('2026-11-10T23:00:00', now)).toBe('Yesterday');
    expect(dayLabel('2026-11-01T10:00:00', now)).toContain('Nov');
  });
});
