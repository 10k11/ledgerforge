import * as LabelPrimitive from '@radix-ui/react-label';
import * as React from 'react';
import { cn } from '@/lib/utils';

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'h-10 w-full rounded-lg border border-line-strong bg-surface px-3 text-sm text-ink shadow-[0_1px_0_rgb(23_32_51/0.02)] transition-colors placeholder:text-faint hover:border-faint focus-visible:border-primary focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-primary/15 disabled:cursor-not-allowed disabled:bg-subtle aria-[invalid=true]:border-danger aria-[invalid=true]:ring-danger/10',
        className,
      )}
      {...props}
    />
  ),
);
Input.displayName = 'Input';

export const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) => (
    <textarea
      ref={ref}
      className={cn(
        'min-h-20 w-full rounded-lg border border-line-strong bg-surface px-3 py-2 text-sm text-ink placeholder:text-faint focus-visible:border-primary focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-primary/15',
        className,
      )}
      {...props}
    />
  ),
);
Textarea.displayName = 'Textarea';

export const Label = React.forwardRef<
  React.ElementRef<typeof LabelPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof LabelPrimitive.Root>
>(({ className, ...props }, ref) => (
  <LabelPrimitive.Root ref={ref} className={cn('text-[13px] font-medium text-ink-soft', className)} {...props} />
));
Label.displayName = 'Label';

interface FieldProps {
  id: string;
  label: string;
  error?: string;
  hint?: React.ReactNode;
  children: React.ReactElement<{ id?: string; 'aria-invalid'?: boolean; 'aria-describedby'?: string }>;
  className?: string;
  trailing?: React.ReactNode;
}

/** Label + control + hint/error, wired for screen readers (aria-invalid, aria-describedby). */
export function Field({ id, label, error, hint, children, className, trailing }: FieldProps) {
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className={cn('space-y-1.5', className)}>
      <div className="flex items-center justify-between">
        <Label htmlFor={id}>{label}</Label>
        {trailing}
      </div>
      {React.cloneElement(children, { id, 'aria-invalid': !!error, 'aria-describedby': describedBy })}
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-[13px] text-danger-strong">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="text-[13px] text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

/** Amount input with a currency prefix; value is a decimal string parsed by parseMoneyInput. */
export const MoneyInput = React.forwardRef<
  HTMLInputElement,
  React.InputHTMLAttributes<HTMLInputElement> & { symbol: string }
>(({ symbol, className, ...props }, ref) => (
  <div className="relative">
    <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-muted">{symbol}</span>
    <Input ref={ref} inputMode="decimal" autoComplete="off" className={cn('pl-8 tabular', className)} {...props} />
  </div>
));
MoneyInput.displayName = 'MoneyInput';

export function NativeSelect({ className, ...props }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      className={cn(
        'h-10 w-full appearance-none rounded-lg border border-line-strong bg-surface bg-[url("data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A//www.w3.org/2000/svg%22%20viewBox%3D%220%200%2020%2020%22%20fill%3D%22%2364748b%22%3E%3Cpath%20d%3D%22M5.3%207.3a1%201%200%20011.4%200L10%2010.6l3.3-3.3a1%201%200%20111.4%201.4l-4%204a1%201%200%2001-1.4%200l-4-4a1%201%200%20010-1.4z%22/%3E%3C/svg%3E")] bg-[length:16px] bg-[right_10px_center] bg-no-repeat pl-3 pr-9 text-sm text-ink focus-visible:border-primary focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-primary/15',
        className,
      )}
      {...props}
    />
  );
}
