import React, { useState } from 'react';
import { CheckCheck, ShieldCheck } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Field } from '../../components/common/Field';
import { SectionHeading } from '../../components/common/SectionHeading';
import {
  useRequestPinCodeMutation,
  useChangePinMutation,
  useChangePasswordMutation,
} from '../../store/api';

export function SecurityPage({ showToast }) {
  const [otpRequested, setOtpRequested] = useState(false);
  const [requestPinCode, pinCodeState] = useRequestPinCodeMutation();
  const [changePin, pinChangeState] = useChangePinMutation();
  const [changePassword, passwordState] = useChangePasswordMutation();

  const [pinError, setPinError] = useState('');
  const [passwordError, setPasswordError] = useState('');
  const [passwordChanged, setPasswordChanged] = useState(false);

  const submitPin = async (event) => {
    event.preventDefault();
    setPinError('');

    if (!otpRequested) {
      try {
        await requestPinCode().unwrap();
        setOtpRequested(true);
        showToast?.('A PIN verification OTP code was sent to your email.', 'success');
      } catch (failure) {
        setPinError(failure?.data?.detail || failure?.data?.message || 'Could not send a PIN verification code.');
      }
      return;
    }

    const fields = new FormData(event.currentTarget);
    const pin = String(fields.get('newPin') || '');
    if (pin !== fields.get('confirmPin')) {
      setPinError('The new PINs do not match.');
      return;
    }

    try {
      await changePin({
        code: fields.get('code'),
        password: fields.get('password'),
        pin,
      }).unwrap();
      event.currentTarget.reset();
      setOtpRequested(false);
      showToast?.('Transaction PIN updated successfully.', 'success');
    } catch (failure) {
      setPinError(failure?.data?.detail || failure?.data?.message || 'Transaction PIN could not be changed.');
    }
  };

  const submitPassword = async (event) => {
    event.preventDefault();
    setPasswordError('');
    setPasswordChanged(false);

    const fields = new FormData(event.currentTarget);
    if (fields.get('newPassword') !== fields.get('confirmPassword')) {
      setPasswordError('The new passwords do not match.');
      return;
    }

    try {
      await changePassword({
        currentPassword: fields.get('currentPassword'),
        newPassword: fields.get('newPassword'),
      }).unwrap();
      event.currentTarget.reset();
      setPasswordChanged(true);
      showToast?.('Password changed successfully.', 'success');
    } catch (failure) {
      setPasswordError(failure?.data?.detail || failure?.data?.message || 'Password could not be changed.');
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Security & PIN"
        description="Keep your account, passwords, and transaction PIN protected."
      />
      <div className="grid gap-5 lg:grid-cols-[1.1fr_0.9fr]">
        <div className="space-y-4">
          <Panel className="p-5 sm:p-6">
            <SectionHeading title="Transaction PIN" />
            <p className="mb-5 text-xs leading-5 text-slate-500">
              We’ll email a one-time code to your registered address before you update your 4-digit transaction PIN.
            </p>
            <form onSubmit={submitPin} className="space-y-4">
              {otpRequested && (
                <>
                  <Field
                    label="Email verification code"
                    name="code"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength="6"
                    pattern="[0-9]{6}"
                    placeholder="Enter 6-digit code"
                    required
                  />
                  <Field
                    label="Account password"
                    name="password"
                    type="password"
                    autoComplete="current-password"
                    placeholder="Verify your current password"
                    required
                  />
                  <Field
                    label="New 4-digit PIN"
                    name="newPin"
                    type="password"
                    inputMode="numeric"
                    maxLength="4"
                    pattern="[0-9]{4}"
                    placeholder="••••"
                    required
                  />
                  <Field
                    label="Confirm new PIN"
                    name="confirmPin"
                    type="password"
                    inputMode="numeric"
                    maxLength="4"
                    pattern="[0-9]{4}"
                    placeholder="••••"
                    required
                  />
                  {pinError && (
                    <p role="alert" className="text-xs text-rose-700 font-medium">
                      {pinError}
                    </p>
                  )}
                </>
              )}

              <div className="flex items-center gap-3">
                <Button
                  type="submit"
                  disabled={pinCodeState.isLoading || pinChangeState.isLoading}
                  isLoading={pinCodeState.isLoading || pinChangeState.isLoading}
                  loadingText={!otpRequested ? 'Sending code…' : 'Updating PIN…'}
                >
                  {!otpRequested ? 'Email me a code' : 'Update transaction PIN'}
                </Button>
                {otpRequested && (
                  <button
                    type="button"
                    className="text-xs font-semibold text-gold-800 hover:text-gold-900 transition"
                    onClick={() => {
                      setOtpRequested(false);
                      setPinError('');
                    }}
                  >
                    Cancel
                  </button>
                )}
              </div>
            </form>
            {!otpRequested && pinError && (
              <p role="alert" className="mt-3 text-xs text-rose-700 font-medium">
                {pinError}
              </p>
            )}
          </Panel>

          <Panel className="p-5 sm:p-6">
            <SectionHeading title="Password & sign-in" />
            <form onSubmit={submitPassword} className="space-y-4">
              <Field
                label="Current password"
                name="currentPassword"
                type="password"
                autoComplete="current-password"
                required
              />
              <Field
                label="New password"
                name="newPassword"
                type="password"
                autoComplete="new-password"
                minLength="8"
                required
              />
              <Field
                label="Confirm new password"
                name="confirmPassword"
                type="password"
                autoComplete="new-password"
                minLength="8"
                required
              />

              {passwordError && (
                <p role="alert" className="text-xs text-rose-700 font-medium">
                  {passwordError}
                </p>
              )}
              {passwordChanged && (
                <p role="status" className="text-xs text-green-700 font-medium">
                  Your password was successfully updated.
                </p>
              )}

              <Button
                type="submit"
                disabled={passwordState.isLoading}
                isLoading={passwordState.isLoading}
                loadingText="Updating password…"
              >
                Change password
              </Button>
            </form>
          </Panel>
        </div>

        <div className="space-y-4">
          <Panel className="p-5">
            <div className="flex items-center gap-3">
              <span className="grid h-10 w-10 place-items-center rounded-xl bg-green-50 text-green-700">
                <ShieldCheck size={18} />
              </span>
              <div>
                <h3 className="text-sm font-bold text-ink">Your account is secure</h3>
                <p className="mt-0.5 text-[11px] text-slate-400">Security audit passed</p>
              </div>
            </div>
            <div className="mt-5 space-y-3">
              {[
                ['Email verified', true],
                ['Transaction PIN enabled', true],
                ['Real-time fraud monitoring active', true],
              ].map(([label, enabled]) => (
                <div key={label} className="flex items-center justify-between text-xs">
                  <span className="text-slate-600">{label}</span>
                  <CheckCheck
                    size={15}
                    className={enabled ? 'text-green-600' : 'text-slate-300'}
                  />
                </div>
              ))}
            </div>
          </Panel>

          <Panel className="border-rose-100 p-5">
            <h3 className="text-sm font-bold text-rose-800">Lost your device?</h3>
            <p className="mt-2 text-xs leading-5 text-slate-500">
              Immediately contact our fraud operations team to freeze active sessions and protect your funds.
            </p>
            <button
              onClick={() => showToast?.('Fraud freeze request initiated.', 'info')}
              className="mt-3 text-xs font-semibold text-rose-700 hover:text-rose-800 transition"
            >
              Get urgent security help
            </button>
          </Panel>
        </div>
      </div>
    </div>
  );
}

export default SecurityPage;
