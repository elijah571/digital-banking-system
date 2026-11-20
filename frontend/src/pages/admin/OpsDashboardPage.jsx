import React from 'react';
import {
  Activity,
  ArrowRight,
  Banknote,
  Coins,
  FileCheck2,
  FileClock,
  Fingerprint,
  RefreshCw,
  ShieldAlert,
  Users,
} from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Metric } from '../../components/common/Metric';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import {
  useAdminAuditQuery,
  useAllAccountsQuery,
  useAllTransactionsQuery,
  useCryptoReviewsQuery,
  useKycReviewsQuery,
  usePendingPayoutsQuery,
  useReconciliationQuery,
  useUsersQuery,
} from '../../store/api';
import { money } from '../../data';
import { hasPermission } from '../../utils/permissions';

const shortId = (value) => value ? `${String(value).slice(0, 8)}…` : '—';
const localTime = (value) => value ? new Date(value).toLocaleString() : '—';

export function OpsDashboardPage({ setView, currentUser }) {
  const users = useUsersQuery(undefined, { skip: !hasPermission(currentUser, 'customers.read') });
  const accounts = useAllAccountsQuery(undefined, { skip: !hasPermission(currentUser, 'accounts.read') });
  const transactions = useAllTransactionsQuery(undefined, { skip: !hasPermission(currentUser, 'transactions.read') });
  const payouts = usePendingPayoutsQuery({ state: 'PENDING_APPROVAL' }, { skip: !hasPermission(currentUser, 'payouts.read') });
  const kyc = useKycReviewsQuery(undefined, { skip: !hasPermission(currentUser, 'kyc.read') });
  const crypto = useCryptoReviewsQuery(undefined, { skip: !hasPermission(currentUser, 'crypto.transactions.read') });
  const reconciliation = useReconciliationQuery(undefined, { skip: !hasPermission(currentUser, 'reconciliation.read') });
  const audit = useAdminAuditQuery({ limit: 8 }, { skip: !hasPermission(currentUser, 'audit.logs.read') });

  const queries = [users, accounts, transactions, payouts, kyc, crypto, reconciliation, audit];
  const refresh = () => queries.forEach((query) => query.refetch?.());
  const payoutRows = payouts.data || [];
  const kycRows = kyc.data || [];
  const kycCount = kycRows.reduce((sum, item) => sum + Number(item.identityStatus === 'PENDING_REVIEW') + Number(item.upgradeStatus === 'PENDING_REVIEW'), 0);
  const riskCount = (transactions.data || []).filter((item) => item.status === 'FLAGGED').length;
  const payoutTotal = payoutRows.reduce((sum, item) => sum + (Number(item.amount) || 0), 0);
  const auditEntries = audit.data?.entries || [];
  const canReadPayouts = hasPermission(currentUser, 'payouts.read');
  const canReadKyc = hasPermission(currentUser, 'kyc.read');
  const canReadCrypto = hasPermission(currentUser, 'crypto.transactions.read');
  const canReadRisk = hasPermission(currentUser, 'risk.alerts.read');
  const isLoading = queries.some((query) => query.isLoading);
  const failures = queries.filter((query) => query.isError);

  const downloadReport = () => {
    const rows = [
      ['Section', 'Reference', 'Status', 'Amount', 'Date'],
      ...payoutRows.map((row) => ['Pending payout', row.payoutId, row.status, row.amount, row.createdAt]),
      ...(transactions.data || []).map((row) => ['Transaction', row.id, row.status, row.amount, row.createdAt]),
      ...kycRows.map((row) => ['KYC review', row.ownerId, `${row.identityStatus || ''} ${row.upgradeStatus || ''}`.trim(), '', row.submittedAt]),
      ...auditEntries.map((row) => ['Audit event', row.entityId, row.action, '', row.occurredAt]),
      ['Reconciliation', '', reconciliation.data?.clean ? 'BALANCED' : reconciliation.data ? 'ISSUES' : 'UNAVAILABLE', '', new Date().toISOString()],
    ];
    const csv = rows.map((row) => row.map((cell) => `"${String(cell ?? '').replaceAll('"', '""')}"`).join(',')).join('\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'eli-pay-operations-report.csv';
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="fade-in">
      <PageHeader
        eyebrow="OPERATIONS WORKSPACE"
        title="Operations overview"
        description="Live queues and control checks from the banking services. Open a queue to review and record an audited decision."
        actions={<>
          <Button variant="secondary" onClick={refresh}>
            <RefreshCw size={14} /> Refresh
          </Button>
          <Button variant="secondary" onClick={downloadReport}>
            Export report
          </Button>
        </>}
      />

      {failures.length > 0 && (
        <div role="alert" className="mb-4 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          Some operations data could not be loaded ({failures.length} of {queries.length} requests). Check your admin access or refresh the page.
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Metric label="Pending payouts" value={!canReadPayouts || payouts.isError ? '—' : payouts.isLoading ? '…' : String(payoutRows.length)} change={canReadPayouts ? 'Awaiting operations decision' : 'Permission not assigned'} icon={FileClock} color="gold" />
        <Metric label="Payout value" value={!canReadPayouts || payouts.isError ? '—' : payouts.isLoading ? '…' : money(payoutTotal)} change={canReadPayouts ? 'Pending approval volume' : 'Permission not assigned'} icon={Banknote} color="green" />
        <Metric label="KYC decisions" value={!canReadKyc || kyc.isError ? '—' : kyc.isLoading ? '…' : String(kycCount)} change={canReadKyc ? 'Identity and upgrade reviews' : 'Permission not assigned'} icon={Fingerprint} color="amber" />
        <Metric label="Risk flagged" value={!canReadRisk || transactions.isError ? '—' : transactions.isLoading ? '…' : String(riskCount)} change={canReadRisk ? 'Transactions held for review' : 'Permission not assigned'} icon={ShieldAlert} color="amber" />
      </div>

      <div className="mt-5 grid gap-5 xl:grid-cols-[1.2fr_0.8fr]">
        <Panel className="overflow-hidden">
          <div className="flex items-center justify-between border-b border-slate-100 p-5">
            <div>
              <h2 className="text-sm font-bold text-ink">Work queues</h2>
              <p className="mt-1 text-xs text-slate-500">Items needing an administrator’s attention</p>
            </div>
          </div>
          <div className="divide-y divide-slate-100">
            {canReadPayouts && <QueueLink icon={FileClock} title="Payout approvals" detail="Review outbound withdrawals" count={payouts.isError ? 'Unavailable' : payoutRows.length} onClick={() => setView('payouts')} />}
            {canReadKyc && <QueueLink icon={Fingerprint} title="Identity and account upgrades" detail="Inspect submitted documents and prompted face recordings" count={kyc.isError ? 'Unavailable' : kycCount} onClick={() => setView('kyc-reviews')} />}
            {canReadCrypto && <QueueLink icon={Coins} title="Crypto reviews" detail="Review crypto deposits and withdrawal requests" count={crypto.isError ? 'Unavailable' : (crypto.data || []).length} onClick={() => setView('crypto-operations')} />}
            {canReadRisk && <QueueLink icon={ShieldAlert} title="Risk alerts" detail="Inspect transactions held by risk rules" count={transactions.isError ? 'Unavailable' : riskCount} onClick={() => setView('risk-alerts')} />}
          </div>
        </Panel>

        <Panel className="p-5">
          <div className="flex items-start justify-between gap-3">
            <div>
              <h2 className="text-sm font-bold text-ink">Platform controls</h2>
              <p className="mt-1 text-xs text-slate-500">Service data and ledger integrity</p>
            </div>
            <Activity size={17} className="text-slate-400" />
          </div>
          <div className="mt-5 space-y-4">
            <ControlRow label="Ledger reconciliation" loading={reconciliation.isLoading} error={reconciliation.isError} status={reconciliation.data ? (reconciliation.data.clean ? 'Balanced' : 'Issues found') : null} tone={reconciliation.data?.clean ? 'green' : 'amber'} />
            <ControlRow label="Customer records" loading={users.isLoading} error={users.isError} status={users.data ? `${users.data.length} users` : null} />
            <ControlRow label="Bank accounts" loading={accounts.isLoading} error={accounts.isError} status={accounts.data ? `${accounts.data.length} accounts` : null} />
            <ControlRow label="Crypto review queue" loading={crypto.isLoading} error={crypto.isError} status={crypto.data ? `${crypto.data.length} awaiting review` : null} />
          </div>
          <button onClick={() => setView('reconciliation')} className="mt-5 inline-flex items-center gap-1 text-xs font-semibold text-gold-800 hover:text-gold-900">
            Open reconciliation <ArrowRight size={13} />
          </button>
        </Panel>
      </div>

      <div className="mt-5 grid gap-5 xl:grid-cols-[0.8fr_1.2fr]">
        <Panel className="p-5">
          <div className="flex items-start justify-between gap-3">
            <div>
              <h2 className="text-sm font-bold text-ink">Customer operations</h2>
              <p className="mt-1 text-xs text-slate-500">Account access and customer records</p>
            </div>
            <Users size={17} className="text-slate-400" />
          </div>
          <div className="mt-5 grid grid-cols-2 gap-3">
            <MiniStat label="Customers" value={users.isError ? '—' : users.isLoading ? '…' : users.data?.length ?? 0} />
            <MiniStat label="Accounts" value={accounts.isError ? '—' : accounts.isLoading ? '…' : accounts.data?.length ?? 0} />
          </div>
          <button onClick={() => setView('customers')} className="mt-5 inline-flex items-center gap-1 text-xs font-semibold text-gold-800 hover:text-gold-900">
            Manage customers <ArrowRight size={13} />
          </button>
        </Panel>

        <Panel className="overflow-hidden">
          <div className="flex items-center justify-between border-b border-slate-100 p-5">
            <div>
              <h2 className="text-sm font-bold text-ink">Recent audit activity</h2>
              <p className="mt-1 text-xs text-slate-500">Append-only administrative and ledger events</p>
            </div>
            <button onClick={() => setView('audit-log')} className="text-xs font-semibold text-gold-800 hover:text-gold-900">View all</button>
          </div>
          {audit.isLoading ? <p className="p-5 text-sm text-slate-500">Loading audit activity…</p> : audit.isError ? <p className="p-5 text-sm text-rose-700">Audit activity could not be loaded.</p> : auditEntries.length ? (
            <div className="divide-y divide-slate-100">
              {auditEntries.slice(0, 5).map((entry, index) => (
                <div key={`${entry.source || 'audit'}-${entry.id || index}`} className="flex items-center justify-between gap-4 px-5 py-3">
                  <div className="min-w-0">
                    <p className="truncate text-xs font-semibold text-ink">{entry.action || 'System event'} · {entry.entityType || 'Activity'}</p>
                    <p className="mt-1 truncate text-[10px] text-slate-500">{entry.actor || 'System'} · {shortId(entry.entityId)}</p>
                  </div>
                  <div className="shrink-0 text-right">
                    <StatusBadge tone="slate">{entry.source || 'SERVICE'}</StatusBadge>
                    <p className="mt-1 text-[10px] text-slate-400">{localTime(entry.occurredAt)}</p>
                  </div>
                </div>
              ))}
            </div>
          ) : <p className="p-5 text-sm text-slate-500">No audit activity is available yet.</p>}
        </Panel>
      </div>

      {isLoading && <p className="mt-4 text-xs text-slate-400">Loading live operations data…</p>}
    </div>
  );
}

function QueueLink({ icon: Icon, title, detail, count, onClick }) {
  return (
    <button onClick={onClick} className="flex w-full items-center gap-3 p-4 text-left transition hover:bg-slate-50 sm:px-5">
      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-slate-50 text-slate-600"><Icon size={16} /></span>
      <span className="min-w-0 flex-1"><span className="block text-xs font-semibold text-ink">{title}</span><span className="mt-1 block text-[10px] text-slate-500">{detail}</span></span>
      <StatusBadge tone={count === 'Unavailable' ? 'red' : Number(count) > 0 ? 'amber' : 'green'}>{count}</StatusBadge>
      <ArrowRight size={14} className="shrink-0 text-slate-400" />
    </button>
  );
}

function ControlRow({ label, status, loading, error, tone = 'slate' }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-xs text-slate-600">{label}</span>
      <StatusBadge tone={error ? 'red' : tone}>{loading ? 'Loading' : error ? 'Unavailable' : status || 'No data'}</StatusBadge>
    </div>
  );
}

function MiniStat({ label, value }) {
  return <div className="rounded-xl bg-slate-50 p-3"><p className="text-[10px] font-medium text-slate-500">{label}</p><p className="mt-1 text-lg font-bold text-ink">{value}</p></div>;
}

export default OpsDashboardPage;
