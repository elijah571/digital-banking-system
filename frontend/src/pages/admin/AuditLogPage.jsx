import React from 'react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { StatusBadge } from '../../components/feedback/StatusBadge';

export function AuditLogPage({ entries = [] }) {
  return (
    <div className="fade-in">
      <PageHeader
        title="Audit log"
        description="A tamper-evident, append-only record of administrative, ledger, and security activity."
      />

      <Panel className="overflow-hidden">
        <div className="border-b border-slate-100 p-5">
          <h2 className="text-sm font-bold text-ink">Recent system events</h2>
          <p className="mt-0.5 text-[10px] text-slate-400">
            Audit entries are permanently recorded with cryptographic hashes and cannot be edited.
          </p>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full min-w-[700px] text-left">
            <thead>
              <tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-wider text-slate-400">
                <th className="px-5 py-3">Event</th>
                <th className="px-4 py-3">Actor / Reference</th>
                <th className="px-4 py-3">Time</th>
                <th className="px-5 py-3">Source</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry, idx) => (
                <tr
                  key={`${entry.source || 'audit'}-${entry.id || idx}`}
                  className="border-b border-slate-50 last:border-0 hover:bg-slate-50/70 transition"
                >
                  <td className="px-5 py-4 text-xs font-semibold text-ink">
                    {entry.action} · <span className="font-normal text-slate-600">{entry.entityType}</span>
                  </td>
                  <td className="px-4 py-4 text-xs text-slate-500 font-mono">
                    {entry.actor || 'System'} · {entry.entityId || '—'}
                  </td>
                  <td className="whitespace-nowrap px-4 py-4 text-[11px] text-slate-500">
                    {entry.occurredAt
                      ? new Date(entry.occurredAt).toLocaleString()
                      : '—'}
                  </td>
                  <td className="px-5 py-4">
                    <StatusBadge tone="slate">{entry.source || 'SERVICE'}</StatusBadge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {entries.length === 0 && (
          <p className="p-8 text-center text-xs text-slate-400">
            No audit records returned for this filter.
          </p>
        )}
      </Panel>
    </div>
  );
}

export default AuditLogPage;
