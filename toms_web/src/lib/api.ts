import axios from 'axios';

const TOKEN_KEY = 'toms_admin_token';

// Empty means same origin, which is how the Docker setup serves the API behind the web server.
export const API_BASE = (import.meta.env.VITE_API_URL ?? '').replace(/\/$/, '');

// sessionStorage, not localStorage: the token is gone when the tab closes, so a shared office PC
// does not stay signed in. It is readable by any script on the page, so keep the page free of
// third-party scripts.
export const tokenStore = {
  get: () => sessionStorage.getItem(TOKEN_KEY),
  set: (token: string) => sessionStorage.setItem(TOKEN_KEY, token),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
};

type UnauthorizedListener = () => void;
let onUnauthorized: UnauthorizedListener = () => undefined;
export function setUnauthorizedListener(fn: UnauthorizedListener) {
  onUnauthorized = fn;
}

/** The only client that carries the TOMS token. Third-party calls (routing) use plain axios. */
export const api = axios.create({ baseURL: `${API_BASE}/api` });

api.interceptors.request.use((config) => {
  const token = tokenStore.get();
  if (token) config.headers.set('Authorization', `Bearer ${token}`);
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (error) => {
    // A login attempt answering 401 means wrong password, not an expired session.
    const isLogin = String(error?.config?.url ?? '').endsWith('/admin/login');
    if (error?.response?.status === 401 && !isLogin && tokenStore.get()) {
      tokenStore.clear();
      onUnauthorized();
    }
    return Promise.reject(error);
  },
);

export async function login(username: string, password: string): Promise<void> {
  const res = await api.post<{ token: string }>('/admin/login', { username, password });
  tokenStore.set(res.data.token);
}

export function logout() {
  tokenStore.clear();
}

/** Downloads a file through the authenticated client, because a plain link cannot send a header. */
export async function downloadFile(path: string, fallbackName: string): Promise<{ truncated: boolean }> {
  const res = await api.get(path, { responseType: 'blob' });
  const disposition = String(res.headers['content-disposition'] ?? '');
  const name = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? fallbackName;
  const url = URL.createObjectURL(res.data as Blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
  return { truncated: res.headers['x-export-truncated'] === 'true' };
}
