import React, { useState } from 'react';
import { ArrowRight, Check, ShieldCheck, Sparkles } from 'lucide-react';
import { BrandMark } from '../../components/layout/BrandMark';
import { Button } from '../../components/buttons/Button';
import { Field } from '../../components/common/Field';
import {
  useLoginMutation,
  useRegisterMutation,
  useForgotPasswordMutation,
  useResetPasswordMutation,
  useResendVerificationMutation,
} from '../../store/api';

export function AuthPage({ view, setView, onSignIn, showToast }) {
  const isRegister = view === 'register';
  const isForgot = view === 'forgot-password';
  const isReset = view === 'reset-password';
  const resetToken =
    typeof window !== 'undefined'
      ? new URLSearchParams(window.location.search).get('token') || ''
      : '';

  const [sent, setSent] = useState(false);
  const [resetComplete, setResetComplete] = useState(false);
  const [login, loginState] = useLoginMutation();
  const [register, registerState] = useRegisterMutation();
  const [forgotPassword, forgotState] = useForgotPasswordMutation();
  const [resetPassword, resetState] = useResetPasswordMutation();
  const [resendVerification, resendState] = useResendVerificationMutation();
  const [error, setError] = useState('');

  const isLoading =
    loginState.isLoading ||
    registerState.isLoading ||
    forgotState.isLoading ||
    resetState.isLoading;

  const submit = async (event) => {
    event.preventDefault();
    setError('');
    const fields = new FormData(event.currentTarget);
    const email = String(fields.get('email') || '').trim();

    try {
      if (isReset) {
        if (!resetToken) {
          throw new Error('This password reset link is missing its token. Please request a new reset email.');
        }
        if (fields.get('newPassword') !== fields.get('confirmPassword')) {
          throw new Error('The new passwords do not match.');
        }
        await resetPassword({
          token: resetToken,
          newPassword: fields.get('newPassword'),
        }).unwrap();
        setResetComplete(true);
      } else if (isForgot) {
        await forgotPassword({ email }).unwrap();
        setSent(true);
      } else if (isRegister) {
        await register({
          fullName: fields.get('fullName'),
          email,
          password: fields.get('password'),
        }).unwrap();
        sessionStorage.setItem('registrationEmail', email);
        setView('verify-email');
      } else {
        const session = await login({
          email,
          password: fields.get('password'),
        }).unwrap();
        localStorage.setItem('accessToken', session.accessToken);
        localStorage.setItem('refreshToken', session.refreshToken);
        onSignIn?.(session.user);
      }
    } catch (failure) {
      setError(
        failure?.message ||
          failure?.data?.detail ||
          failure?.data?.message ||
          'We could not complete that request. Please verify your details and try again.'
      );
    }
  };

  return (
    <main className="grid min-h-screen bg-white lg:grid-cols-[1.05fr_0.95fr]">
      {/* Left Branding Hero */}
      <section className="relative hidden overflow-hidden bg-ink p-12 text-white lg:flex lg:flex-col lg:justify-between xl:p-16">
        <div className="absolute -right-20 top-1/4 h-96 w-96 rounded-full border border-white/[0.08]" />
        <div className="absolute -right-4 top-[30%] h-72 w-72 rounded-full border border-white/[0.08]" />
        <BrandMark />
        <div className="relative max-w-lg">
          <div className="mb-5 inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/[0.06] px-3 py-1.5 text-[11px] font-medium text-slate-300">
            <Sparkles size={13} className="text-green-300" /> Your money, made clearer
          </div>
          <h1 className="text-5xl font-bold leading-[1.1] tracking-tight">
            Banking that<br />moves with you.
          </h1>
          <p className="mt-5 max-w-sm text-sm leading-6 text-slate-300">
            A calmer, clearer way to manage everyday money. Your whole financial life, in one place.
          </p>
          <div className="mt-10 flex items-center gap-3">
            <span className="grid h-10 w-10 place-items-center rounded-xl bg-green-400/10 text-green-300">
              <ShieldCheck size={18} />
            </span>
            <span className="text-xs text-slate-300">
              Secure by design. Simple by nature.
            </span>
          </div>
        </div>
        <p className="relative text-[10px] text-slate-500">
          © 2026 Eli Digital Bank · Banking made clear.
        </p>
      </section>

      {/* Right Form Area */}
      <section className="flex items-center justify-center p-5 sm:p-10">
        <div className="w-full max-w-[410px]">
          <div className="mb-8 flex items-center gap-3 lg:hidden">
            <BrandMark compact />
            <span className="text-sm font-bold text-ink">ELI DIGITAL BANK</span>
          </div>

          {view === 'verify-email' ? (
            <div className="text-center fade-in">
              <span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-green-50 text-green-600">
                <Check size={26} />
              </span>
              <h2 className="mt-5 text-2xl font-bold text-ink">Check your inbox</h2>
              <p className="mt-2 text-sm leading-6 text-slate-500">
                We sent a verification link to{' '}
                <span className="font-semibold text-ink">
                  {sessionStorage.getItem('registrationEmail') || 'your email address'}
                </span>
                . Please verify your email before signing in.
              </p>
              <Button className="mt-6 w-full" onClick={() => setView('login')}>
                Back to sign in
              </Button>
              <button
                className="mt-4 text-xs font-semibold text-gold-800 hover:text-gold-900 transition disabled:opacity-50"
                disabled={resendState.isLoading}
                onClick={async () => {
                  try {
                    await resendVerification({
                      email: sessionStorage.getItem('registrationEmail'),
                    }).unwrap();
                    showToast?.('A new verification email has been sent.', 'success');
                  } catch {
                    showToast?.('Could not resend the verification email.', 'error');
                  }
                }}
              >
                {resendState.isLoading ? 'Sending…' : 'Resend verification email'}
              </button>
            </div>
          ) : resetComplete ? (
            <div className="text-center fade-in">
              <span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-green-50 text-green-600">
                <Check size={26} />
              </span>
              <h2 className="mt-5 text-2xl font-bold text-ink">Password updated</h2>
              <p className="mt-2 text-sm leading-6 text-slate-500">
                Your password has been reset successfully. Sign in with your new password.
              </p>
              <Button className="mt-6 w-full" onClick={() => setView('login')}>
                Back to sign in
              </Button>
            </div>
          ) : sent ? (
            <div className="text-center fade-in">
              <span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-gold-50 text-gold-800">
                <Check size={26} />
              </span>
              <h2 className="mt-5 text-2xl font-bold text-ink">Check your email</h2>
              <p className="mt-2 text-sm leading-6 text-slate-500">
                If an account exists for that address, a secure password reset link has been dispatched.
              </p>
              <Button className="mt-6 w-full" onClick={() => setView('login')}>
                Back to sign in
              </Button>
            </div>
          ) : (
            <div className="fade-in">
              <div className="mb-8 hidden items-center gap-2 text-xs font-semibold text-slate-400 lg:flex">
                <BrandMark compact />
                <span>Secure customer sign in</span>
              </div>
              <p className="text-[11px] font-bold uppercase tracking-[0.15em] text-gold-800">
                {isRegister
                  ? 'GET STARTED'
                  : isForgot || isReset
                  ? 'ACCOUNT RECOVERY'
                  : 'WELCOME BACK'}
              </p>
              <h2 className="mt-2 text-[29px] font-bold tracking-tight text-ink">
                {isRegister
                  ? 'Create your account'
                  : isForgot
                  ? 'Reset your password'
                  : isReset
                  ? 'Choose a new password'
                  : 'Sign in to Eli'}
              </h2>
              <p className="mt-2 text-sm text-slate-500">
                {isRegister
                  ? 'A better banking experience starts here.'
                  : isForgot
                  ? 'We’ll email you a secure link to recover your account.'
                  : isReset
                  ? 'Choose a strong password with at least 8 characters.'
                  : 'Your money is right where you left it.'}
              </p>

              <form onSubmit={submit} className="mt-7 space-y-4">
                {isRegister && (
                  <Field
                    name="fullName"
                    label="Full name"
                    placeholder="Your legal full name"
                    autoComplete="name"
                    disabled={isLoading}
                    required
                  />
                )}
                {!isReset && (
                  <Field
                    name="email"
                    label="Email address"
                    type="email"
                    placeholder="you@example.com"
                    autoComplete="email"
                    disabled={isLoading}
                    required
                  />
                )}
                {isReset ? (
                  <>
                    <Field
                      name="newPassword"
                      label="New password"
                      type="password"
                      autoComplete="new-password"
                      minLength={8}
                      disabled={isLoading}
                      required
                    />
                    <Field
                      name="confirmPassword"
                      label="Confirm new password"
                      type="password"
                      autoComplete="new-password"
                      minLength={8}
                      disabled={isLoading}
                      required
                    />
                  </>
                ) : (
                  !isForgot && (
                    <Field
                      name="password"
                      label="Password"
                      type="password"
                      placeholder={isRegister ? 'At least 8 characters' : 'Enter your password'}
                      autoComplete={isRegister ? 'new-password' : 'current-password'}
                      disabled={isLoading}
                      required
                    />
                  )
                )}

                {!isRegister && !isForgot && !isReset && (
                  <div className="-mt-1 flex justify-end">
                    <button
                      type="button"
                      onClick={() => setView('forgot-password')}
                      className="text-[11px] font-semibold text-gold-800 hover:text-gold-900 transition"
                    >
                      Forgot password?
                    </button>
                  </div>
                )}

                {error && (
                  <p role="alert" className="rounded-xl bg-rose-50 p-3 text-xs text-rose-700 font-medium">
                    {error}
                  </p>
                )}

                <Button
                  type="submit"
                  disabled={isLoading}
                  isLoading={isLoading}
                  loadingText="Please wait…"
                  className="mt-1 w-full"
                >
                  {isRegister
                    ? 'Create account'
                    : isForgot
                    ? 'Send reset link'
                    : isReset
                    ? 'Reset password'
                    : 'Sign in'}{' '}
                  <ArrowRight size={15} />
                </Button>
              </form>

              <div className="my-6 flex items-center gap-3 text-[10px] text-slate-300">
                <span className="h-px flex-1 bg-slate-200" />
                SECURE, ENCRYPTED BANKING
                <span className="h-px flex-1 bg-slate-200" />
              </div>

              <p className="mt-6 text-center text-xs text-slate-500">
                {isRegister
                  ? 'Already banking with us?'
                  : isForgot
                  ? 'Remember your password?'
                  : 'New to Eli?'}{' '}
                <button
                  type="button"
                  onClick={() => setView(isRegister || isForgot ? 'login' : 'register')}
                  className="font-semibold text-gold-800 hover:text-gold-900 transition"
                >
                  {isRegister || isForgot ? 'Sign in' : 'Create an account'}
                </button>
              </p>
            </div>
          )}
        </div>
      </section>
    </main>
  );
}

export default AuthPage;
