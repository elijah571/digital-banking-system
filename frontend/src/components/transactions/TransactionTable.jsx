import React from 'react';
import {
  ArrowDownLeft,
  ArrowUpRight,
  CreditCard,
  Send,
  ChevronRight,
} from 'lucide-react';
import { StatusBadge } from '../feedback/StatusBadge';
import { money } from '../../data';

export function TransactionTable({ rows = [], onSelect, compact = false }) {
  const typeIcon = (type) => {
    switch (type?.toUpperCase()) {
      case 'DEPOSIT':
        return ArrowDownLeft;
      case 'WITHDRAWAL':
        return ArrowUpRight;
      case 'PAYMENT':
        return CreditCard;
      default:
        return Send;
    }
  };

  const displayRows = compact ? rows.slice(0, 4) : rows;

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[650px] text-left">
        <thead>
          <tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-[0.11em] text-slate-400">
            <th className="px-5 py-3">Description</th>
            <th className="px-4 py-3">Reference</th>
            <th className="px-4 py-3">Type</th>
            <th className="px-4 py-3">Amount</th>
            <th className="px-4 py-3">Status</th>
            <th className="px-5 py-3" />
          </tr>
        </thead>
        <tbody>
          {displayRows.map((row) => {
            const Icon = typeIcon(row.type);
            const isCredit = row.amount > 0;
            return (
              <tr
                key={row.id}
                onClick={() => onSelect?.(row)}
                className="cursor-pointer border-b border-slate-50 last:border-0 hover:bg-slate-50/80 transition"
              >
                <td className="px-5 py-3.5">
                  <div className="flex items-center gap-3">
                    <span
                      className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl ${
                        isCredit
                          ? 'bg-green-50 text-green-700'
                          : 'bg-slate-100 text-slate-600'
                      }`}
                    >
                      <Icon size={15} />
                    </span>
                    <div>
                      <span className="block text-xs font-semibold text-ink">
                        {row.title}
                      </span>
                      <span className="mt-0.5 block text-[10px] text-slate-400">
                        {row.subtitle}
                      </span>
                    </div>
                  </div>
                </td>
                <td className="px-4 py-3.5 text-[11px] font-mono text-slate-500">
                  {row.id}
                </td>
                <td className="px-4 py-3.5 text-xs text-slate-500">
                  {row.type}
                </td>
                <td
                  className={`whitespace-nowrap px-4 py-3.5 text-xs font-bold ${
                    isCredit ? 'text-green-700' : 'text-ink'
                  }`}
                >
                  {isCredit ? '+' : '−'}
                  {money(Math.abs(row.amount))}
                </td>
                <td className="px-4 py-3.5">
                  <StatusBadge>{row.status}</StatusBadge>
                </td>
                <td className="px-5 py-3.5 text-right text-slate-400">
                  <ChevronRight size={15} />
                </td>
              </tr>
            );
          })}
          {displayRows.length === 0 && (
            <tr>
              <td colSpan="6" className="px-5 py-12 text-center text-sm text-slate-400">
                No transactions match these filters.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

export default TransactionTable;
