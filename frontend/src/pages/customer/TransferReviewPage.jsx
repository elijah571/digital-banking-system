import React from 'react';
import { ArrowLeft, ArrowRight, Check, LockKeyhole } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import { money } from '../../data';

export function TransferReviewPage({
  transfer,
  accounts = [],
  onConfirm,
  onBack,
  onFlag,
  isLoading = false,
}) {
  const source = accounts.find((account) => account.id === transfer.accountId) || accounts[0];
  const entries = [
    ['Recipient', transfer.recipientName || 'Verified recipient'],
    ['Bank', transfer.bank || 'Eli‑Pay'],
    ['Account number', transfer.accountNumber || '—'],
    ['From account', `${source?.name || 'Account'} · •••• ${source?.lastFour || '—'}`],
    ['Transfer fee', '₦0.00'],
    ['Reference', 'Generated on submission'],
  ];

  return (
    <div className="fade-in">
      <PageHeader
        title="Review transfer"
        description="Check the details carefully before authorizing your payment."
        actions={
          <Button variant="secondary" onClick={onBack} disabled={isLoading}>
            <ArrowLeft size={15} /> Edit details
          </Button>
        }
      />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.25fr)_minmax(270px,0.75fr)]">
        <Panel className="p-5 sm:p-7">
          <div className="rounded-2xl border border-slate-200 bg-slate-50/80 p-5 text-center">
            <p className="text-[10px] font-bold uppercase tracking-[0.13em] text-slate-400">
              You’re sending
            </p>
            <p className="mt-2 text-[32px] font-bold tracking-tight text-ink">
              {money(Number(transfer.amount) || 0)}
            </p>
            <p className="mt-1 text-xs text-slate-500">
              {transfer.note || 'Personal transfer'}
            </p>
          </div>

          <div className="mt-5 divide-y divide-slate-100">
            {entries.map(([label, value]) => (
              <div key={label} className="flex items-center justify-between gap-3 py-3">
                <span className="text-xs text-slate-500">{label}</span>
                <span className="text-right text-xs font-semibold text-ink break-all">
                  {value}
                </span>
              </div>
            ))}
          </div>

          <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-800">
            You’ll be asked to enter your transaction PIN to authorize this transfer.
          </div>

          <Button
            className="mt-4 w-full"
            onClick={onConfirm}
            isLoading={isLoading}
            loadingText="Processing…"
          >
            Confirm and enter PIN <ArrowRight size={15} />
          </Button>

          {onFlag && (
            <button
              onClick={onFlag}
              className="mt-3 w-full text-center text-[11px] font-semibold text-slate-400 hover:text-gold-800 transition"
            >
              Preview additional OTP verification step
            </button>
          )}
        </Panel>

        <div className="space-y-4">
          <Panel className="p-5">
            <div className="flex items-center gap-2">
              <LockKeyhole size={17} className="text-gold-800" />
              <h3 className="text-sm font-bold text-ink">Security check</h3>
            </div>
            <p className="mt-3 text-xs leading-5 text-slate-500">
              This transfer is protected by your encrypted PIN and real-time fraud monitoring.
            </p>
            <div className="mt-3">
              <StatusBadge tone="green">
                <Check size={12} /> Protected
              </StatusBadge>
            </div>
          </Panel>

          <Panel className="p-5">
            <h3 className="text-sm font-bold text-ink">Need to change something?</h3>
            <p className="mt-2 text-xs leading-5 text-slate-500">
              Go back to adjust the recipient or transfer amount before continuing.
            </p>
            <button
              onClick={onBack}
              className="mt-3 inline-flex items-center gap-1 text-xs font-semibold text-gold-800 hover:text-gold-900 transition"
            >
              Edit transfer <ArrowRight size={13} />
            </button>
          </Panel>
        </div>
      </div>
    </div>
  );
}

export default TransferReviewPage;
