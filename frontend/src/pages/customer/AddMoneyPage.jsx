import React from 'react';
import { ArrowRight, CreditCard, LockKeyhole, Shield, ShieldCheck } from 'lucide-react';
import { Elements } from '@stripe/react-stripe-js';
import { loadStripe } from '@stripe/stripe-js';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Field } from '../../components/common/Field';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import { StripePaymentForm } from '../../components/payments/StripePaymentForm';
import { useDepositQuery } from '../../store/api';
import { money } from '../../data';

const stripePromise = import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY
  ? loadStripe(import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY)
  : null;

export function AddMoneyPage({
  accounts = [],
  deposit,
  setDeposit,
  onSubmit,
  activeDeposit,
  onClearDeposit,
  isLoading = false,
  error = '',
}) {
  const { data: latestDeposit } = useDepositQuery(activeDeposit?.depositId, {
    skip: !activeDeposit?.depositId,
    pollingInterval: 3000,
  });

  return (
    <div className="fade-in">
      <PageHeader
        title="Add money"
        description="Top up your account securely with a debit or credit card."
      />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.3fr)_minmax(260px,0.7fr)]">
        <Panel className="p-5 sm:p-7">
          {!activeDeposit && (
            <form onSubmit={onSubmit} className="space-y-5">
              <Field
                label="Credit to"
                as="select"
                value={deposit.accountId || accounts[0]?.id || ''}
                onChange={(e) => setDeposit((old) => ({ ...old, accountId: e.target.value }))}
                disabled={isLoading}
                required
              >
                {accounts.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name} · •••• {account.lastFour} · {money(account.balance)}
                  </option>
                ))}
              </Field>

              <Field
                label="Amount (NGN)"
                type="number"
                min="1000"
                max="5000000"
                step="500"
                placeholder="50,000.00"
                value={deposit.amount}
                onChange={(e) => setDeposit((old) => ({ ...old, amount: e.target.value }))}
                disabled={isLoading}
                required
                hint="Minimum card deposit ₦1,000.00 · Maximum ₦5,000,000.00"
              />

              <div>
                <p className="mb-2 text-[13px] font-semibold text-slate-700">Quick amounts</p>
                <div className="grid grid-cols-4 gap-2">
                  {['₦5,000', '₦10,000', '₦20,000', '₦50,000'].map((amount) => (
                    <button
                      key={amount}
                      type="button"
                      disabled={isLoading}
                      onClick={() =>
                        setDeposit((old) => ({
                          ...old,
                          amount: amount.replace(/[₦,]/g, ''),
                        }))
                      }
                      className="rounded-xl border border-slate-200 px-2 py-2.5 text-xs font-semibold text-slate-600 transition hover:border-brand hover:bg-gold-50 hover:text-gold-800 disabled:opacity-50"
                    >
                      {amount}
                    </button>
                  ))}
                </div>
              </div>

              <div className="flex items-center gap-3 rounded-xl border border-gold-100 bg-gold-50/80 p-3.5">
                <span className="grid h-9 w-9 place-items-center rounded-lg bg-white text-gold-800 shadow-sm">
                  <CreditCard size={17} />
                </span>
                <div>
                  <p className="text-xs font-bold text-ink">Secure card checkout</p>
                  <p className="mt-0.5 text-[10px] text-slate-500">
                    Payment is handled through PCI-DSS Level 1 compliant infrastructure.
                  </p>
                </div>
                <ShieldCheck size={16} className="ml-auto text-green-600 shrink-0" />
              </div>

              {error && (
                <p role="alert" className="rounded-xl bg-rose-50 p-3 text-xs text-rose-700">
                  {error}
                </p>
              )}

              <Button
                type="submit"
                className="w-full"
                disabled={isLoading || !deposit.amount}
                isLoading={isLoading}
                loadingText="Initiating checkout…"
              >
                Continue to secure checkout <ArrowRight size={15} />
              </Button>
            </form>
          )}

          {activeDeposit && (
            <div className="rounded-xl border border-slate-200 p-5 fade-in">
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-sm font-bold text-ink">Complete your card payment</h2>
                  <p className="text-xs text-slate-400 mt-0.5">
                    Deposit ref: <span className="font-mono text-slate-600">{activeDeposit.depositId}</span>
                  </p>
                </div>
                <StatusBadge>{latestDeposit?.status || activeDeposit.status}</StatusBadge>
              </div>

              <p className="mt-3 text-xs leading-5 text-slate-500">
                The balance updates automatically once the payment provider confirms the settlement.
              </p>

              {latestDeposit?.status === 'PENDING' && activeDeposit.clientSecret && stripePromise && (
                <Elements
                  stripe={stripePromise}
                  options={{ clientSecret: activeDeposit.clientSecret }}
                >
                  <StripePaymentForm onPaid={() => {}} />
                </Elements>
              )}

              {latestDeposit?.status === 'COMPLETED' && (
                <div className="mt-4 rounded-xl bg-green-50 p-4 border border-green-200 text-xs font-semibold text-green-800">
                  Payment confirmed! Your account has been credited.
                </div>
              )}

              {!stripePromise && (
                <p role="alert" className="mt-3 rounded-xl bg-amber-50 p-3 text-xs text-amber-800">
                  Card checkout requires <code>VITE_STRIPE_PUBLISHABLE_KEY</code> in the frontend environment.
                </p>
              )}

              <button
                type="button"
                className="mt-4 text-xs font-semibold text-gold-800 hover:text-gold-900 transition"
                onClick={onClearDeposit}
              >
                Start another deposit
              </button>
            </div>
          )}
        </Panel>

        <div className="space-y-4">
          <Panel className="p-5">
            <p className="text-xs font-semibold text-slate-500">Your deposit</p>
            <p className="mt-2 text-[26px] font-bold tracking-tight text-ink">
              {money(Number(deposit.amount) || 0)}
            </p>
            <p className="mt-1 text-[11px] text-slate-400">
              Estimated arrival: usually instant
            </p>
          </Panel>

          <Panel className="p-5">
            <div className="flex items-center gap-2">
              <Shield size={16} className="text-green-600" />
              <h3 className="text-sm font-bold text-ink">Protected by design</h3>
            </div>
            <p className="mt-3 text-xs leading-5 text-slate-500">
              Card details are tokenized and processed by certified processors. Eli‑Pay never stores your full card number or CVV.
            </p>
            <div className="mt-4 flex items-center gap-2 text-[10px] font-medium text-slate-400">
              <LockKeyhole size={13} /> 256-bit SSL encrypted & secure
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}

export default AddMoneyPage;
