import { useQueryClient } from '@tanstack/react-query';
import * as React from 'react';
import { logoutRequest, refreshSession, setAccessToken, subscribeSession } from '@/lib/api';
import { authService, type RegisterInput } from '@/services';
import type { AuthResponse, Role, UserProfile } from '@/types/api';

type Status = 'loading' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  status: Status;
  user: UserProfile | null;
  login: (email: string, password: string) => Promise<UserProfile>;
  register: (input: RegisterInput) => Promise<UserProfile>;
  logout: () => Promise<void>;
  setUser: (user: UserProfile) => void;
  hasRole: (...roles: Role[]) => boolean;
}

const AuthContext = React.createContext<AuthContextValue | null>(null);

/**
 * Holds the signed-in user. On load it restores the session by rotating the HttpOnly refresh
 * cookie; the resulting access token is kept in memory only (see lib/api.ts).
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = React.useState<Status>('loading');
  const [user, setUserState] = React.useState<UserProfile | null>(null);

  const adopt = React.useCallback((session: AuthResponse | null) => {
    if (session) {
      setAccessToken(session.accessToken);
      setUserState(session.user);
      setStatus('authenticated');
    } else {
      setAccessToken(null);
      setUserState(null);
      setStatus('anonymous');
    }
  }, []);

  React.useEffect(() => {
    const unsubscribe = subscribeSession((session) => {
      if (!session) queryClient.clear();
      adopt(session);
    });
    let cancelled = false;
    refreshSession().then((session) => {
      if (!cancelled) adopt(session);
    });
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [adopt, queryClient]);

  const value = React.useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      login: async (email, password) => {
        const session = await authService.login(email, password);
        queryClient.clear();
        adopt(session);
        return session.user;
      },
      register: async (input) => {
        const session = await authService.register(input);
        queryClient.clear();
        adopt(session);
        return session.user;
      },
      logout: async () => {
        try {
          await logoutRequest();
        } finally {
          queryClient.clear();
          adopt(null);
        }
      },
      setUser: (u) => setUserState(u),
      hasRole: (...roles) => !!user && roles.includes(user.role),
    }),
    [status, user, adopt, queryClient],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = React.useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

/** Test helper: provide a fixed auth state without network calls. */
export function StaticAuthProvider({ value, children }: { value: AuthContextValue; children: React.ReactNode }) {
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export type { AuthContextValue };
