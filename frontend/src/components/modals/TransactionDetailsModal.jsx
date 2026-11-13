import React from 'react';
import { Download } from 'lucide-react';
import { Modal } from './Modal';
import { Button } from '../buttons/Button';
import { StatusBadge } from '../feedback/StatusBadge';
import { money } from '../../data';

export function TransactionDetailsModal({
  isOpen = true,
  onClose,
  transaction,
}) {
  if (!transaction) return null;

  const isPositive = Number(transaction.amount) > 0;

  return (
    <Modal isOpen={isOpen} onClose={onClose} maxWidth="max-w-md">
      <div>
        <p className="text-[10px] font-bold uppercase tracking-wider text-gold-800">
          Transaction details
        </p>
        <div className="mt-1 flex items-center justify-between gap-3">
          <h2 className="text-lg font-bold text-ink">{transaction.title}</h2>
          <StatusBadge>{transaction.status}</StatusBadge>
        </div>
      </div>

      <p className={`mt-5 text-[26px] font-bold ${isPositive ? 'text-green-700' : 'text-ink'}`}>
        {isPositive ? '+' : '−'}
        {money(Math.abs(Number(transaction.amount) || 0))}
      </p>

      <div className="mt-4 space-y-3 border-y border-slate-100 py-4">
        {[
          ['Reference', transaction.id],
          ['Type', transaction.type],
          ['Status', transaction.status],
          ['Date & Time', transaction.subtitle || '—'],
        ].map(([key, value]) => (
          <div key={key} className="flex justify-between gap-3 text-xs">
            <span className="text-slate-500">{key}</span>
            <span className="text-right font-semibold text-ink break-all">{value}</span>
          </div>
        ))}
      </div>

      <div className="mt-5 flex gap-2">
        <Button
          variant="secondary"
          className="w-full"
          onClick={() => window.print()}
        >
          <Download size={15} /> Download receipt
        </Button>
      </div>
    </Modal>
  );
}

export default TransactionDetailsModal;
