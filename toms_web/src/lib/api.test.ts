import { AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, downloadFile, login, logout, setUnauthorizedListener, tokenStore } from './api';

type Handler = { fulfilled?: (v: any) => any; rejected?: (e: any) => any };
const requestHandler = () => (api.interceptors.request as unknown as { handlers: Handler[] }).handlers[0];
const responseHandler = () => (api.interceptors.response as unknown as { handlers: Handler[] }).handlers[0];

beforeEach(() => sessionStorage.clear());
afterEach(() => {
  vi.restoreAllMocks();
  setUnauthorizedListener(() => undefined);
});

describe('token storage', () => {
  it('lives in sessionStorage so closing the tab signs out', () => {
    tokenStore.set('abc');
    expect(sessionStorage.getItem('toms_admin_token')).toBe('abc');
    expect(localStorage.getItem('toms_admin_token')).toBeNull();
    tokenStore.clear();
    expect(tokenStore.get()).toBeNull();
  });
});

describe('request interceptor', () => {
  const config = () => ({ headers: new AxiosHeaders() }) as InternalAxiosRequestConfig;

  it('adds the bearer token when signed in and nothing when not', async () => {
    expect((await requestHandler().fulfilled!(config())).headers.get('Authorization')).toBeUndefined();
    tokenStore.set('tok');
    expect((await requestHandler().fulfilled!(config())).headers.get('Authorization')).toBe('Bearer tok');
  });
});

describe('response interceptor', () => {
  const unauthorized = (url: string) => ({ config: { url }, response: { status: 401 } });

  it('signs out and tells the app when an expired session gets a 401', async () => {
    const listener = vi.fn();
    setUnauthorizedListener(listener);
    tokenStore.set('old');
    await expect(responseHandler().rejected!(unauthorized('/routes'))).rejects.toBeDefined();
    expect(tokenStore.get()).toBeNull();
    expect(listener).toHaveBeenCalledOnce();
  });

  it('does not treat a wrong password on the login call as an expired session', async () => {
    const listener = vi.fn();
    setUnauthorizedListener(listener);
    tokenStore.set('keep');
    await expect(responseHandler().rejected!(unauthorized('/admin/login'))).rejects.toBeDefined();
    expect(tokenStore.get()).toBe('keep');
    expect(listener).not.toHaveBeenCalled();
  });

  it('ignores other errors, and a 401 when nobody was signed in', async () => {
    const listener = vi.fn();
    setUnauthorizedListener(listener);
    tokenStore.set('keep');
    await expect(
      responseHandler().rejected!({ config: { url: '/routes' }, response: { status: 500 } }),
    ).rejects.toBeDefined();
    expect(tokenStore.get()).toBe('keep');
    tokenStore.clear();
    await expect(responseHandler().rejected!(unauthorized('/routes'))).rejects.toBeDefined();
    expect(listener).not.toHaveBeenCalled();
  });
});

describe('login and logout', () => {
  it('stores the token from a successful login and clears it on logout', async () => {
    vi.spyOn(api, 'post').mockResolvedValue({ data: { token: 'jwt-1' } });
    await login('boss', 'pw');
    expect(api.post).toHaveBeenCalledWith('/admin/login', { username: 'boss', password: 'pw' });
    expect(tokenStore.get()).toBe('jwt-1');
    logout();
    expect(tokenStore.get()).toBeNull();
  });

  it('stores nothing when login fails', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(new Error('401'));
    await expect(login('boss', 'bad')).rejects.toThrow();
    expect(tokenStore.get()).toBeNull();
  });
});

describe('downloadFile', () => {
  function captureClicks() {
    const clicked: string[] = [];
    const original = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement) {
      clicked.push(this.download);
    };
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    return { clicked, restore: () => (HTMLAnchorElement.prototype.click = original) };
  }

  it('fetches with the client, names the file from the header and reports truncation', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({
      data: new Blob(['a,b']),
      headers: { 'content-disposition': 'attachment; filename="toms_audit_logs.csv"', 'x-export-truncated': 'true' },
    });
    const { clicked, restore } = captureClicks();
    try {
      const result = await downloadFile('/export/audit', 'fallback.csv');
      expect(api.get).toHaveBeenCalledWith('/export/audit', { responseType: 'blob' });
      expect(result.truncated).toBe(true);
      expect(clicked).toEqual(['toms_audit_logs.csv']);
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:x');
    } finally {
      restore();
    }
  });

  it('uses the fallback name and reports not truncated when headers are absent', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: new Blob(['x']), headers: {} });
    const { clicked, restore } = captureClicks();
    try {
      expect((await downloadFile('/export/audit', 'fallback.csv')).truncated).toBe(false);
      expect(clicked).toEqual(['fallback.csv']);
    } finally {
      restore();
    }
  });

  it('does not leave a download link in the page', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({ data: new Blob(['x']), headers: {} });
    const { restore } = captureClicks();
    try {
      await downloadFile('/export/audit', 'f.csv');
      expect(document.querySelectorAll('a[download]')).toHaveLength(0);
    } finally {
      restore();
    }
  });
});
