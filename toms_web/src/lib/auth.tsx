import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { login as apiLogin, logout as apiLogout, setUnauthorizedListener, tokenStore } from './api';

interface AuthState {
  signedIn: boolean;
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [signedIn, setSignedIn] = useState(() => tokenStore.get() !== null);

  const signOut = useCallback(() => {
    apiLogout();
    setSignedIn(false);
  }, []);

  useEffect(() => {
    setUnauthorizedListener(() => setSignedIn(false));
    return () => setUnauthorizedListener(() => undefined);
  }, []);

  const signIn = useCallback(async (username: string, password: string) => {
    await apiLogin(username, password);
    setSignedIn(true);
  }, []);

  const value = useMemo(() => ({ signedIn, signIn, signOut }), [signedIn, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
