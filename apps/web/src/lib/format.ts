const dateTime = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});
const dateOnly = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
const shortDay = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short' });
const longDate = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });

export const formatDateTime = (iso?: string | null) => (iso ? dateTime.format(new Date(iso)) : '—');
export const formatDate = (iso?: string | null) => (iso ? dateOnly.format(new Date(iso)) : '—');
export const formatShortDay = (iso: string) => shortDay.format(new Date(iso));
export const formatLongDate = (d: Date) => longDate.format(d);

export function relativeTime(iso: string, now: Date = new Date()): string {
  const diff = (now.getTime() - new Date(iso).getTime()) / 1000;
  if (diff < 60) return 'just now';
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}d ago`;
  return formatDate(iso);
}

export function humanize(value: string): string {
  const lower = value.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/** Start of a local day expressed as an ISO instant, for API date filters. */
export function startOfDayIso(date: string): string {
  return new Date(`${date}T00:00:00`).toISOString();
}

export function endOfDayExclusiveIso(date: string): string {
  const d = new Date(`${date}T00:00:00`);
  d.setDate(d.getDate() + 1);
  return d.toISOString();
}

export function greeting(now: Date = new Date()): string {
  const h = now.getHours();
  if (h < 12) return 'Good morning';
  if (h < 18) return 'Good afternoon';
  return 'Good evening';
}
