import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, tokenStore } from '../lib/api';
import { AuthProvider, useAuth } from '../lib/auth';
import Login, { loginErrorMessage } from './Login';

function axiosError(status: number | null, headers: Record<string, string> = {}) {
  const err = new AxiosError('x');
  if (status !== null) {
    err.response = {
      status,
      headers: new AxiosHeaders(headers),
      data: {},
      statusText: '',
      config: { headers: new AxiosHeaders() },
    };
  }
  return err;
}

afterEach(() => vi.restoreAllMocks());

describe('loginErrorMessage', () => {
  it('explains wrong credentials, lockout, network and unknown failures', () => {
    expect(loginErrorMessage(axiosError(401))).toMatch(/Wrong username or password/);
    expect(loginErrorMessage(axiosError(429, { 'retry-after': '600' }))).toMatch(/10 minute/);
    expect(loginErrorMessage(axiosError(429))).toMatch(/Try again later/);
    expect(loginErrorMessage(axiosError(null))).toMatch(/Cannot reach the server/);
    expect(loginErrorMessage(axiosError(500))).toMatch(/failed/);
    expect(loginErrorMessage(new Error('boom'))).toMatch(/failed/);
  });
});

function Probe() {
  const { signedIn, signOut } = useAuth();
  return (
    <div>
      <span>{signedIn ? 'IN' : 'OUT'}</span>
      <button onClick={signOut}>out</button>
    </div>
  );
}

const renderLogin = () =>
  render(
    <AuthProvider>
      <Login />
      <Probe />
    </AuthProvider>,
  );

const fill = async (user: string, pass: string) => {
  await userEvent.type(screen.getByLabelText('Username'), user);
  await userEvent.type(screen.getByLabelText('Password'), pass);
};

describe('Login page', () => {
  it('keeps the button disabled until both fields are filled', async () => {
    renderLogin();
    const button = screen.getByRole('button', { name: /sign in/i });
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Username'), 'boss');
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Password'), 'pw');
    expect(button).toBeEnabled();
  });

  it('posts the trimmed username and password, keeps the token and signs in', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { token: 'jwt-9' } });
    renderLogin();
    await fill('  boss  ', 'secret');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => expect(screen.getByText('IN')).toBeInTheDocument());
    expect(post).toHaveBeenCalledWith('/admin/login', { username: 'boss', password: 'secret' });
    expect(tokenStore.get()).toBe('jwt-9');
  });

  it('shows the error and clears the password after a wrong login', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(axiosError(401));
    renderLogin();
    await fill('boss', 'wrong');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Wrong username or password/);
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.getByText('OUT')).toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
  });

  it('shows the lockout wait time', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(axiosError(429, { 'retry-after': '120' }));
    renderLogin();
    await fill('boss', 'x');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/2 minute/);
  });

  it('sends one request and disables the button while signing in, even if Enter is pressed twice', async () => {
    let finish!: (v: { data: { token: string } }) => void;
    const post = vi.spyOn(api, 'post').mockImplementation(() => new Promise((resolve) => (finish = resolve)));
    renderLogin();
    await fill('boss', 'pw');
    await userEvent.type(screen.getByLabelText('Password'), '{Enter}{Enter}');
    expect(post).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: /signing in/i })).toBeDisabled();
    await act(async () => finish({ data: { token: 't' } }));
    await waitFor(() => expect(screen.getByText('IN')).toBeInTheDocument());
  });
});

describe('auth provider', () => {
  it('starts signed in when a token is stored, and signOut clears it', async () => {
    tokenStore.set('existing');
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    expect(screen.getByText('IN')).toBeInTheDocument();
    await userEvent.click(screen.getByText('out'));
    expect(screen.getByText('OUT')).toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
  });

  it('drops to signed out when an API call reports an expired session', async () => {
    tokenStore.set('existing');
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    const handler = (api.interceptors.response as unknown as { handlers: { rejected: (e: unknown) => Promise<never> }[] })
      .handlers[0];
    await act(async () => {
      await handler.rejected({ config: { url: '/routes' }, response: { status: 401 } }).catch(() => undefined);
    });
    expect(screen.getByText('OUT')).toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
  });
});
