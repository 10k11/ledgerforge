import { zodResolver } from '@hookform/resolvers/zod';
import { Building2, Eye, EyeOff, Scale, ShieldCheck, User, Workflow } from 'lucide-react';
import * as React from 'react';
import { Controller, useForm, type FieldValues, type Path, type UseFormSetError } from 'react-hook-form';
import { Link, useLocation, useNavigate } from 'react-router';
import { toast } from 'sonner';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Field, Input, NativeSelect } from '@/components/ui/form';
import { Checkbox } from '@/components/ui/primitives';
import { useAuth } from '@/features/auth/AuthProvider';
import { useDocumentTitle } from '@/hooks';
import { Logo, Wordmark } from '@/layouts/Wordmark';
import { ApiError } from '@/lib/api';
import { cn } from '@/lib/utils';

/** Maps server-side field errors (RFC 9457 `errors`) onto form fields; returns the leftover message. */
// eslint-disable-next-line react-refresh/only-export-components
export function applyServerErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly string[],
): string | null {
  if (!(error instanceof ApiError)) return 'Something went wrong. Please try again.';
  let unmatched = false;
  for (const fe of error.fieldErrors) {
    if (fields.includes(fe.field)) setError(fe.field as Path<T>, { message: fe.message });
    else unmatched = true;
  }
  return error.fieldErrors.length === 0 || unmatched ? error.detail : null;
}

function AuthShell({ children, title, subtitle }: { children: React.ReactNode; title: string; subtitle: string }) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-[minmax(0,1fr)_520px] xl:grid-cols-[minmax(0,1fr)_600px]">
      <aside className="relative hidden overflow-hidden bg-ink lg:flex lg:flex-col lg:justify-between lg:p-12">
        <div
          className="pointer-events-none absolute inset-0 opacity-[0.07]"
          style={{
            backgroundImage:
              'linear-gradient(#fff 1px, transparent 1px), linear-gradient(90deg, #fff 1px, transparent 1px)',
            backgroundSize: '40px 40px',
          }}
          aria-hidden
        />
        <div className="relative flex items-center gap-2.5">
          <Logo inverted className="size-8" />
          <span className="text-base font-semibold text-white">LedgerForge</span>
        </div>
        <div className="relative max-w-lg">
          <p className="text-[13px] font-medium uppercase tracking-[0.14em] text-blue-300">Payment infrastructure sandbox</p>
          <h2 className="mt-4 text-[34px] font-semibold leading-tight tracking-tight text-white">
            Financial transactions.
            <br />
            Engineered for correctness.
          </h2>
          <p className="mt-4 text-[15px] leading-relaxed text-slate-300">
            Wallets, transfers, merchant payments and refunds, all posted to a double-entry ledger with idempotent,
            concurrency-safe APIs and signed webhooks.
          </p>
          <div className="mt-10 overflow-hidden rounded-xl border border-white/10 bg-white/[0.04]">
            <div className="grid grid-cols-[1fr_auto_auto] gap-x-6 border-b border-white/10 px-4 py-2.5 text-[11px] font-medium uppercase tracking-wide text-slate-400">
              <span>Account</span>
              <span className="w-20 text-right">Debit</span>
              <span className="w-20 text-right">Credit</span>
            </div>
            {[
              ['Customer Wallet', '₹1,000.00', ''],
              ['Merchant Payable', '', '₹980.00'],
              ['Platform Fee Revenue', '', '₹20.00'],
            ].map(([a, d, c]) => (
              <div key={a} className="grid grid-cols-[1fr_auto_auto] gap-x-6 px-4 py-2.5 text-[13px] text-slate-200">
                <span>{a}</span>
                <span className="tabular w-20 text-right">{d || '—'}</span>
                <span className="tabular w-20 text-right">{c || '—'}</span>
              </div>
            ))}
            <div className="flex items-center gap-2 border-t border-white/10 px-4 py-2.5 text-[12px] text-emerald-300">
              <Scale className="size-3.5" aria-hidden /> Balanced: debits ₹1,000.00 = credits ₹1,000.00
            </div>
          </div>
        </div>
        <ul className="relative grid grid-cols-3 gap-6 text-[13px] text-slate-300">
          <li className="flex items-start gap-2">
            <Scale className="mt-0.5 size-4 shrink-0 text-blue-300" aria-hidden /> Double-entry ledger
          </li>
          <li className="flex items-start gap-2">
            <ShieldCheck className="mt-0.5 size-4 shrink-0 text-blue-300" aria-hidden /> Idempotent APIs
          </li>
          <li className="flex items-start gap-2">
            <Workflow className="mt-0.5 size-4 shrink-0 text-blue-300" aria-hidden /> Signed webhooks
          </li>
        </ul>
      </aside>
      <main className="flex flex-col bg-surface px-6 py-8 sm:px-12">
        <Wordmark className="lg:hidden" />
        <div className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center py-10">
          <h1 className="text-2xl font-semibold tracking-tight text-ink">{title}</h1>
          <p className="mt-1.5 text-sm text-muted">{subtitle}</p>
          <div className="mt-8">{children}</div>
        </div>
        <p className="text-center text-xs text-faint">
          Educational sandbox. Never enter real card or banking details.
        </p>
      </main>
    </div>
  );
}

function PasswordInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  const [visible, setVisible] = React.useState(false);
  return (
    <div className="relative">
      <Input {...props} type={visible ? 'text' : 'password'} className="pr-10" />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        className="absolute inset-y-0 right-0 grid w-10 place-items-center text-muted hover:text-ink"
        aria-label={visible ? 'Hide password' : 'Show password'}
        aria-pressed={visible}
      >
        {visible ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
      </button>
    </div>
  );
}

function FormAlert({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div role="alert" className="rounded-lg border border-red-200 bg-danger-soft px-3 py-2.5 text-[13px] text-danger-strong">
      {message}
    </div>
  );
}

// ---------------------------------------------------------------- Login

const loginSchema = z.object({
  email: z.email('Enter a valid email address'),
  password: z.string().min(1, 'Enter your password'),
});
type LoginValues = z.infer<typeof loginSchema>;

export function LoginPage() {
  useDocumentTitle('Sign in');
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [formError, setFormError] = React.useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      const user = await login(values.email, values.password);
      toast.success(`Welcome back, ${user.fullName.split(' ')[0]}`);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : '/', { replace: true });
    } catch (e) {
      setFormError(applyServerErrors(e, setError, ['email', 'password']));
    }
  });

  return (
    <AuthShell title="Welcome back" subtitle="Sign in to your LedgerForge workspace.">
      <form onSubmit={onSubmit} noValidate className="space-y-5">
        <FormAlert message={formError} />
        <Field id="email" label="Email" error={errors.email?.message}>
          <Input type="email" autoComplete="email" placeholder="you@company.com" {...register('email')} />
        </Field>
        <Field id="password" label="Password" error={errors.password?.message}>
          <PasswordInput autoComplete="current-password" placeholder="••••••••••" {...register('password')} />
        </Field>
        <Button type="submit" size="lg" className="w-full" loading={isSubmitting}>
          Sign in
        </Button>
      </form>
      <p className="mt-6 text-center text-sm text-muted">
        New to LedgerForge?{' '}
        <Link to="/register" className="font-medium text-primary hover:underline">
          Create an account
        </Link>
      </p>
    </AuthShell>
  );
}

// ---------------------------------------------------------------- Register

const registerSchema = z
  .object({
    accountType: z.enum(['CUSTOMER', 'MERCHANT']),
    fullName: z.string().trim().min(2, 'Enter your full name').max(120),
    email: z.email('Enter a valid email address').max(254),
    password: z
      .string()
      .min(10, 'Use at least 10 characters')
      .max(64, 'Use at most 64 characters')
      .regex(/[A-Za-z]/, 'Include at least one letter')
      .regex(/\d/, 'Include at least one number'),
    confirmPassword: z.string(),
    currency: z.enum(['INR', 'USD']),
    businessName: z.string().max(120).optional(),
    category: z.string().max(64).optional(),
    acceptTerms: z.boolean().refine((v) => v, 'You must accept the sandbox terms'),
  })
  .refine((v) => v.password === v.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' })
  .refine((v) => v.accountType !== 'MERCHANT' || (v.businessName?.trim().length ?? 0) >= 2, {
    path: ['businessName'],
    message: 'Enter your business name',
  });
type RegisterValues = z.infer<typeof registerSchema>;

export function RegisterPage() {
  useDocumentTitle('Create account');
  const { register: registerAccount } = useAuth();
  const navigate = useNavigate();
  const [formError, setFormError] = React.useState<string | null>(null);
  const {
    register,
    handleSubmit,
    control,
    watch,
    setValue,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: {
      accountType: 'CUSTOMER',
      fullName: '',
      email: '',
      password: '',
      confirmPassword: '',
      currency: 'INR',
      businessName: '',
      category: 'Retail',
      acceptTerms: false,
    },
  });
  const accountType = watch('accountType');

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await registerAccount({
        fullName: values.fullName,
        email: values.email,
        password: values.password,
        accountType: values.accountType,
        currency: values.currency,
        businessName: values.accountType === 'MERCHANT' ? values.businessName : undefined,
        category: values.accountType === 'MERCHANT' ? values.category : undefined,
        acceptTerms: values.acceptTerms,
      });
      toast.success('Your account is ready');
      navigate('/', { replace: true });
    } catch (e) {
      setFormError(
        applyServerErrors(e, setError, ['fullName', 'email', 'password', 'businessName', 'category', 'currency']),
      );
    }
  });

  return (
    <AuthShell title="Create your account" subtitle="Open a sandbox wallet or a merchant account in seconds.">
      <form onSubmit={onSubmit} noValidate className="space-y-5">
        <FormAlert message={formError} />
        <fieldset>
          <legend className="mb-1.5 text-[13px] font-medium text-ink-soft">Account type</legend>
          <div className="grid grid-cols-2 gap-2" role="radiogroup">
            {(
              [
                ['CUSTOMER', 'Personal', 'Wallet & transfers', User],
                ['MERCHANT', 'Business', 'Accept payments', Building2],
              ] as const
            ).map(([value, label, hint, Icon]) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={accountType === value}
                onClick={() => setValue('accountType', value, { shouldValidate: false })}
                className={cn(
                  'flex items-start gap-2.5 rounded-xl border p-3 text-left transition-colors',
                  accountType === value
                    ? 'border-primary bg-primary-soft ring-1 ring-primary'
                    : 'border-line-strong hover:bg-subtle',
                )}
              >
                <Icon className={cn('mt-0.5 size-4', accountType === value ? 'text-primary' : 'text-muted')} aria-hidden />
                <span>
                  <span className="block text-sm font-medium text-ink">{label}</span>
                  <span className="block text-xs text-muted">{hint}</span>
                </span>
              </button>
            ))}
          </div>
        </fieldset>
        <Field id="fullName" label="Full name" error={errors.fullName?.message}>
          <Input autoComplete="name" placeholder="Asha Verma" {...register('fullName')} />
        </Field>
        {accountType === 'MERCHANT' && (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-[1fr_140px]">
            <Field id="businessName" label="Business name" error={errors.businessName?.message}>
              <Input autoComplete="organization" placeholder="Chai & Co" {...register('businessName')} />
            </Field>
            <Field id="category" label="Category" error={errors.category?.message}>
              <NativeSelect {...register('category')}>
                {['Retail', 'Food & Beverage', 'Digital goods', 'Services', 'Education', 'Travel'].map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </NativeSelect>
            </Field>
          </div>
        )}
        <Field id="email" label="Email" error={errors.email?.message}>
          <Input type="email" autoComplete="email" placeholder="you@company.com" {...register('email')} />
        </Field>
        <Field id="currency" label="Account currency" hint="Balances and payments use this currency.">
          <NativeSelect {...register('currency')}>
            <option value="INR">INR · Indian rupee</option>
            <option value="USD">USD · US dollar</option>
          </NativeSelect>
        </Field>
        <Field
          id="password"
          label="Password"
          error={errors.password?.message}
          hint="10+ characters with at least one letter and one number."
        >
          <PasswordInput autoComplete="new-password" {...register('password')} />
        </Field>
        <Field id="confirmPassword" label="Confirm password" error={errors.confirmPassword?.message}>
          <PasswordInput autoComplete="new-password" {...register('confirmPassword')} />
        </Field>
        <div className="space-y-1.5">
          <div className="flex items-start gap-2.5">
            <Controller
              control={control}
              name="acceptTerms"
              render={({ field }) => (
                <Checkbox
                  id="acceptTerms"
                  className="mt-0.5"
                  checked={field.value}
                  onCheckedChange={(v) => field.onChange(v === true)}
                  aria-invalid={!!errors.acceptTerms}
                  aria-describedby={errors.acceptTerms ? 'acceptTerms-error' : undefined}
                />
              )}
            />
            <label htmlFor="acceptTerms" className="text-[13px] leading-snug text-ink-soft">
              I understand LedgerForge is a sandbox: balances are simulated and no real money moves.
            </label>
          </div>
          {errors.acceptTerms && (
            <p id="acceptTerms-error" role="alert" className="text-[13px] text-danger-strong">
              {errors.acceptTerms.message}
            </p>
          )}
        </div>
        <Button type="submit" size="lg" className="w-full" loading={isSubmitting}>
          Create account
        </Button>
      </form>
      <p className="mt-6 text-center text-sm text-muted">
        Already have an account?{' '}
        <Link to="/login" className="font-medium text-primary hover:underline">
          Sign in
        </Link>
      </p>
    </AuthShell>
  );
}
