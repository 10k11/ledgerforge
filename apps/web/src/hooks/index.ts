import { useCallback, useEffect, useRef, useState } from 'react';
import { newIdempotencyKey } from '@/lib/utils';

/**
 * A stable Idempotency-Key for one logical operation. The same key is reused when the user retries
 * after a timeout or double-clicks, so the server executes the operation at most once. Call
 * `reset()` after a confirmed success, or when the user changes what they are submitting.
 */
export function useIdempotencyKey() {
  const ref = useRef<string>(newIdempotencyKey());
  const [, force] = useState(0);
  const reset = useCallback(() => {
    ref.current = newIdempotencyKey();
    force((n) => n + 1);
  }, []);
  return { key: ref.current, reset };
}

export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}

export function useDocumentTitle(title: string) {
  useEffect(() => {
    document.title = title ? `${title} · LedgerForge` : 'LedgerForge';
  }, [title]);
}
