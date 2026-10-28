import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchTables, onWaking, analyze } from './api.js';

const ok = (body) => ({ ok: true, status: 200, json: async () => body });
const status = (code) => ({ ok: false, status: code, json: async () => ({}) });

describe('api', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('retries while the server is starting (503) and then succeeds', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(status(503)).mockResolvedValueOnce(status(502)).mockResolvedValueOnce(ok([{ name: 't', rows: 1 }]));
    vi.stubGlobal('fetch', fetchMock);
    const promise = fetchTables();
    await vi.advanceTimersByTimeAsync(10_000);
    await expect(promise).resolves.toEqual([{ name: 't', rows: 1 }]);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('retries after a network failure', async () => {
    const fetchMock = vi.fn().mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(ok([]));
    vi.stubGlobal('fetch', fetchMock);
    const promise = fetchTables();
    await vi.advanceTimersByTimeAsync(5_000);
    await expect(promise).resolves.toEqual([]);
  });

  it('gives a helpful message when the backend never answers', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    const promise = analyze('SELECT 1');
    const assertion = expect(promise).rejects.toThrow(/may still be waking up/);
    await vi.advanceTimersByTimeAsync(30_000);
    await assertion;
  });

  it('does not retry real errors such as 400 and shows the server message', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 400, json: async () => ({ message: 'Bad statement' }) });
    vi.stubGlobal('fetch', fetchMock);
    await expect(analyze('x')).rejects.toThrow('Bad statement');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('reports "waking up" only when a request is slow, and clears it afterwards', async () => {
    let release;
    vi.stubGlobal('fetch', vi.fn(() => new Promise((r) => (release = () => r(ok([]))))));
    const seen = [];
    const off = onWaking((v) => seen.push(v));
    const promise = fetchTables();
    await vi.advanceTimersByTimeAsync(1000);
    expect(seen).toEqual([]); // fast enough: no banner
    await vi.advanceTimersByTimeAsync(2000);
    expect(seen).toEqual([true]);
    release();
    await promise;
    expect(seen).toEqual([true, false]);
    off();
  });
});
