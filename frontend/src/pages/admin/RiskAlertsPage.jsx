import React from 'react';
import { ShieldAlert, ShieldCheck } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import { money } from '../../data';

export function RiskAlertsPage({ transactions = [] }) {
  const alerts = transactions.filter((tx) => tx.status === 'FLAGGED');

  return (
    <div className="fade-in">
      <PageHeader
        title="Flagged transactions"
        description="Transactions currently flagged by the automated risk scoring engine."
      />

      <div className="mb-4 flex items-center gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3.5 text-xs text-amber-900">
        <ShieldAlert size={17} className="shrink-0 text-amber-700" />
        <span>
          These records reflect transaction flags. Investigation decisions and ledger quarantines are managed via operations controls.
        </span>
      </div>

      <div className="space-y-3">
        {alerts.map((tx) => (
          <Panel key={tx.id} className="p-5">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <h2 className="text-sm font-bold text-ink">
                  {tx.description || 'Transaction flagged by rules'}
                </h2>
                <p className="mt-1 text-xs text-slate-600 font-mono">
                  {tx.senderAccountNumber || '—'} → {tx.receiverAccountNumber || '—'}
                </p>
                <p className="mt-2 text-[10px] text-slate-400 font-mono">
                  {tx.id} · {tx.createdAt ? new Date(tx.createdAt).toLocaleString() : '—'}
                </p>
              </div>
              <div className="text-right">
                <StatusBadge tone="amber">{tx.status}</StatusBadge>
                <p className="mt-2 text-sm font-bold text-ink">
                  {money(Number(tx.amount) || 0)}
                </p>
              </div>
            </div>
          </Panel>
        ))}
      </div>

      {alerts.length === 0 && (
        <Panel className="p-10 text-center">
          <ShieldCheck className="mx-auto text-green-600" size={32} />
          <p className="mt-3 text-sm font-semibold text-ink">No flagged transactions</p>
          <p className="mt-1 text-xs text-slate-400">
            The transaction risk service returned zero records held for review.
          </p>
        </Panel>
      )}
    </div>
  );
}

export default RiskAlertsPage;
