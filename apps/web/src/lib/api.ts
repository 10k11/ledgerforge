/**
 * HTTP client for the LedgerForge API.
 *
 * Security model:
 *  - The access token lives only in module memory (never localStorage/sessionStorage).
 *  - The refresh token is an HttpOnly cookie the browser sends to /api/v1/auth only.
 *  - Refresh/logout send the double-submit CSRF header obtained from /api/v1/auth/csrf.
 *  - A 401 triggers one single-flight refresh, then the request is retried once.
 */
import type { AuthResponse, Problem } from '@/types/api';

const BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '';

let accessToken: string | null = null;
let refreshInFlight: Promise<AuthResponse | null> | null = null;
let onSessionChange: ((session: AuthResponse | null) => void) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

/** The auth provider subscribes so refreshed/expired sessions update React state. */
export function subscribeSession(listener: (session: AuthResponse | null) => void) {
  onSessionChange = listener;
  return () => {
    if (onSessionChange === listener) onSessionChange = null;
  };
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail: string;
  readonly fieldErrors: { field: string; message: string }[];
  readonly correlationId?: string;
  readonly problem: Problem | null;

  constructor(status: number, problem: Problem | null, fallback: string) {
    super(problem?.detail ?? fallback);
    this.status = status;
    this.problem = problem;
    this.code = problem?.code ?? (status === 0 ? 'NETWORK_ERROR' : 'HTTP_' + status);
    this.detail = problem?.detail ?? fallback;
    this.fieldErrors = problem?.errors ?? [];
    this.correlationId = problem?.correlationId;
  }
}

export interface RequestOptions {
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
  /** Internal: prevents refresh recursion. */
  skipRefresh?: boolean;
  headers?: Record<string, string>;
}

async function parseProblem(res: Response): Promise<Problem | null> {
  const type = res.headers.get('content-type') ?? '';
  if (!type.includes('json')) return null;
  try {
    return (await res.json()) as Problem;
  } catch {
    return null;
  }
}

export interface ApiResponse<T> {
  data: T;
  replayed: boolean;
}

export async function requestWithMeta<T>(
  method: string,
  path: string,
  opts: RequestOptions = {},
): Promise<ApiResponse<T>> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;

  let res: Response;
  try {
    res = await fetch(BASE + path, {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      credentials: 'include',
      signal: opts.signal,
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError(0, null, 'Network error: the server could not be reached. Check your connection and retry.');
  }

  if (res.status === 401 && !opts.skipRefresh && accessToken) {
    const refreshed = await refreshSession();
    if (refreshed) {
      return requestWithMeta<T>(method, path, { ...opts, skipRefresh: true });
    }
  }

  if (!res.ok) {
    const problem = await parseProblem(res);
    throw new ApiError(res.status, problem, `Request failed (${res.status})`);
  }
  const replayed = res.headers.get('Idempotent-Replayed') === 'true';
  if (res.status === 204) return { data: undefined as T, replayed };
  const text = await res.text();
  return { data: (text ? JSON.parse(text) : undefined) as T, replayed };
}

export async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  return (await requestWithMeta<T>(method, path, opts)).data;
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>('GET', path, { signal }),
  post: <T>(path: string, body?: unknown, idempotencyKey?: string) =>
    request<T>('POST', path, { body, idempotencyKey }),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, { body }),
  delete: <T>(path: string) => request<T>('DELETE', path),
};

async function csrfHeader(): Promise<Record<string, string>> {
  const res = await fetch(`${BASE}/api/v1/auth/csrf`, { credentials: 'include' });
  if (!res.ok) return {};
  const body = (await res.json()) as { headerName: string; token: string };
  return { [body.headerName]: body.token };
}

/** Rotates the refresh cookie and obtains a new access token. Concurrent callers share one call. */
export function refreshSession(): Promise<AuthResponse | null> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const headers = await csrfHeader();
        const res = await fetch(`${BASE}/api/v1/auth/refresh`, {
          method: 'POST',
          headers: { Accept: 'application/json', ...headers },
          credentials: 'include',
        });
        if (!res.ok) {
          setAccessToken(null);
          onSessionChange?.(null);
          return null;
        }
        const session = (await res.json()) as AuthResponse;
        setAccessToken(session.accessToken);
        onSessionChange?.(session);
        return session;
      } catch {
        return null;
      } finally {
        setTimeout(() => {
          refreshInFlight = null;
        }, 0);
      }
    })();
  }
  return refreshInFlight;
}

export async function logoutRequest(): Promise<void> {
  try {
    const headers = await csrfHeader();
    await fetch(`${BASE}/api/v1/auth/logout`, { method: 'POST', headers, credentials: 'include' });
  } finally {
    setAccessToken(null);
  }
}

/** Builds a query string, skipping empty values. */
export function qs(params: Record<string, string | number | undefined | null>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.detail;
  if (error instanceof Error) return error.message;
  return 'Something went wrong';
}
