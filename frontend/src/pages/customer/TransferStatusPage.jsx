import React, { useState } from 'react';
import { ArrowRight, Check, Clock3, Download } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import {
  useTransactionQuery,
  useWithdrawalQuery,
  usePayoutQuery,
  useCancelWithdrawalMutation,
} from '../../store/api';
import { money } from '../../data';

export function TransferStatusPage({ transfer, setView }) {
  const { data: transaction } = useTransactionQuery(transfer.transactionId, {
    skip: !transfer.transactionId || transfer.isWithdrawal,
    pollingInterval: 5000,
  });

  const { data: withdrawal } = useWithdrawalQuery(transfer.transactionId, {
    skip: !transfer.transactionId || !transfer.isWithdrawal,
    pollingInterval: 5000,
  });

  const { data: payout } = usePayoutQuery(transfer.transactionId, {
    skip: !transfer.transactionId || !transfer.isPayout,
    pollingInterval: 5000,
  });

  const result = payout || withdrawal || transaction;
  const status = result?.status || result?.state || transfer.status || 'PROCESSING';
  const [cancelWithdrawal, cancelState] = useCancelWithdrawalMutation();
  const [cancelError, setCancelError] = useState('');

  const completed = ['COMPLETED', 'SUCCESS', 'SUCCESSFUL'].includes(status);
  const failed = ['FAILED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'REVERSED'].includes(status);
  const tone = completed ? 'green' : failed ? 'red' : 'amber';

  const handleCancel = async () => {
    setCancelError('');
    try {
      await cancelWithdrawal(transfer.transactionId).unwrap();
    } catch (failure) {
      setCancelError(
        failure?.data?.detail || failure?.data?.message || 'This withdrawal can no longer be cancelled.'
      );
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title={transfer.isWithdrawal ? 'Withdrawal status' : 'Transfer status'}
        description="Real-time transaction status from the banking ledger service."
      />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.2fr)_minmax(270px,0.8fr)]">
        <Panel className="p-6 text-center sm:p-9">
          <div
            className={`mx-auto grid h-16 w-16 place-items-center rounded-2xl ${
              completed
                ? 'bg-green-50 text-green-600'
                : failed
                ? 'bg-rose-50 text-rose-600'
                : 'bg-amber-50 text-amber-700'
            }`}
          >
            {completed ? (
              <Check size={30} strokeWidth={2.5} />
            ) : (
              <Clock3 size={28} />
            )}
          </div>

          <div className="mt-3">
            <StatusBadge tone={tone}>{status.replaceAll('_', ' ')}</StatusBadge>
          </div>

          <h2 className="mt-3 text-2xl font-bold tracking-tight text-ink">
            {completed
              ? 'The transaction is complete.'
              : failed
              ? 'The transaction did not complete.'
              : 'The transaction is being processed.'}
          </h2>

          <p className="mt-2 text-xs text-slate-500">
            {result?.reason ||
              result?.message ||
              'This status updates automatically from the service as processing progresses.'}
          </p>

          <p className="mt-5 text-[32px] font-bold tracking-tight text-ink">
            {money(Number(transfer.amount) || 0)}
          </p>

          <div className="mx-auto mt-6 max-w-sm divide-y divide-slate-100 border-y border-slate-100 text-left">
            {[
              ['Recipient', transfer.recipientName || transfer.accountNumber || '—'],
              ['Bank', transfer.bank || '—'],
              ['Reference', transfer.transactionId || '—'],
              [
                'Date',
                result?.createdAt
                  ? new Date(result.createdAt).toLocaleString()
                  : '—',
              ],
            ].map(([label, value]) => (
              <div key={label} className="flex justify-between gap-3 py-3 text-xs">
                <span className="text-slate-500">{label}</span>
                <span className="text-right font-semibold text-ink break-all font-mono">
                  {value}
                </span>
              </div>
            ))}
          </div>

          {cancelError && (
            <p role="alert" className="mt-3 text-xs text-rose-700 font-medium">
              {cancelError}
            </p>
          )}

          <div className="mt-6 flex flex-wrap justify-center gap-2">
            {transfer.isWithdrawal && status === 'PENDING_APPROVAL' && (
              <Button
                variant="secondary"
                disabled={cancelState.isLoading}
                isLoading={cancelState.isLoading}
                loadingText="Cancelling…"
                onClick={handleCancel}
              >
                Cancel withdrawal
              </Button>
            )}
            <Button variant="dark" onClick={() => window.print()}>
              <Download size={15} /> Download receipt
            </Button>
            <Button onClick={() => setView('overview')}>
              Back to overview <ArrowRight size={15} />
            </Button>
          </div>
        </Panel>

        <Panel className="h-fit p-5">
          <h3 className="text-sm font-bold text-ink">Transaction details</h3>
          <p className="mt-3 text-xs leading-5 text-slate-500">
            A downloadable receipt is generated once the transfer settles. A transaction alert is also delivered to your verified email address.
          </p>
          <p className="mt-4 break-all rounded-xl bg-slate-50 p-3 text-[11px] text-slate-600 font-mono">
            {result?.description || transfer.note || 'Internal ledger settlement'}
          </p>
        </Panel>
      </div>
    </div>
  );
}

export default TransferStatusPage;
