import React from 'react';
import {
  ArrowRight,
  Banknote,
  Download,
  FileCheck2,
  FileClock,
  ShieldAlert,
  Users,
} from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Metric } from '../../components/common/Metric';
import { SectionHeading } from '../../components/common/SectionHeading';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import { PayoutTable } from '../../components/payouts/PayoutTable';
import { money } from '../../data';

export function OpsDashboardPage({
  setView,
  payouts = [],
  users = [],
  accounts = [],
  transactions = [],
  reconciliation,
}) {
  const pendingTotal = payouts.reduce((sum, payout) => sum + (Number(payout.amount) || 0), 0);
  const flagged = transactions.filter((transaction) => transaction.status === 'FLAGGED').length;

  const downloadReport = () => {
    const rows = [
      ['Report section', 'ID', 'Status', 'Amount', 'Created'],
      ...payouts.map((row) => [
        'Pending payout',
        row.payoutId || row.id,
        row.status,
        row.amount,
        row.createdAt || row.requestedAt,
      ]),
      ...transactions.map((row) => [
        'Transaction',
        row.id,
        row.status,
        row.amount,
        row.createdAt,
      ]),
      ...accounts.map((row) => [
        'Account',
        row.accountNumber,
        row.status || row.accountStatus,
        '',
        row.createdAt,
      ]),
      [
        'Reconciliation',
        '',
        reconciliation?.clean ? 'BALANCED' : reconciliation ? 'ISSUES' : 'UNAVAILABLE',
        '',
        new Date().toISOString(),
      ],
    ];
    const csv = rows
      .map((row) => row.map((cell) => `"${String(cell ?? '').replaceAll('"', '""')}"`).join(','))
      .join('\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'banking-operations-report.csv';
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="fade-in">
      <PageHeader
        eyebrow="OPERATIONS WORKSPACE"
        title="Operations overview"
        description="Current payout queue, active accounts, flagged transactions, and ledger reconciliation."
        actions={
          <Button variant="secondary" onClick={downloadReport}>
            <Download size={15} /> Export report
          </Button>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Metric
          label="Pending payouts"
          value={String(payouts.length)}
          change="Awaiting operations decision"
          icon={FileClock}
          color="gold"
        />
        <Metric
          label="Payout value"
          value={money(pendingTotal)}
          change="Total pending volume"
          icon={Banknote}
          color="green"
        />
        <Metric
          label="Flagged transactions"
          value={String(flagged)}
          change="Current fraud warnings"
          icon={ShieldAlert}
          color="amber"
        />
        <Metric
          label="Active customers"
          value={String(users.filter((user) => user.enabled).length)}
          change={`${accounts.length} total accounts`}
          icon={Users}
          color="gold"
        />
      </div>

      <div className="mt-5 grid gap-5 xl:grid-cols-[1.35fr_0.65fr]">
        <Panel className="overflow-hidden">
          <div className="flex items-center justify-between border-b border-slate-100 p-5">
            <div>
              <h2 className="text-sm font-bold text-ink">Payouts needing review</h2>
              <p className="mt-0.5 text-[10px] text-slate-400">
                Outbound withdrawals requiring dual authorization
              </p>
            </div>
            <button
              onClick={() => setView('payouts')}
              className="text-xs font-semibold text-gold-800 hover:text-gold-900 transition"
            >
              View queue <ArrowRight size={13} className="ml-1 inline" />
            </button>
          </div>
          <PayoutTable
            payouts={payouts.slice(0, 3)}
            onReview={() => setView('payouts')}
            compact
          />
        </Panel>

        <Panel className="p-5">
          <SectionHeading
            title="Platform health"
            action="View reconciliation"
            onAction={() => setView('reconciliation')}
          />
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <span className="text-xs text-slate-600">Ledger reconciliation</span>
              <StatusBadge tone={reconciliation?.clean ? 'green' : 'amber'}>
                {reconciliation
                  ? reconciliation.clean
                    ? 'Balanced'
                    : 'Issues found'
                  : 'Loading'}
              </StatusBadge>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-xs text-slate-600">Payout approvals</span>
              <StatusBadge tone="slate">{payouts.length} pending</StatusBadge>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-xs text-slate-600">Account records</span>
              <StatusBadge tone="slate">{accounts.length} active</StatusBadge>
            </div>
          </div>
        </Panel>
      </div>

      <div className="mt-5 grid gap-4 sm:grid-cols-2">
        <button
          onClick={() => setView('risk-alerts')}
          className="rounded-2xl border border-amber-200 bg-amber-50/70 p-4 text-left transition hover:bg-amber-50"
        >
          <div className="flex items-center gap-3">
            <span className="grid h-9 w-9 place-items-center rounded-xl bg-white text-amber-700 shadow-sm">
              <ShieldAlert size={17} />
            </span>
            <div>
              <p className="text-xs font-bold text-ink">{flagged} flagged transactions</p>
              <p className="mt-0.5 text-[10px] text-slate-500">
                Review transactions held by risk and fraud rules.
              </p>
            </div>
            <ArrowRight size={15} className="ml-auto text-amber-700" />
          </div>
        </button>

        <button
          onClick={() => setView('audit-log')}
          className="rounded-2xl border border-slate-200 bg-white p-4 text-left transition hover:bg-slate-50"
        >
          <div className="flex items-center gap-3">
            <span className="grid h-9 w-9 place-items-center rounded-xl bg-gold-50 text-gold-800 shadow-sm">
              <FileCheck2 size={17} />
            </span>
            <div>
              <p className="text-xs font-bold text-ink">Recent admin activity</p>
              <p className="mt-0.5 text-[10px] text-slate-500">
                Inspect immutable append-only operations audit events.
              </p>
            </div>
            <ArrowRight size={15} className="ml-auto text-slate-400" />
          </div>
        </button>
      </div>
    </div>
  );
}

export default OpsDashboardPage;
