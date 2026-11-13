import React, { useState } from 'react';
import { ArrowLeft, ArrowRight, Check, Landmark, Wallet } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Field } from '../../components/common/Field';

export function OpenAccountPage({ onCancel, onCreate, isLoading = false, error = '' }) {
  const [accountType, setAccountType] = useState('Savings');
  const [openingDeposit, setOpeningDeposit] = useState('1000');
  const [accountHolderName, setAccountHolderName] = useState('My savings');
  const [phone, setPhone] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    onCreate(
      accountType,
      Number(openingDeposit),
      accountHolderName.trim(),
      phone.trim()
    );
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Open a new account"
        description="Choose the account that works for your day-to-day."
        actions={
          <Button variant="secondary" onClick={onCancel} disabled={isLoading}>
            <ArrowLeft size={15} /> Back to accounts
          </Button>
        }
      />
      <div className="mx-auto grid max-w-4xl gap-5 lg:grid-cols-[1.1fr_0.9fr]">
        <Panel className="p-5 sm:p-7">
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              {['Savings', 'Current'].map((type) => (
                <button
                  type="button"
                  key={type}
                  onClick={() => {
                    setAccountType(type);
                    setAccountHolderName(type === 'Savings' ? 'My savings' : 'Everyday account');
                  }}
                  className={`rounded-2xl border p-4 text-left transition ${
                    accountType === type
                      ? 'border-brand bg-gold-50/70 ring-2 ring-gold-100'
                      : 'border-slate-200 hover:border-slate-300'
                  }`}
                >
                  <span
                    className={`mb-3 grid h-9 w-9 place-items-center rounded-xl ${
                      accountType === type
                        ? 'bg-white text-gold-800'
                        : 'bg-slate-100 text-slate-500'
                    }`}
                  >
                    {type === 'Savings' ? <Landmark size={17} /> : <Wallet size={17} />}
                  </span>
                  <span className="block text-sm font-bold text-ink">{type} account</span>
                  <span className="mt-1 block text-[11px] leading-5 text-slate-500">
                    {type === 'Savings'
                      ? 'Save towards your goals and earn interest.'
                      : 'Flexible everyday banking and business payments.'}
                  </span>
                </button>
              ))}
            </div>

            <Field
              name="accountHolderName"
              label="Account name"
              value={accountHolderName}
              onChange={(e) => setAccountHolderName(e.target.value)}
              disabled={isLoading}
              required
            />
            <Field
              name="phone"
              label="Phone number"
              type="tel"
              placeholder="08012345678"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              disabled={isLoading}
              required
            />
            <Field
              label="Opening deposit (NGN)"
              type="number"
              min="1000"
              step="500"
              value={openingDeposit}
              onChange={(e) => setOpeningDeposit(e.target.value)}
              disabled={isLoading}
              required
              hint="Your opening deposit will be added directly to your account balance."
            />

            {error && (
              <p role="alert" className="rounded-xl bg-rose-50 p-3 text-xs text-rose-700">
                {error}
              </p>
            )}

            <Button
              type="submit"
              className="w-full"
              disabled={isLoading || !phone}
              isLoading={isLoading}
              loadingText="Creating account…"
            >
              Open {accountType.toLowerCase()} account <ArrowRight size={15} />
            </Button>
          </form>
        </Panel>

        <Panel className="h-fit p-5">
          <h3 className="text-sm font-bold text-ink">Your new account includes</h3>
          <ul className="mt-4 space-y-3">
            {[
              'A unique 10-digit NUBAN account number',
              'Instant internal transfers & inter-bank payouts',
              'Protected real-time transaction history',
              'No monthly maintenance fees',
            ].map((item) => (
              <li key={item} className="flex gap-2 text-xs text-slate-600">
                <Check size={14} className="mt-0.5 text-green-600 shrink-0" />
                {item}
              </li>
            ))}
          </ul>
          <p className="mt-5 rounded-xl bg-slate-50 p-3 text-[10px] leading-5 text-slate-500">
            Your account request is submitted securely and authenticated with your session.
          </p>
        </Panel>
      </div>
    </div>
  );
}

export default OpenAccountPage;
