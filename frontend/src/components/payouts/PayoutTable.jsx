import React from 'react';
import { Button } from '../buttons/Button';
import { StatusBadge } from '../feedback/StatusBadge';
import { money } from '../../data';

export function PayoutTable({
  payouts = [],
  onReview,
  onDecision,
  compact = false,
  decisionLoadingId = null,
}) {
  const displayPayouts = compact ? payouts.slice(0, 3) : payouts;

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[710px] text-left">
        <thead>
          <tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-[0.1em] text-slate-400">
            <th className="px-5 py-3">Customer</th>
            <th className="px-4 py-3">Destination</th>
            <th className="px-4 py-3">Amount</th>
            <th className="px-4 py-3">Risk</th>
            <th className="px-4 py-3">Requested</th>
            <th className="px-5 py-3 text-right">Action</th>
          </tr>
        </thead>
        <tbody>
          {displayPayouts.length ? (
            displayPayouts.map((payout) => {
              const isApproving = decisionLoadingId === payout.id;
              return (
                <tr
                  key={payout.id}
                  className="border-b border-slate-50 last:border-0 hover:bg-slate-50/70 transition"
                >
                  <td className="px-5 py-3.5">
                    <div className="text-xs font-semibold text-ink">{payout.name}</div>
                    <div className="mt-0.5 text-[10px] font-mono text-slate-400">{payout.id}</div>
                  </td>
                  <td className="px-4 py-3.5 text-xs text-slate-600">{payout.bank}</td>
                  <td className="whitespace-nowrap px-4 py-3.5 text-xs font-bold text-ink">
                    {money(payout.amount)}
                  </td>
                  <td className="px-4 py-3.5">
                    <StatusBadge>{payout.risk}</StatusBadge>
                  </td>
                  <td className="px-4 py-3.5 text-[10px] text-slate-500">
                    {payout.requested}
                  </td>
                  <td className="px-5 py-3.5 text-right">
                    <div className="flex justify-end gap-1.5">
                      <Button
                        variant="secondary"
                        className="min-h-8 px-2.5 py-1.5 text-[11px]"
                        onClick={() => onReview?.(payout)}
                      >
                        View
                      </Button>
                      {!compact && onDecision && (
                        <Button
                          className="min-h-8 px-2.5 py-1.5 text-[11px]"
                          onClick={() => onDecision(payout, 'Approved')}
                          isLoading={isApproving}
                          loadingText="Approving…"
                        >
                          Approve
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })
          ) : (
            <tr>
              <td colSpan="6" className="p-10 text-center text-xs text-slate-400">
                No payouts are waiting for review.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

export default PayoutTable;
