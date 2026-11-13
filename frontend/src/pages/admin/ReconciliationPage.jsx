import React from 'react';
import { CheckCheck } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { StatusBadge } from '../../components/feedback/StatusBadge';

export function ReconciliationPage({ data }) {
  const clean = Boolean(data?.clean);
  const issues = data?.issues || [];

  return (
    <div className="fade-in">
      <PageHeader
        title="Ledger reconciliation"
        description="Review double-entry consistency checks and system-wide account balance reconciliations."
      />

      <Panel
        className={`mb-5 flex flex-col gap-4 p-5 sm:flex-row sm:items-center ${
          clean
            ? 'border-green-100 bg-green-50/60'
            : 'border-amber-200 bg-amber-50/60'
        }`}
      >
        <span
          className={`grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-white shadow-sm ${
            clean ? 'text-green-700' : 'text-amber-700'
          }`}
        >
          <CheckCheck size={21} />
        </span>
        <div className="flex-1">
          <h2 className="text-sm font-bold text-ink">
            {data
              ? clean
                ? 'Ledger is balanced and reconciled'
                : 'Reconciliation found ledger discrepancies'
              : 'Loading reconciliation data…'}
          </h2>
          <p className="mt-0.5 text-xs text-slate-600">
            {data
              ? `${issues.length} issue${issues.length === 1 ? '' : 's'} reported by the double-entry verification engine.`
              : 'Fetching report from ledger service…'}
          </p>
        </div>
        <StatusBadge tone={clean ? 'green' : 'amber'}>
          {data ? (clean ? 'All checks passed' : 'Action required') : 'Loading'}
        </StatusBadge>
      </Panel>

      {issues.length > 0 && (
        <Panel className="p-5">
          <h2 className="text-sm font-bold text-ink">Issues requiring manual review</h2>
          <ul className="mt-3 space-y-2">
            {issues.map((issue, index) => (
              <li
                key={`${index}-${issue}`}
                className="rounded-lg bg-amber-50 p-3 text-xs text-amber-900 border border-amber-200"
              >
                {issue}
              </li>
            ))}
          </ul>
        </Panel>
      )}
    </div>
  );
}

export default ReconciliationPage;
