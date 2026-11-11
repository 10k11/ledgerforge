import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, getAccessToken, qs, setAccessToken } from './api';

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });
}

async function rejection(p: Promise<unknown>): Promise<ApiError> {
  try {
    await p;
  } catch (e) {
    return e as ApiError;
  }
  throw new Error('expected the request to fail');
}

afterEach(() => {
  vi.unstubAllGlobals();
  setAccessToken(null);
});

describe('api client', () => {
  it('sends the bearer token and parses RFC 9457 problems', async () => {
    setAccessToken('tok-1');
    const fetchMock = vi.fn().mockResolvedValue(
      json(422, { code: 'INSUFFICIENT_FUNDS', detail: 'Insufficient funds for this operation', correlationId: 'req-1' }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const err = await rejection(api.post('/api/v1/transfers', { amountMinor: 1 }, 'key-12345678'));
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('INSUFFICIENT_FUNDS');
    expect(err.correlationId).toBe('req-1');
    const init = fetchMock.mock.calls[0][1];
    expect(init.headers.Authorization).toBe('Bearer tok-1');
    expect(init.headers['Idempotency-Key']).toBe('key-12345678');
  });

  it('refreshes once on 401 (with CSRF header) and retries the request', async () => {
    setAccessToken('expired');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' }))
      .mockResolvedValueOnce(json(200, { headerName: 'X-XSRF-TOKEN', token: 'csrf-abc' }))
      .mockResolvedValueOnce(json(200, { accessToken: 'fresh', user: { id: 'u' } }))
      .mockResolvedValueOnce(json(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(api.get('/api/v1/wallet')).resolves.toEqual({ ok: true });
    expect(getAccessToken()).toBe('fresh');
    const refreshCall = fetchMock.mock.calls[2];
    expect(refreshCall[0]).toBe('/api/v1/auth/refresh');
    expect(refreshCall[1].headers['X-XSRF-TOKEN']).toBe('csrf-abc');
    expect(refreshCall[1].credentials).toBe('include');
    expect(fetchMock.mock.calls[3][1].headers.Authorization).toBe('Bearer fresh');
  });

  it('maps network failures to a friendly error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    const err = await rejection(api.get('/x'));
    expect(err.code).toBe('NETWORK_ERROR');
  });

  it('never stores tokens in web storage', () => {
    setAccessToken('secret-token');
    expect(JSON.stringify({ ...localStorage })).not.toContain('secret-token');
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('secret-token');
  });

  it('builds query strings without empty values', () => {
    expect(qs({ a: 1, b: '', c: undefined, d: 'x y' })).toBe('?a=1&d=x+y');
    expect(qs({})).toBe('');
  });
});
