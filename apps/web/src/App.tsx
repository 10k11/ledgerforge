import { Loader2 } from 'lucide-react';
import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router';
import { useAuth } from '@/features/auth/AuthProvider';
import { AppLayout } from '@/layouts/AppLayout';
import { LoginPage, RegisterPage } from '@/pages/AuthPages';
import type { Role } from '@/types/api';

const OverviewPage = lazy(() => import('@/pages/OverviewPage'));
const WalletPage = lazy(() => import('@/pages/WalletPage'));
const TransfersPage = lazy(() => import('@/pages/TransfersPage'));
const PaymentsPage = lazy(() => import('@/pages/PaymentsPage'));
const PaymentDetailPage = lazy(() => import('@/pages/PaymentDetailPage'));
const TransactionsPage = lazy(() => import('@/pages/TransactionsPage'));
const TransactionDetailPage = lazy(() => import('@/pages/TransactionDetailPage'));
const MerchantsPage = lazy(() => import('@/pages/MerchantsPage'));
const DevelopersPage = lazy(() => import('@/pages/DevelopersPage'));
const SettingsPage = lazy(() => import('@/pages/SettingsPage'));
const OperationsPage = lazy(() => import('@/pages/OperationsPage'));
const NotFoundPage = lazy(() => import('@/pages/NotFoundPage'));

function FullPageSpinner() {
  return (
    <div className="grid min-h-dvh place-items-center" role="status" aria-label="Loading">
      <Loader2 className="size-6 animate-spin text-muted" />
    </div>
  );
}

function RequireAuth({ children, roles }: { children: React.ReactNode; roles?: Role[] }) {
  const { status, user } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <FullPageSpinner />;
  if (status === 'anonymous') return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  // UI guard for navigation only; the API enforces every authorization rule independently.
  if (roles && user && !roles.includes(user.role)) return <Navigate to="/" replace />;
  return <>{children}</>;
}

function GuestOnly({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  if (status === 'loading') return <FullPageSpinner />;
  if (status === 'authenticated') return <Navigate to="/" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <Suspense fallback={<FullPageSpinner />}>
      <Routes>
        <Route path="/login" element={<GuestOnly><LoginPage /></GuestOnly>} />
        <Route path="/register" element={<GuestOnly><RegisterPage /></GuestOnly>} />
        <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
          <Route index element={<OverviewPage />} />
          <Route path="wallet" element={<RequireAuth roles={['CUSTOMER']}><WalletPage /></RequireAuth>} />
          <Route path="transfers" element={<RequireAuth roles={['CUSTOMER']}><TransfersPage /></RequireAuth>} />
          <Route path="payments" element={<PaymentsPage />} />
          <Route path="payments/:paymentId" element={<PaymentDetailPage />} />
          <Route path="transactions" element={<RequireAuth roles={['CUSTOMER', 'MERCHANT']}><TransactionsPage /></RequireAuth>} />
          <Route path="transactions/:transactionId" element={<TransactionDetailPage />} />
          <Route path="merchants" element={<RequireAuth roles={['CUSTOMER', 'MERCHANT']}><MerchantsPage /></RequireAuth>} />
          <Route path="developers" element={<RequireAuth roles={['MERCHANT']}><DevelopersPage /></RequireAuth>} />
          <Route path="operations" element={<RequireAuth roles={['ADMIN']}><OperationsPage /></RequireAuth>} />
          <Route path="settings" element={<SettingsPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
