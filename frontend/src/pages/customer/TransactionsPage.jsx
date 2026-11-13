import React, { useMemo, useState } from 'react';
import { Download, Search } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { TransactionTable } from '../../components/transactions/TransactionTable';

export function TransactionsPage({
  transactions = [],
  setView,
  onSelect,
  statement,
}) {
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('All activity');

  const filtered = useMemo(() => {
    return transactions.filter((tx) => {
      const matchesText = `${tx.title} ${tx.id} ${tx.type}`.toLowerCase().includes(query.toLowerCase());
      const matchesType =
        filter === 'All activity' ||
        (filter === 'Money in' ? tx.amount > 0 : tx.amount < 0);
      return matchesText && matchesType;
    });
  }, [transactions, query, filter]);

  const downloadStatement = () => {
    const lines = statement?.content || [];
    if (!lines.length) return;
    const rows = [
      ['Line ID', 'Entry type', 'Reference', 'Direction', 'Amount', 'Running balance'],
      ...lines.map((line) => [
        line.lineId,
        line.entryType,
        line.reference,
        line.direction,
        line.amount,
        line.runningBalance,
      ]),
    ];
    const csv = rows
      .map((row) => row.map((cell) => `"${String(cell ?? '').replaceAll('"', '""')}"`).join(','))
      .join('\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `statement-${statement.accountNumber || 'account'}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Transaction history"
        description="Track every movement and debit/credit across your accounts."
        actions={
          <Button
            variant="secondary"
            onClick={downloadStatement}
            disabled={!statement?.content?.length}
          >
            <Download size={15} /> Download statement
          </Button>
        }
      />

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="inline-flex rounded-xl border border-slate-200 bg-white p-1">
          {['All activity', 'Money in', 'Money out'].map((tab) => (
            <button
              key={tab}
              onClick={() => setFilter(tab)}
              className={`rounded-lg px-3 py-2 text-xs font-semibold transition ${
                filter === tab
                  ? 'bg-brand text-ink shadow-sm'
                  : 'text-slate-500 hover:bg-slate-50'
              }`}
            >
              {tab}
            </button>
          ))}
        </div>

        <label className="relative min-w-[200px] flex-1 sm:w-64 sm:flex-none">
          <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search activity..."
            className="form-control pl-9"
          />
        </label>
      </div>

      <Panel className="overflow-hidden">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <div>
            <h2 className="text-sm font-bold text-ink">All transactions</h2>
            <p className="mt-0.5 text-[10px] text-slate-400">{filtered.length} matching records</p>
          </div>
        </div>

        <TransactionTable rows={filtered} onSelect={onSelect} />

        <div className="border-t border-slate-100 px-5 py-3 text-[10px] text-slate-400">
          Showing {filtered.length} of {transactions.length} loaded transactions
        </div>
      </Panel>

      <div className="mt-4 flex items-center justify-between rounded-xl bg-gold-50/70 px-4 py-3 text-xs">
        <span className="text-slate-600">Need official stamped records for your audit or embassy?</span>
        <button
          onClick={() => setView('support')}
          className="font-semibold text-gold-800 hover:text-gold-900 transition"
        >
          Request certified statement
        </button>
      </div>
    </div>
  );
}

export default TransactionsPage;
