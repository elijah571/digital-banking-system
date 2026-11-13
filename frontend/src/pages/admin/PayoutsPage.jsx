import React, { useState } from 'react';
import { Banknote, FileClock, RefreshCw, ShieldAlert } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Metric } from '../../components/common/Metric';
import { PayoutTable } from '../../components/payouts/PayoutTable';
import { PayoutReviewModal } from '../../components/modals/PayoutReviewModal';
import { money } from '../../data';

export function PayoutsPage({
  payouts = [],
  onDecision,
  onRefresh,
  isRefreshing = false,
  decisionLoadingId = null,
}) {
  const [selected, setSelected] = useState(null);

  const handleDecision = async (payout, status, reason) => {
    await onDecision?.(payout, status, reason);
    setSelected(null);
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
          onDecision={handleDecision}
          decisionLoadingId={decisionLoadingId}
        />
      </Panel>

      {selected && (
        <PayoutReviewModal
          isOpen={Boolean(selected)}
          onClose={() => setSelected(null)}
          payout={selected}
          onDecision={handleDecision}
          isLoading={Boolean(decisionLoadingId)}
        />
      )}
    </div>
  );
}

export default PayoutsPage;
