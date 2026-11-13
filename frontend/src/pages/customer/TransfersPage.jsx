import React, { useState } from 'react';
import { ArrowRight, BadgeCheck, Check, Clock3, ShieldCheck } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Field } from '../../components/common/Field';
import { money } from '../../data';

export function TransfersPage({
  accounts = [],
  transfer,
  setTransfer,
  onSubmit,
  onEnquiry,
  bankOptions = [],
}) {
  const [enquiryDone, setEnquiryDone] = useState(Boolean(transfer.recipientName));
  const [enquiryLoading, setEnquiryLoading] = useState(false);
  const [enquiryError, setEnquiryError] = useState('');

  const update = (key, value) => {
    setTransfer((old) => ({ ...old, [key]: value }));
  };

  const handleEnquiry = async () => {
    setEnquiryLoading(true);
    setEnquiryError('');
    try {
      const result = await onEnquiry?.();
      if (result?.accountName) {
        setTransfer((old) => ({
          ...old,
          recipientName: result.accountName,
          nameEnquiryRef: result.nameEnquiryRef || '',
        }));
        setEnquiryDone(true);
      }
    } catch (err) {
      setEnquiryError(
        err?.data?.detail || err?.data?.message || err?.message || 'Could not verify recipient account. Check details.'
      );
      setEnquiryDone(false);
    } finally {
      setEnquiryLoading(false);
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Send money"
        description="Move money to another Eli customer or to an external bank account."
      />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.35fr)_minmax(260px,0.65fr)]">
        <Panel className="p-5 sm:p-7">
          <div className="mb-6 flex items-center gap-3">
            {[
              ['1', 'Recipient'],
              ['2', 'Amount'],
              ['3', 'Review'],
            ].map(([num, label], index) => (
              <div key={num} className="flex items-center gap-2">
                <span
                  className={`grid h-7 w-7 place-items-center rounded-full text-[11px] font-bold ${
                    index === 0
                      ? 'bg-brand text-ink'
                      : 'bg-slate-100 text-slate-400'
                  }`}
                >
                  {num}
                </span>
                <span
                  className={`text-xs font-semibold ${
                    index === 0 ? 'text-ink' : 'text-slate-400'
                  }`}
                >
                  {label}
                </span>
                {index < 2 && <span className="mx-1 h-px w-5 bg-slate-200 sm:w-9" />}
              </div>
            ))}
          </div>

          <form onSubmit={onSubmit} className="space-y-4">
            <Field
              label="Transfer type"
              as="select"
              value={transfer.type}
              onChange={(e) => {
                update('type', e.target.value);
                setEnquiryDone(false);
                setEnquiryError('');
              }}
            >
              <option value="Bank transfer">To another bank</option>
              <option value="Internal transfer">To Eli Digital Bank</option>
            </Field>

            {transfer.type === 'Bank transfer' && (
              <Field
                label="Destination bank"
                as="select"
                value={transfer.bankCode || ''}
                onChange={(e) => {
                  const selected = bankOptions.find((b) => b.code === e.target.value);
                  setTransfer((old) => ({
                    ...old,
                    bankCode: e.target.value,
                    bank: selected?.name || '',
                    recipientName: '',
                    nameEnquiryRef: '',
                  }));
                  setEnquiryDone(false);
                  setEnquiryError('');
                }}
                required
              >
                <option value="">Select a bank</option>
                {bankOptions.map((bank) => (
                  <option key={bank.code} value={bank.code}>
                    {bank.name}
                  </option>
                ))}
              </Field>
            )}

            <div className="grid gap-3 sm:grid-cols-[1fr_auto] sm:items-end">
              <Field
                label="Account number"
                placeholder="Enter 10-digit account number"
                inputMode="numeric"
                maxLength={10}
                value={transfer.accountNumber}
                onChange={(e) => {
                  update('accountNumber', e.target.value.replace(/\D/g, ''));
                  setEnquiryDone(false);
                  setEnquiryError('');
                  update('recipientName', '');
                }}
                required
              />
              <Button
                type="button"
                variant="secondary"
                className="h-[44px]"
                onClick={handleEnquiry}
                disabled={
                  transfer.accountNumber?.length !== 10 ||
                  (transfer.type === 'Bank transfer' && !transfer.bankCode) ||
                  enquiryLoading
                }
                isLoading={enquiryLoading}
                loadingText="Verifying…"
              >
                Verify account
              </Button>
            </div>

            {enquiryDone && transfer.recipientName && (
              <div className="flex items-center gap-2 rounded-xl border border-green-100 bg-green-50 px-3.5 py-3 text-xs font-semibold text-green-800 fade-in">
                <BadgeCheck size={16} className="text-green-700 shrink-0" />
                <span>Account verified · {transfer.recipientName}</span>
              </div>
            )}

            {enquiryError && (
              <p role="alert" className="rounded-xl bg-rose-50 p-3 text-xs text-rose-700">
                {enquiryError}
              </p>
            )}

            <Field
              label="From account"
              as="select"
              value={transfer.accountId || accounts[0]?.id || ''}
              onChange={(e) => update('accountId', e.target.value)}
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
              placeholder="0.00"
              min="100"
              step="100"
              value={transfer.amount}
              onChange={(e) => update('amount', e.target.value)}
              required
              hint="Minimum transfer amount: ₦100.00"
            />

            <Field
              label="Description (optional)"
              placeholder="What is this transfer for?"
              value={transfer.note}
              onChange={(e) => update('note', e.target.value)}
            />

            <Button
              type="submit"
              className="mt-2 w-full"
              disabled={!transfer.recipientName || !transfer.amount}
            >
              Continue to review <ArrowRight size={15} />
            </Button>
          </form>
        </Panel>

        <div className="space-y-4">
          <Panel className="p-5">
            <div className="flex items-center gap-2">
              <ShieldCheck size={17} className="text-green-600" />
              <h3 className="text-sm font-bold text-ink">Safe transfers</h3>
            </div>
            <ul className="mt-4 space-y-3">
              {[
                'Recipient name is confirmed via CBN name-enquiry',
                'Your encrypted PIN protects every transaction',
                'Large transfers may require additional OTP code',
              ].map((item) => (
                <li key={item} className="flex items-start gap-2 text-xs text-slate-600">
                  <Check size={14} className="mt-0.5 shrink-0 text-green-600" />
                  {item}
                </li>
              ))}
            </ul>
          </Panel>

          <Panel className="p-5">
            <div className="flex items-center gap-2">
              <Clock3 size={16} className="text-gold-800" />
              <h3 className="text-sm font-bold text-ink">Transfer limits</h3>
            </div>
            <div className="mt-4 space-y-3 text-xs">
              <div className="flex justify-between">
                <span className="text-slate-500">Daily limit</span>
                <b className="text-ink">₦5,000,000</b>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Used today</span>
                <b className="text-ink">₦175,000</b>
              </div>
              <div className="h-1.5 overflow-hidden rounded-full bg-slate-100">
                <div className="h-full w-[8%] rounded-full bg-brand" />
              </div>
            </div>
          </Panel>

          <div className="rounded-2xl border border-slate-200 bg-white p-4 text-[11px] leading-5 text-slate-500">
            Always confirm the recipient’s name before you send. Eli will never ask you to share your PIN or one-time code.
          </div>
        </div>
      </div>
    </div>
  );
}

export default TransfersPage;
