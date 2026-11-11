import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { LogOut, Monitor } from 'lucide-react';
import * as React from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router';
import { toast } from 'sonner';
import { z } from 'zod';
import { DefinitionList, PageHeader } from '@/components/shared';
import { Button } from '@/components/ui/button';
import { Field, Input } from '@/components/ui/form';
import { ConfirmDialog } from '@/components/ui/overlay';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Skeleton } from '@/components/ui/primitives';
import { useAuth } from '@/features/auth/AuthProvider';
import { useDocumentTitle } from '@/hooks';
import { errorMessage } from '@/lib/api';
import { formatDate, formatDateTime, relativeTime } from '@/lib/format';
import { applyServerErrors } from '@/pages/AuthPages';
import { authService } from '@/services';

const profileSchema = z.object({ fullName: z.string().trim().min(2, 'Enter your full name').max(120) });

const passwordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password'),
    newPassword: z
      .string()
      .min(10, 'Use at least 10 characters')
      .max(64)
      .regex(/[A-Za-z]/, 'Include a letter')
      .regex(/\d/, 'Include a number'),
    confirmPassword: z.string(),
  })
  .refine((v) => v.newPassword === v.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' });

function ProfileCard() {
  const { user, setUser } = useAuth();
  const { register, handleSubmit, formState: { errors, isDirty, isSubmitting }, reset } = useForm<z.infer<typeof profileSchema>>({
    resolver: zodResolver(profileSchema),
    defaultValues: { fullName: user?.fullName ?? '' },
  });
  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Profile</CardTitle>
          <CardDescription>Your name appears to people you transact with (abbreviated).</CardDescription>
        </div>
      </CardHeader>
      <CardContent>
        <form
          noValidate
          className="space-y-4"
          onSubmit={handleSubmit(async (v) => {
            try {
              const updated = await authService.updateProfile(v.fullName);
              setUser(updated);
              reset({ fullName: updated.fullName });
              toast.success('Profile updated');
            } catch (e) {
              toast.error('Could not update profile', { description: errorMessage(e) });
            }
          })}
        >
          <Field id="s-name" label="Full name" error={errors.fullName?.message}>
            <Input autoComplete="name" {...register('fullName')} />
          </Field>
          <Field id="s-email" label="Email" hint="Email changes are not supported in the sandbox.">
            <Input value={user?.email ?? ''} disabled readOnly />
          </Field>
          <Button type="submit" disabled={!isDirty} loading={isSubmitting}>
            Save profile
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

function PasswordCard() {
  const { register, handleSubmit, reset, setError, formState: { errors, isSubmitting } } = useForm<z.infer<typeof passwordSchema>>({
    resolver: zodResolver(passwordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  });
  const queryClient = useQueryClient();
  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Password</CardTitle>
          <CardDescription>Changing your password signs out every other session.</CardDescription>
        </div>
      </CardHeader>
      <CardContent>
        <form
          noValidate
          className="space-y-4"
          onSubmit={handleSubmit(async (v) => {
            try {
              await authService.changePassword(v.currentPassword, v.newPassword);
              reset();
              queryClient.invalidateQueries({ queryKey: ['sessions'] });
              toast.success('Password changed', { description: 'Other sessions were signed out.' });
            } catch (e) {
              const msg = applyServerErrors(e, setError, ['currentPassword', 'newPassword']);
              if (msg) setError('currentPassword', { message: msg });
            }
          })}
        >
          <Field id="s-current" label="Current password" error={errors.currentPassword?.message}>
            <Input type="password" autoComplete="current-password" {...register('currentPassword')} />
          </Field>
          <Field id="s-new" label="New password" error={errors.newPassword?.message} hint="10+ characters with a letter and a number.">
            <Input type="password" autoComplete="new-password" {...register('newPassword')} />
          </Field>
          <Field id="s-confirm" label="Confirm new password" error={errors.confirmPassword?.message}>
            <Input type="password" autoComplete="new-password" {...register('confirmPassword')} />
          </Field>
          <Button type="submit" loading={isSubmitting}>
            Change password
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

function SessionsCard() {
  const queryClient = useQueryClient();
  const { logout } = useAuth();
  const navigate = useNavigate();
  const sessions = useQuery({ queryKey: ['sessions'], queryFn: authService.sessions });
  const [allOpen, setAllOpen] = React.useState(false);
  const revoke = useMutation({
    mutationFn: authService.revokeSession,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sessions'] });
      toast.success('Session revoked');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const logoutAll = useMutation({
    mutationFn: authService.logoutAll,
    onSuccess: async () => {
      await logout();
      navigate('/login');
    },
  });
  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Active sessions</CardTitle>
          <CardDescription>Refresh tokens are rotated on every use and stored only as hashes.</CardDescription>
        </div>
      </CardHeader>
      <CardContent className="pt-3">
        {sessions.isPending ? (
          <Skeleton className="h-16 w-full" />
        ) : (
          <ul className="divide-y divide-line">
            {sessions.data?.map((s) => (
              <li key={s.id} className="flex items-center gap-3 py-3">
                <Monitor className="size-5 shrink-0 text-muted" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">
                    {s.userAgent ? s.userAgent.split(') ')[0]?.split(' (')[1] ?? 'Browser' : 'Unknown device'}{' '}
                    {s.current && <Badge tone="info" className="ml-1">This device</Badge>}
                  </p>
                  <p className="text-xs text-muted">
                    {s.ipAddress ?? 'unknown IP'} · active {relativeTime(s.lastUsedAt)} · expires {formatDate(s.expiresAt)}
                  </p>
                </div>
                {!s.current && (
                  <Button variant="ghost" size="sm" onClick={() => revoke.mutate(s.id)} disabled={revoke.isPending}>
                    Revoke
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
        <Button variant="outline" className="mt-3" onClick={() => setAllOpen(true)}>
          <LogOut /> Sign out everywhere
        </Button>
      </CardContent>
      <ConfirmDialog
        open={allOpen}
        onOpenChange={setAllOpen}
        title="Sign out of all sessions?"
        description="Every device, including this one, will need to sign in again."
        confirmLabel="Sign out everywhere"
        tone="danger"
        loading={logoutAll.isPending}
        onConfirm={() => logoutAll.mutate()}
      />
    </Card>
  );
}

export default function SettingsPage() {
  useDocumentTitle('Settings');
  const { user } = useAuth();
  return (
    <>
      <PageHeader title="Settings" description="Manage your profile, password and sessions." />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <div className="space-y-6">
          <ProfileCard />
          <Card>
            <CardHeader>
              <CardTitle>Account</CardTitle>
            </CardHeader>
            <CardContent className="pt-2">
              {user && (
                <DefinitionList
                  items={[
                    { label: 'Role', value: <Badge>{user.role.toLowerCase()}</Badge> },
                    { label: 'User ID', value: <span className="font-mono text-xs">{user.id}</span> },
                    { label: 'Currency', value: user.defaultCurrency },
                    ...(user.merchant ? [{ label: 'Business', value: user.merchant.businessName }] : []),
                    { label: 'Member since', value: formatDate(user.createdAt) },
                    { label: 'Last sign-in', value: user.lastLoginAt ? formatDateTime(user.lastLoginAt) : '—' },
                  ]}
                />
              )}
            </CardContent>
          </Card>
        </div>
        <div className="space-y-6">
          <PasswordCard />
          <SessionsCard />
        </div>
      </div>
    </>
  );
}
