import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { Toaster } from 'sonner';
import App from '@/App';
import { TooltipProvider } from '@/components/ui/primitives';
import { AuthProvider } from '@/features/auth/AuthProvider';
import { ApiError } from '@/lib/api';
import '@/styles/index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: true,
      retry: (count, error) => {
        // Never retry client errors (403/404/422...); retry transient failures twice.
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
        return count < 2;
      },
    },
    mutations: { retry: false },
  },
  queryCache: new QueryCache(),
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <TooltipProvider>
            <App />
            <Toaster
              position="top-right"
              toastOptions={{
                classNames: {
                  toast: 'rounded-xl border border-line bg-surface text-ink shadow-pop font-sans',
                  description: 'text-muted',
                },
              }}
            />
          </TooltipProvider>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
