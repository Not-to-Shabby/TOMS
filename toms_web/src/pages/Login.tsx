import { useState, type FormEvent } from 'react';
import { isAxiosError } from 'axios';
import { useAuth } from '../lib/auth';

export function loginErrorMessage(err: unknown): string {
  if (isAxiosError(err)) {
    if (err.response?.status === 401) return 'Wrong username or password.';
    if (err.response?.status === 429) {
      const wait = Number(err.response.headers['retry-after']);
      return wait > 0
        ? `Too many failed attempts. Try again in ${Math.ceil(wait / 60)} minute(s).`
        : 'Too many failed attempts. Try again later.';
    }
    if (!err.response) return 'Cannot reach the server. Check your connection.';
  }
  return 'Sign-in failed. Please try again.';
}

export default function Login() {
  const { signIn } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await signIn(username.trim(), password);
    } catch (err) {
      setError(loginErrorMessage(err));
      setPassword('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ display: 'grid', placeItems: 'center', height: '100vh' }}>
      <form onSubmit={submit} className="glass-panel" style={{ width: 360, padding: 32, display: 'grid', gap: 16 }}>
        <h1 style={{ fontSize: 24, fontWeight: 800, color: 'var(--accent)', letterSpacing: 2 }}>TOMS</h1>
        <label style={{ display: 'grid', gap: 6 }}>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus required />
        </label>
        <label style={{ display: 'grid', gap: 6 }}>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </label>
        {error && <div role="alert" style={{ color: 'var(--danger, #ff4757)' }}>{error}</div>}
        <button type="submit" disabled={busy || !username || !password}>
          {busy ? 'Signing in...' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
