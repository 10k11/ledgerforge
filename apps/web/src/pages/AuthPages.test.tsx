import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/api';
import { makeAuth, makeUser, renderWithProviders } from '@/test/utils';
import { LoginPage, RegisterPage } from './AuthPages';

describe('LoginPage', () => {
  it('validates fields before calling the API', async () => {
    const auth = makeAuth(null);
    renderWithProviders(<LoginPage />, { auth });
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument();
    expect(screen.getByText('Enter your password')).toBeInTheDocument();
    expect(auth.login).not.toHaveBeenCalled();
  });

  it('submits credentials and shows server errors', async () => {
    const login = vi.fn().mockRejectedValue(
      new ApiError(401, { code: 'INVALID_CREDENTIALS', detail: 'Email or password is incorrect' }, 'x'),
    );
    renderWithProviders(<LoginPage />, { auth: makeAuth(null, { login }) });
    await userEvent.type(screen.getByLabelText('Email'), 'asha@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'Wrong-pass-1');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(login).toHaveBeenCalledWith('asha@example.com', 'Wrong-pass-1');
    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect');
  });

  it('toggles password visibility', async () => {
    renderWithProviders(<LoginPage />, { auth: makeAuth(null) });
    const input = screen.getByLabelText('Password');
    expect(input).toHaveAttribute('type', 'password');
    await userEvent.click(screen.getByRole('button', { name: 'Show password' }));
    expect(input).toHaveAttribute('type', 'text');
  });
});

describe('RegisterPage', () => {
  it('requires matching passwords, terms, and a business name for merchants', async () => {
    const register = vi.fn();
    renderWithProviders(<RegisterPage />, { auth: makeAuth(null, { register }) });
    await userEvent.click(screen.getByRole('radio', { name: /Business/ }));
    await userEvent.type(screen.getByLabelText('Full name'), 'Meera Iyer');
    await userEvent.type(screen.getByLabelText('Email'), 'meera@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'Strong-pass-42');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'Different-42x');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
    expect(screen.getByText('Enter your business name')).toBeInTheDocument();
    expect(screen.getByText('You must accept the sandbox terms')).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  it('registers a customer and maps server field errors', async () => {
    const register = vi.fn().mockRejectedValue(
      new ApiError(400, { code: 'VALIDATION_FAILED', detail: 'Password does not meet requirements', errors: [{ field: 'password', message: 'is too common' }] }, 'x'),
    );
    renderWithProviders(<RegisterPage />, { auth: makeAuth(null, { register }) });
    await userEvent.type(screen.getByLabelText('Full name'), 'Asha Verma');
    await userEvent.type(screen.getByLabelText('Email'), 'asha@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'password1234');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'password1234');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    await waitFor(() => expect(register).toHaveBeenCalled());
    expect(register.mock.calls[0][0]).toMatchObject({ accountType: 'CUSTOMER', currency: 'INR', acceptTerms: true });
    expect(register.mock.calls[0][0].businessName).toBeUndefined();
    expect(await screen.findByText('is too common')).toBeInTheDocument();
    expect(makeUser().role).toBe('CUSTOMER');
  });
});
