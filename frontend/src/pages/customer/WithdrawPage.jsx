import React, { useState } from 'react';
import { ArrowRight, BadgeCheck, Check, Clock3 } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Field } from '../../components/common/Field';
import { money } from '../../data';

export function WithdrawPage({
  accounts = [],
  withdrawal,
  setWithdrawal,
  onSubmit,
  bankOptions = [],
  onEnquiry,
  isLoading = false,
  error = '',
}) {
  const [enquiryLoading, setEnquiryLoading] = useState(false);
  const [enquiryError, setEnquiryError] = useState('');

  const verifyDestination = async () => {
    setEnquiryLoading(true);
    setEnquiryError('');
    try {
      const result = await onEnquiry?.();
      if (result?.accountName) {
        setWithdrawal((old) => ({
          ...old,
          verified: true,
          recipient: result.accountName,
          nameEnquiryRef: result.nameEnquiryRef || '',
        }));
      }
    } catch (err) {
      setEnquiryError(
        err?.data?.detail || err?.data?.message || err?.message || 'Could not verify recipient bank account.'
      );
      setWithdrawal((old) => ({ ...old, verified: false }));
    } finally {
      setEnquiryLoading(false);
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Withdraw to bank"
        description="Send money from your Eli account to an external verified bank account."
      />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.3fr)_minmax(260px,0.7fr)]">
        <Panel className="p-5 sm:p-7">
          <form onSubmit={onSubmit} className="space-y-4">
            <Field
              label="From account"
              as="select"
              value={withdrawal.accountId || accounts[0]?.id || ''}
              onChange={(e) => setWithdrawal((old) => ({ ...old, accountId: e.target.value }))}
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
              label="Destination bank"
              as="select"
              value={withdrawal.bankCode || ''}
              onChange={(e) => {
                const selected = bankOptions.find((bank) => bank.code === e.target.value);
                setWithdrawal((old) => ({
                  ...old,
                  bankCode: e.target.value,
                  bank: selected?.name || '',
                  verified: false,
                  nameEnquiryRef: '',
                }));
                setEnquiryError('');
              }}
              disabled={isLoading}
              required
            >
              <option value="">Select destination bank</option>
              {bankOptions.map((bank) => (
                <option key={bank.code} value={bank.code}>
                  {bank.name}
                </option>
              ))}
            </Field>

            <div className="grid gap-3 sm:grid-cols-[1fr_auto] sm:items-end">
              <Field
                label="Account number"
                inputMode="numeric"
                maxLength="10"
                placeholder="Enter 10-digit account number"
                value={withdrawal.accountNumber}
                onChange={(e) => {
                  setWithdrawal((old) => ({
                    ...old,
                    accountNumber: e.target.value.replace(/\D/g, ''),
                    verified: false,
                    nameEnquiryRef: '',
                  }));
                  setEnquiryError('');
                }}
                disabled={isLoading}
                required
              />
              <Button
                type="button"
                variant="secondary"
                className="h-[44px]"
                onClick={verifyDestination}
                disabled={
                  withdrawal.accountNumber?.length !== 10 ||
                  !withdrawal.bankCode ||
                  enquiryLoading ||
                  isLoading
                }
                isLoading={enquiryLoading}
                loadingText="Verifying…"
              >
                Verify account
              </Button>
            </div>

            {withdrawal.verified && (
              <div className="flex items-center gap-2 rounded-xl border border-green-100 bg-green-50 px-3.5 py-3 text-xs font-semibold text-green-800 fade-in">
                <BadgeCheck size={16} className="text-green-700 shrink-0" />
                <span>Verified account name: {withdrawal.recipient}</span>
              </div>
            )}

            {enquiryError && (
              <p role="alert" className="rounded-xl bg-rose-50 p-3 text-xs text-rose-700">
                {enquiryError}
              </p>
            )}

            <Field
              label="Amount (NGN)"
              type="number"
              min="100"
              step="100"
              placeholder="0.00"
              value={withdrawal.amount}
              onChange={(e) => setWithdrawal((old) => ({ ...old, amount: e.target.value }))}
              disabled={isLoading}
              required
              hint="Minimum withdrawal amount: ₦100.00"
            />

            {error && (
              <p role="alert" className="rounded-xl bg-rose-50 p-3 text-xs text-rose-700">
                {error}
              </p>
            )}

            <Button
              type="submit"
              className="mt-2 w-full"
              disabled={!withdrawal.verified || !withdrawal.amount || isLoading}
            >
              Review withdrawal <ArrowRight size={15} />
            </Button>
          </form>
        </Panel>

        <div className="space-y-4">
          <Panel className="p-5">
            <h3 className="text-sm font-bold text-ink">Before you withdraw</h3>
            <ul className="mt-4 space-y-3">
              {[
                'A valid recipient name verification is required',
                'You’ll authorize the request with your 4-digit PIN',
                'Payouts over thresholds undergo operations review',
              ].map((item) => (
                <li key={item} className="flex gap-2 text-xs text-slate-600">
                  <Check size={14} className="mt-0.5 shrink-0 text-green-600" />
                  {item}
                </li>
              ))}
            </ul>
          </Panel>

          <Panel className="p-5">
            <h3 className="text-sm font-bold text-ink">Payout timing</h3>
            <p className="mt-2 text-xs leading-5 text-slate-500">
              Most bank withdrawals arrive within minutes via NIP clearing. Payouts requiring manual operations review settle within 1 business day.
            </p>
            <div className="mt-4 flex items-center gap-2 text-[11px] font-medium text-slate-500">
              <Clock3 size={14} className="text-gold-800" /> Estimated: today
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}

export default WithdrawPage;
