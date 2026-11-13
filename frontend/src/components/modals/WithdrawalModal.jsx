import React, { useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { Modal } from './Modal';
import { Button } from '../buttons/Button';
import { Field } from '../common/Field';
import { money } from '../../data';

export function WithdrawalModal({
  isOpen = true,
  onClose,
  onConfirm,
  withdrawal,
  amount,
  isLoading = false,
  error = '',
}) {
  const [pin, setPin] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    if (pin.length === 4) {
      onConfirm(pin);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} maxWidth="max-w-sm" showClose={!isLoading}>
      <h2 className="text-lg font-bold text-ink">Review withdrawal</h2>
      <p className="mt-1 text-xs text-slate-500">Confirm this payout request before you continue.</p>

      <div className="my-5 rounded-xl bg-slate-50 p-4 border border-slate-100">
        <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">Withdrawal Amount</p>
        <p className="mt-1 text-2xl font-bold text-ink">{money(amount || 0)}</p>
        <div className="mt-3 border-t border-slate-200/60 pt-2 text-xs">
          <p className="font-semibold text-slate-700">
            {withdrawal?.bank} · •••• {withdrawal?.accountNumber?.slice(-4)}
          </p>
          <p className="text-slate-500 mt-0.5">{withdrawal?.recipient}</p>
        </div>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        <Field
          label="Transaction PIN"
          type="password"
          inputMode="numeric"
          maxLength={4}
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))}
          placeholder="••••"
          inputClassName="text-center text-xl tracking-[0.5em] font-mono"
          disabled={isLoading}
          error={error}
          required
        />

        <Button
          type="submit"
          className="w-full"
          disabled={pin.length !== 4 || isLoading}
          isLoading={isLoading}
          loadingText="Submitting withdrawal…"
        >
          Submit withdrawal <ArrowRight size={15} />
        </Button>

        {!isLoading && (
          <button
            type="button"
            onClick={onClose}
            className="w-full text-center text-xs font-semibold text-slate-400 hover:text-slate-600 transition"
          >
            Cancel
          </button>
        )}
      </form>
    </Modal>
  );
}

export default WithdrawalModal;
