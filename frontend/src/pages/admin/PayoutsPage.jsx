import React, { useState } from 'react';
import { Banknote, FileClock, RefreshCw, ShieldAlert, Store } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Metric } from '../../components/common/Metric';
import { PayoutTable } from '../../components/payouts/PayoutTable';
import { PayoutReviewModal } from '../../components/modals/PayoutReviewModal';
import { money } from '../../data';
import {
  useAdminMerchantsQuery,
  useCancelAndRefundPayoutMutation,
  useManualReviewPayoutsQuery,
  useReroutePayoutMutation,
} from '../../store/api';
import { isSuperAdmin } from '../../utils/permissions';

export function PayoutsPage({
  payouts = [],
  onDecision,
  onRefresh,
  isRefreshing = false,
  decisionLoadingId = null,
  canDecide = true,
  canRead = true,
  currentUser,
}) {
  const [selected, setSelected] = useState(null);
  const [providerById, setProviderById] = useState({});
  const [reasonById, setReasonById] = useState({});
  const [actionError, setActionError] = useState('');
  const [busyAction, setBusyAction] = useState(null);
  const { data: reviewPayouts = [], isLoading: reviewLoading, isError: reviewError, refetch: refreshReview } =
    useManualReviewPayoutsQuery({ limit: 100 }, { skip: !canRead });
  const { data: merchants = [], isLoading: merchantsLoading, isError: merchantsError } =
    useAdminMerchantsQuery(undefined, { skip: !isSuperAdmin(currentUser) });
  const [reroutePayout] = useReroutePayoutMutation();
  const [cancelAndRefund] = useCancelAndRefundPayoutMutation();

  const handleDecision = async (payout, status, reason) => {
    await onDecision?.(payout, status, reason);
    setSelected(null);
  };

  const handleReroute = async (payout) => {
    setActionError('');
    setBusyAction({ id: payout.payoutId, type: 'reroute' });
    try {
      await reroutePayout({ id: payout.payoutId, provider: providerById[payout.payoutId] || 'monnify' }).unwrap();
      await refreshReview();
      onRefresh?.();
    } catch (failure) {
      setActionError(failure?.data?.detail || 'The payout could not be rerouted.');
    } finally {
      setBusyAction(null);
    }
  };

  const handleCancelAndRefund = async (payout) => {
    const reason = (reasonById[payout.payoutId] || '').trim();
    if (!reason) {
      setActionError('Enter an operations reason before cancelling and refunding.');
      return;
    }
    setActionError('');
    setBusyAction({ id: payout.payoutId, type: 'refund' });
    try {
      await cancelAndRefund({ id: payout.payoutId, reason }).unwrap();
      setReasonById((current) => ({ ...current, [payout.payoutId]: '' }));
      await refreshReview();
      onRefresh?.();
    } catch (failure) {
      setActionError(failure?.data?.detail || 'The payout could not be cancelled and refunded.');
    } finally {
      setBusyAction(null);
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Payout approvals"
        description="Review pending outbound withdrawal requests requiring operations dual-authorization."
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-3">
        <Metric
          label="Awaiting review"
          value={String(payouts.length)}
          icon={FileClock}
        />
        <Metric
          label="Total value"
          value={money(payouts.reduce((sum, p) => sum + (Number(p.amount) || 0), 0))}
          icon={Banknote}
          color="green"
        />
        <Metric
          label="Flagged by risk"
          value={String(payouts.filter((p) => p.risk === 'High').length)}
          icon={ShieldAlert}
          color="amber"
        />
      </div>

      <Panel className="overflow-hidden">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <div>
            <h2 className="text-sm font-bold text-ink">Pending payouts</h2>
            <p className="mt-0.5 text-[10px] text-slate-400">
              Verify destination bank details and risk flags before approving
            </p>
          </div>
          <Button
            variant="secondary"
            className="min-h-8 px-3 py-1.5 text-xs"
            onClick={onRefresh}
            isLoading={isRefreshing}
            loadingText="Refreshing…"
          >
            <RefreshCw size={13} /> Refresh
          </Button>
        </div>

        <PayoutTable
          payouts={payouts}
          onReview={setSelected}
          onDecision={canDecide ? handleDecision : undefined}
          decisionLoadingId={decisionLoadingId}
        />
      </Panel>

      <Panel className="mt-5 overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <h2 className="text-sm font-bold text-ink">Payouts requiring manual review</h2>
            <p className="mt-0.5 text-[10px] text-slate-400">Provider retries are exhausted or require an operator decision.</p>
          </div>
          <Button variant="secondary" className="min-h-8 px-3 py-1.5 text-xs" onClick={refreshReview} isLoading={reviewLoading} loadingText="Refreshing…">
            <RefreshCw size={13} /> Refresh queue
          </Button>
        </div>
        {actionError && <p role="alert" className="m-4 rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs text-rose-800">{actionError}</p>}
        {reviewError ? <p role="alert" className="p-5 text-sm text-rose-700">The manual review queue could not be loaded.</p> : reviewLoading ? <p className="p-5 text-sm text-slate-500">Loading manual review queue…</p> : reviewPayouts.length ? (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-left">
              <thead><tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-wider text-slate-400"><th className="px-5 py-3">Payout</th><th className="px-4 py-3">Amount</th><th className="px-4 py-3">Provider</th><th className="px-4 py-3">Retries / failure</th><th className="px-5 py-3">Operations</th></tr></thead>
              <tbody>{reviewPayouts.map((payout) => <tr key={payout.payoutId} className="border-b border-slate-50 last:border-0">
                <td className="px-5 py-3"><p className="text-xs font-semibold text-ink">#{payout.payoutId} · {payout.destinationAccountName || 'Bank recipient'}</p><p className="mt-1 text-[10px] text-slate-500">{payout.bankCode} · account ending {payout.destinationAccountNumber?.slice(-4) || '—'} · {payout.state}</p></td>
                <td className="px-4 py-3 text-xs font-bold text-ink">{money(Number(payout.amount))} {payout.currency}</td>
                <td className="px-4 py-3"><span className="text-xs text-slate-700">{payout.activeProvider || 'Unassigned'}</span>{canDecide && <select aria-label={`Reroute provider for payout ${payout.payoutId}`} className="mt-1 block rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs" value={providerById[payout.payoutId] || 'monnify'} onChange={(event) => setProviderById((current) => ({ ...current, [payout.payoutId]: event.target.value }))}><option value="flutterwave">Flutterwave</option><option value="monnify">Monnify</option></select>}</td>
                <td className="max-w-xs px-4 py-3 text-xs text-slate-600"><p>{payout.retryCount} attempts</p><p className="mt-1 break-words text-[10px] text-rose-700">{payout.failureReason || 'No failure detail recorded'}</p></td>
                <td className="px-5 py-3">{canDecide ? <div className="flex min-w-52 flex-col gap-2"><Button variant="secondary" className="min-h-8 px-2.5 py-1.5 text-[11px]" disabled={Boolean(busyAction)} onClick={() => handleReroute(payout)} isLoading={busyAction?.id === payout.payoutId && busyAction.type === 'reroute'} loadingText="Rerouting…">Reroute</Button><input aria-label={`Reason to cancel payout ${payout.payoutId}`} maxLength={500} value={reasonById[payout.payoutId] || ''} onChange={(event) => setReasonById((current) => ({ ...current, [payout.payoutId]: event.target.value }))} placeholder="Cancellation reason" className="min-h-8 rounded-lg border border-slate-200 px-2 text-xs" disabled={Boolean(busyAction)}/><Button variant="secondary" className="min-h-8 px-2.5 py-1.5 text-[11px] text-rose-700" disabled={!reasonById[payout.payoutId]?.trim() || Boolean(busyAction)} onClick={() => handleCancelAndRefund(payout)} isLoading={busyAction?.id === payout.payoutId && busyAction.type === 'refund'} loadingText="Refunding…">Cancel and refund</Button></div> : <span className="text-xs text-slate-500">Read only</span>}</td>
              </tr>)}</tbody>
            </table>
          </div>
        ) : <p className="p-6 text-sm text-slate-500">No payouts currently require manual review.</p>}
      </Panel>

      {isSuperAdmin(currentUser) && <Panel className="mt-5 overflow-hidden">
        <div className="flex items-center gap-2 border-b border-slate-100 p-5"><Store size={16} className="text-slate-500"/><div><h2 className="text-sm font-bold text-ink">Flutterwave merchant directory</h2><p className="mt-0.5 text-[10px] text-slate-400">Business names are returned in alphabetical order.</p></div></div>
        {merchantsLoading ? <p className="p-5 text-sm text-slate-500">Loading merchants…</p> : merchantsError ? <p className="p-5 text-sm text-slate-500">The merchant directory is unavailable for the currently selected payout provider.</p> : merchants.length ? <ul className="divide-y divide-slate-100">{merchants.map((merchant) => <li key={merchant.id} className="flex items-center justify-between gap-4 px-5 py-3"><span className="text-xs font-semibold text-ink">{merchant.businessName}</span><span className="font-mono text-[10px] text-slate-500">{merchant.id}</span></li>)}</ul> : <p className="p-5 text-sm text-slate-500">No Flutterwave merchants were returned.</p>}
      </Panel>}

      {selected && (
        <PayoutReviewModal
          isOpen={Boolean(selected)}
          onClose={() => setSelected(null)}
          payout={selected}
          onDecision={canDecide ? handleDecision : undefined}
          canDecide={canDecide}
          isLoading={Boolean(decisionLoadingId)}
        />
      )}
    </div>
  );
}

export default PayoutsPage;
